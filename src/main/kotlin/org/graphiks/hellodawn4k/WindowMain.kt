package org.graphiks.hellodawn4k

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.graphiks.dawn4k.DawnCommandEncoder
import org.graphiks.dawn4k.toolkit.DawnSession
import org.graphiks.dawn4k.toolkit.DawnSessionOptions
import org.graphiks.dawn4k.toolkit.createRenderTexture
import org.graphiks.dawn4k.toolkit.glfw.DawnWindow
import org.graphiks.dawn4k.toolkit.glfw.DawnWindowEvent
import org.graphiks.dawn4k.toolkit.glfw.DawnWindowOptions
import org.graphiks.dawn4k.toolkit.glfw.withDawnWindow
import org.graphiks.dawn4k.toolkit.surface.DawnFrame
import org.graphiks.dawn4k.toolkit.surface.DawnPresentation
import org.graphiks.webgpu.ArrayBuffer
import org.graphiks.webgpu.GPUBufferUsage
import org.graphiks.webgpu.GPUMapMode
import org.graphiks.webgpu.GPULoadOp
import org.graphiks.webgpu.GPUStoreOp
import org.graphiks.webgpu.GPUTextureFormat
import org.graphiks.webgpu.descriptors.BindGroupDescriptor
import org.graphiks.webgpu.descriptors.BindGroupEntry
import org.graphiks.webgpu.descriptors.BufferDescriptor
import org.graphiks.webgpu.descriptors.Color
import org.graphiks.webgpu.descriptors.ColorTargetState
import org.graphiks.webgpu.descriptors.ComputePipelineDescriptor
import org.graphiks.webgpu.descriptors.Extent3D
import org.graphiks.webgpu.descriptors.FragmentState
import org.graphiks.webgpu.descriptors.ProgrammableStage
import org.graphiks.webgpu.descriptors.RenderPassDescriptor
import org.graphiks.webgpu.descriptors.RenderPassColorAttachment
import org.graphiks.webgpu.descriptors.RenderPipelineDescriptor
import org.graphiks.webgpu.descriptors.ShaderModuleDescriptor
import org.graphiks.webgpu.descriptors.TexelCopyBufferInfo
import org.graphiks.webgpu.descriptors.TexelCopyTextureInfo
import org.graphiks.webgpu.descriptors.VertexState
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

/** Samples traced per presented frame; the on-screen image converges as frames accumulate. */
const val WINDOW_SAMPLES_PER_FRAME = 4

// GLFW constants forwarded as-is by the toolkit's window events.
private const val GLFW_RELEASE = 0
private const val GLFW_BUTTON_LEFT = 0
private const val GLFW_KEY_R = 82
private const val GLFW_KEY_ESCAPE = 256

/**
 * Windowed Cornell box: each frame dispatches [WINDOW_SAMPLES_PER_FRAME] path-traced
 * samples per pixel into a persistent radiance buffer (progressive mean), then a
 * fullscreen pass tonemaps it onto the swapchain. Drag orbits the camera, the wheel
 * zooms, R resets and Escape closes; any camera motion restarts the accumulation.
 * The internal render resolution is fixed and stretched to the window extent.
 */
fun main() = runBlocking {
    val frameLimit = System.getProperty("cornell.frames")?.toIntOrNull() ?: 0
    val camera = OrbitCamera()
    // Smoke-test hook: render the finite run from an explicit orbit position.
    System.getProperty("cornell.orbit")
        ?.split(',')
        ?.mapNotNull { it.toFloatOrNull() }
        ?.takeIf { it.size == 3 }
        ?.let { (azimuth, elevation, radius) ->
            camera.azimuth = azimuth
            camera.elevation = elevation
            camera.radius = radius
        }
    withDawnWindow(
        windowOptions = DawnWindowOptions(title = "hello-dawn4k — Cornell box", width = 960, height = 720),
        sessionOptions = DawnSessionOptions(startupTimeout = 60.seconds),
    ) { gpu, window, presentation ->
        val controls = OrbitControls(window, camera)
        val watcher = launch { controls.collect() }
        try {
            renderCornellFrames(gpu, presentation, frameLimit, controls)
        } finally {
            watcher.cancelAndJoin()
        }
    }
}

/** Folded window input into one collector; cameraDirty signals an accumulation restart. */
private class OrbitControls(private val window: DawnWindow, val camera: OrbitCamera = OrbitCamera()) {
    @Volatile var closeRequested = false
    @Volatile var cameraDirty = true
    private var dragging = false
    private var lastPointer: Pair<Double, Double>? = null

    suspend fun collect() {
        window.events.collect { event ->
            when (event) {
                is DawnWindowEvent.PointerButton -> if (event.button == GLFW_BUTTON_LEFT) {
                    dragging = event.action != GLFW_RELEASE
                    if (!dragging) lastPointer = null
                }
                is DawnWindowEvent.PointerMoved -> {
                    val previous = lastPointer
                    if (dragging && previous != null) {
                        camera.drag(event.x - previous.first, event.y - previous.second)
                        cameraDirty = true
                    }
                    lastPointer = event.x to event.y
                }
                is DawnWindowEvent.Scroll -> {
                    camera.zoom(event.y)
                    cameraDirty = true
                }
                is DawnWindowEvent.Key -> when (event.key) {
                    GLFW_KEY_R -> {
                        camera.reset()
                        cameraDirty = true
                    }
                    GLFW_KEY_ESCAPE -> window.requestClose()
                }
                DawnWindowEvent.CloseRequested -> closeRequested = true
                else -> {}
            }
        }
    }
}

/** Invoke on process main; the GLFW loop stays there while encoding uses the GPU owner. */
private suspend fun renderCornellFrames(
    gpu: DawnSession,
    presentation: DawnPresentation,
    frameLimit: Int,
    controls: OrbitControls,
) {
    require(frameLimit >= 0)
    val renderer = CornellWindowRenderer(gpu, checkNotNull(presentation.configuration.value).format)
    val hudText = if (System.getProperty("cornell.hud") != "false") HudText.open() else null
    val hudRenderer = hudText?.let { HudRenderer(gpu, it) }
    val adapterName = gpu.adapter.info.description
    var presented = 0u
    var accumulated = 0u
    var hudVertices = FloatArray(0)
    var emaFrameMs = 16.0
    var lastHudAt = System.nanoTime()
    try {
        while (currentCoroutineContext().isActive &&
            !controls.closeRequested &&
            (frameLimit == 0 || presented < frameLimit.toUInt())
        ) {
            if (controls.cameraDirty) {
                controls.cameraDirty = false
                renderer.updateCamera(controls.camera.basis())
                accumulated = 0u
            }
            val frameStartedAt = System.nanoTime()
            if (presentation.withFrame { frame ->
                    renderer.renderFrame(frame, accumulated + 1u, hudRenderer, hudVertices)
                    frame.present()
                }) {
                presented++
                accumulated++
            }
            val frameMs = (System.nanoTime() - frameStartedAt) / 1_000_000.0
            emaFrameMs = emaFrameMs * 0.9 + frameMs * 0.1
            if (hudRenderer != null && System.nanoTime() - lastHudAt > 500_000_000L) {
                lastHudAt = System.nanoTime()
                val samples = ((accumulated + 1u) * WINDOW_SAMPLES_PER_FRAME.toUInt()).toInt()
                val lines = listOf(
                    hudText!!.layoutLine("${(1000.0 / emaFrameMs).toInt()} fps · $samples spp · ${WIDTH}x$HEIGHT"),
                    hudText.layoutLine(
                        "az %.0f°  el %.0f°  zoom %.1f".format(
                            Math.toDegrees(controls.camera.azimuth.toDouble()),
                            Math.toDegrees(controls.camera.elevation.toDouble()),
                            controls.camera.radius,
                        ),
                    ),
                    hudText.layoutLine(adapterName),
                )
                hudVertices = hudRenderer.buildVertices(lines, baseX = 12f, baseY = 30f, lineHeight = 26f)
            }
            delay(16)
        }
        println("hello-dawn4k frames=$presented")
        if (frameLimit > 0) gpu.run { renderer.dumpPresentedFrame("cornell-window.png", hudRenderer, hudVertices) }
    } finally {
        withContext(NonCancellable) {
            try {
                gpu.queue.onSubmittedWorkDone().getOrThrow()
            } finally {
                renderer.close()
                hudRenderer?.close()
                hudText?.close()
            }
        }
    }
}

/** Owns every GPU resource used by the windowed render loop; close before the session ends. */
private class CornellWindowRenderer(private val session: DawnSession, private val format: GPUTextureFormat) : AutoCloseable {
    private var closed = false
    private val scene = CornellBox.scene()
    private val quadBytes = (scene.quadData.size * 4).toULong()
    private val radianceBytes = (WIDTH * HEIGHT * 16).toULong()

    private val quads = session.device.createBuffer(
        BufferDescriptor(quadBytes, GPUBufferUsage.Storage or GPUBufferUsage.CopyDst),
    )
    private val params = session.device.createBuffer(
        BufferDescriptor(48uL, GPUBufferUsage.Uniform or GPUBufferUsage.CopyDst),
    )
    private val radiance = session.device.createBuffer(
        BufferDescriptor(radianceBytes, GPUBufferUsage.Storage),
    )
    private val dims = session.device.createBuffer(
        BufferDescriptor(16uL, GPUBufferUsage.Uniform or GPUBufferUsage.CopyDst),
    )
    private val cameraBuffer = session.device.createBuffer(
        BufferDescriptor(64uL, GPUBufferUsage.Uniform or GPUBufferUsage.CopyDst),
    )

    private val traceShader = session.device.createShaderModule(ShaderModuleDescriptor(resource("/cornell.wgsl")))
    private val presentShader = session.device.createShaderModule(ShaderModuleDescriptor(resource("/present.wgsl")))
    private val tracePipeline = session.device.createComputePipeline(
        ComputePipelineDescriptor(ProgrammableStage(traceShader, "main")),
    )
    private val presentPipeline = session.device.createRenderPipeline(
        RenderPipelineDescriptor(
            vertex = VertexState(module = presentShader, entryPoint = "vs"),
            fragment = FragmentState(
                module = presentShader,
                entryPoint = "fs",
                targets = listOf(ColorTargetState(format)),
            ),
        ),
    )
    private val traceLayout = tracePipeline.getBindGroupLayout(0u)
    private val traceBinding = session.device.createBindGroup(
        BindGroupDescriptor(
            traceLayout,
            listOf(BindGroupEntry(0u, quads), BindGroupEntry(1u, radiance), BindGroupEntry(2u, params), BindGroupEntry(3u, cameraBuffer)),
        ),
    )
    private val presentLayout = presentPipeline.getBindGroupLayout(0u)
    private val presentBinding = session.device.createBindGroup(
        BindGroupDescriptor(presentLayout, listOf(BindGroupEntry(0u, radiance), BindGroupEntry(1u, dims))),
    )

    init {
        session.queue.writeBuffer(quads, 0uL, ArrayBuffer.of(scene.quadData))
        session.queue.writeBuffer(dims, 0uL, ArrayBuffer.of(uintArrayOf(WIDTH.toUInt(), HEIGHT.toUInt(), 0u, 0u)))
    }

    /** Writes the camera basis uniform; called whenever the orbit controls move. */
    fun updateCamera(basis: CameraBasis) {
        session.queue.writeBuffer(cameraBuffer, 0uL, ArrayBuffer.of(basis.toUniform()))
    }

    /** One accumulation frame plus one presentation pass (then the HUD), in a single command buffer. */
    fun renderFrame(frame: DawnFrame, frameIndex: UInt, hud: HudRenderer?, hudVertices: FloatArray) {
        session.queue.writeBuffer(params, 0uL, ArrayBuffer.of(uintArrayOf(
            scene.quadCount.toUInt(),
            WIDTH.toUInt(),
            HEIGHT.toUInt(),
            WINDOW_SAMPLES_PER_FRAME.toUInt(),
            scene.lightIndex.toUInt(), 0u, 0u, 0u,
            frameIndex, 0u, 0u, 0u,
        )))
        (session.device.createCommandEncoder() as DawnCommandEncoder).use { encoder ->
            encoder.beginComputePass().use { pass ->
                try {
                    pass.setPipeline(tracePipeline)
                    pass.setBindGroup(0u, traceBinding)
                    pass.dispatchWorkgroups(
                        ((WIDTH + WORKGROUP_SIZE - 1) / WORKGROUP_SIZE).toUInt(),
                        ((HEIGHT + WORKGROUP_SIZE - 1) / WORKGROUP_SIZE).toUInt(),
                    )
                } finally {
                    pass.end()
                }
            }
            encoder.beginRenderPass(RenderPassDescriptor(colorAttachments = listOf(
                RenderPassColorAttachment(
                    frame.view,
                    loadOp = GPULoadOp.Clear,
                    storeOp = GPUStoreOp.Store,
                    clearValue = Color(0.0, 0.0, 0.0, 1.0),
                ),
            ))).use { pass ->
                try {
                    pass.setPipeline(presentPipeline)
                    pass.setBindGroup(0u, presentBinding)
                    pass.draw(3u)
                } finally {
                    pass.end()
                }
            }
            if (hud != null && hudVertices.isNotEmpty()) {
                encoder.beginRenderPass(RenderPassDescriptor(colorAttachments = listOf(
                    RenderPassColorAttachment(
                        frame.view,
                        loadOp = GPULoadOp.Load,
                        storeOp = GPUStoreOp.Store,
                        clearValue = Color(0.0, 0.0, 0.0, 1.0),
                    ),
                ))).use { pass ->
                    try {
                        hud.draw(pass, format, frame.extent.width, frame.extent.height, hudVertices)
                    } finally {
                        pass.end()
                    }
                }
            }
            encoder.finish().use { session.queue.submit(listOf(it)) }
        }
    }

    /**
     * Smoke-test helper: runs the presentation pass into an offscreen RGBA8Unorm texture
     * (the swapchain pipeline format cannot write there, so a twin pipeline is built) and
     * writes exactly what the window shows to [path], row padding already absent at 640 px.
     */
    suspend fun dumpPresentedFrame(path: String, hud: HudRenderer?, hudVertices: FloatArray) {
        session.createRenderTexture(WIDTH.toUInt(), HEIGHT.toUInt(), GPUTextureFormat.RGBA8Unorm).use { target ->
            session.device.createRenderPipeline(
                RenderPipelineDescriptor(
                    vertex = VertexState(module = presentShader, entryPoint = "vs"),
                    fragment = FragmentState(
                        module = presentShader,
                        entryPoint = "fs",
                        targets = listOf(ColorTargetState(GPUTextureFormat.RGBA8Unorm)),
                    ),
                ),
            ).use { dumpPipeline ->
                // The dump pipeline's default layout differs from the swapchain pipeline's;
                // bind groups must come from the layout of the pipeline they are used with.
                dumpPipeline.getBindGroupLayout(0u).use { dumpLayout ->
                    session.device.createBindGroup(
                        BindGroupDescriptor(dumpLayout, listOf(BindGroupEntry(0u, radiance), BindGroupEntry(1u, dims))),
                    ).use { dumpBinding ->
                        target.createView().use { view ->
                            session.device.createBuffer(
                                BufferDescriptor(2560uL * HEIGHT.toULong(), GPUBufferUsage.CopyDst or GPUBufferUsage.MapRead),
                            ).use { staging ->
                                (session.device.createCommandEncoder() as DawnCommandEncoder).use { encoder ->
                                    encoder.beginRenderPass(RenderPassDescriptor(colorAttachments = listOf(
                                        RenderPassColorAttachment(
                                            view,
                                            loadOp = GPULoadOp.Clear,
                                            storeOp = GPUStoreOp.Store,
                                            clearValue = Color(0.0, 0.0, 0.0, 1.0),
                                        ),
                                    ))).use { pass ->
                                        try {
                                            pass.setPipeline(dumpPipeline)
                                            pass.setBindGroup(0u, dumpBinding)
                                            pass.draw(3u)
                                        } finally {
                                            pass.end()
                                        }
                                    }
                                    if (hud != null && hudVertices.isNotEmpty()) {
                                        encoder.beginRenderPass(RenderPassDescriptor(colorAttachments = listOf(
                                            RenderPassColorAttachment(
                                                view,
                                                loadOp = GPULoadOp.Load,
                                                storeOp = GPUStoreOp.Store,
                                                clearValue = Color(0.0, 0.0, 0.0, 1.0),
                                            ),
                                        ))).use { pass ->
                                            try {
                                                hud.draw(pass, GPUTextureFormat.RGBA8Unorm, WIDTH, HEIGHT, hudVertices)
                                            } finally {
                                                pass.end()
                                            }
                                        }
                                    }
                                    encoder.copyTextureToBuffer(
                                        TexelCopyTextureInfo(target),
                                        TexelCopyBufferInfo(staging, bytesPerRow = 2560u, rowsPerImage = HEIGHT.toUInt()),
                                        Extent3D(WIDTH.toUInt(), HEIGHT.toUInt()),
                                    )
                                    encoder.finish().use { session.queue.submit(listOf(it)) }
                                }
                                staging.mapAsync(GPUMapMode.Read).getOrThrow()
                                val rgba = try {
                                    staging.getMappedRange().toByteArray()
                                } finally {
                                    staging.unmap()
                                }
                                Files.write(Path.of(path), Png.encode(WIDTH, HEIGHT, rgba))
                            }
                        }
                    }
                }
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        traceBinding.close()
        presentBinding.close()
        traceLayout.close()
        presentLayout.close()
        presentPipeline.close()
        tracePipeline.close()
        presentShader.close()
        traceShader.close()
        cameraBuffer.close()
        dims.close()
        radiance.close()
        params.close()
        quads.close()
    }

    private fun resource(name: String): String =
        requireNotNull(javaClass.getResourceAsStream(name)) { "missing resource $name" }
            .readBytes().decodeToString()
}
