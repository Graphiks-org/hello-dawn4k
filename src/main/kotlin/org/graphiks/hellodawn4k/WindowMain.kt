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
import org.graphiks.webgpu.GPUCompareFunction
import org.graphiks.webgpu.GPUFilterMode
import org.graphiks.webgpu.GPUIndexFormat
import org.graphiks.webgpu.GPUMapMode
import org.graphiks.webgpu.GPULoadOp
import org.graphiks.webgpu.GPUStoreOp
import org.graphiks.webgpu.GPUTexture
import org.graphiks.webgpu.GPUTextureFormat
import org.graphiks.webgpu.GPUTextureUsage
import org.graphiks.webgpu.GPUTextureView
import org.graphiks.webgpu.GPUVertexFormat
import org.graphiks.webgpu.descriptors.BindGroupDescriptor
import org.graphiks.webgpu.descriptors.BindGroupEntry
import org.graphiks.webgpu.descriptors.BufferDescriptor
import org.graphiks.webgpu.descriptors.Color
import org.graphiks.webgpu.descriptors.ColorTargetState
import org.graphiks.webgpu.descriptors.ComputePipelineDescriptor
import org.graphiks.webgpu.descriptors.DepthStencilState
import org.graphiks.webgpu.descriptors.Extent3D
import org.graphiks.webgpu.descriptors.FragmentState
import org.graphiks.webgpu.descriptors.PrimitiveState
import org.graphiks.webgpu.descriptors.ProgrammableStage
import org.graphiks.webgpu.descriptors.RenderPassColorAttachment
import org.graphiks.webgpu.descriptors.RenderPassDepthStencilAttachment
import org.graphiks.webgpu.descriptors.RenderPassDescriptor
import org.graphiks.webgpu.descriptors.RenderPipelineDescriptor
import org.graphiks.webgpu.descriptors.SamplerDescriptor
import org.graphiks.webgpu.descriptors.ShaderModuleDescriptor
import org.graphiks.webgpu.descriptors.TexelCopyBufferInfo
import org.graphiks.webgpu.descriptors.TexelCopyTextureInfo
import org.graphiks.webgpu.descriptors.TextureDescriptor
import org.graphiks.webgpu.descriptors.VertexAttribute
import org.graphiks.webgpu.descriptors.VertexBufferLayout
import org.graphiks.webgpu.descriptors.VertexState
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

/** Samples traced per presented frame; the on-screen image converges as frames accumulate. */
const val WINDOW_SAMPLES_PER_FRAME = 8

// GLFW constants forwarded as-is by the toolkit's window events.
private const val GLFW_RELEASE = 0
private const val GLFW_PRESS = 1
private const val GLFW_BUTTON_LEFT = 0
private const val GLFW_KEY_R = 82
private const val GLFW_KEY_M = 77
private const val GLFW_KEY_ESCAPE = 256

/** Rendering modes; M cycles through them. The two lightmap views follow the webgpu-samples. */
enum class RenderMode { PATH_TRACING, RASTERIZER, RAYTRACER }

/**
 * Windowed Cornell box with three renderers, mirroring the webgpu-samples architecture:
 * a radiosity pass converges a per-quad photon lightmap in surface space every frame,
 * and the view is either our progressive path tracer, the sample's rasterizer (MVP over
 * an indexed quad mesh) or its ray tracer (one primary ray per pixel). Drag orbits the
 * camera, the wheel zooms, R resets and Escape closes.
 */
fun main() = runBlocking {
    val frameLimit = System.getProperty("cornell.frames")?.toIntOrNull() ?: 0
    val initialMode = when (System.getProperty("cornell.mode")) {
        "lightmap", "rasterizer" -> RenderMode.RASTERIZER
        "raytracer", "ray" -> RenderMode.RAYTRACER
        else -> RenderMode.PATH_TRACING
    }
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
            renderCornellFrames(gpu, presentation, frameLimit, controls, initialMode)
        } finally {
            watcher.cancelAndJoin()
        }
    }
}

/** Folded window input into one collector; cameraDirty signals an accumulation restart. */
private class OrbitControls(private val window: DawnWindow, val camera: OrbitCamera) {
    @Volatile var closeRequested = false
    @Volatile var cameraDirty = true
    @Volatile var toggleMode = false
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
                is DawnWindowEvent.Key -> if (event.action == GLFW_PRESS) when (event.key) {
                    GLFW_KEY_R -> {
                        camera.reset()
                        cameraDirty = true
                    }
                    GLFW_KEY_M -> toggleMode = true
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
    initialMode: RenderMode = RenderMode.PATH_TRACING,
) {
    require(frameLimit >= 0)
    val renderer = CornellWindowRenderer(gpu, checkNotNull(presentation.configuration.value).format)
    val hudText = if (System.getProperty("cornell.hud") != "false") HudText.open() else null
    val hudRenderer = hudText?.let { HudRenderer(gpu, it) }
    val adapterName = gpu.adapter.info.description
    var presented = 0u
    var accumulated = 0u
    var mode = initialMode
    var hudVertices = FloatArray(0)
    var emaFrameMs = 16.0
    var lastHudAt = System.nanoTime()

    fun hudLines(samples: Int): List<String> = listOf(
        when (mode) {
            RenderMode.PATH_TRACING ->
                "${(1000.0 / emaFrameMs).toInt()} fps · $samples spp · ${WIDTH}x$HEIGHT"
            RenderMode.RASTERIZER ->
                "${(1000.0 / emaFrameMs).toInt()} fps · ${(renderer.photonsEmitted / 1_000_000.0).toInt()}M photons · rasterizer"
            RenderMode.RAYTRACER ->
                "${(1000.0 / emaFrameMs).toInt()} fps · ${(renderer.photonsEmitted / 1_000_000.0).toInt()}M photons · raytracer"
        },
        "az %.0f°  el %.0f°  zoom %.1f".format(
            Math.toDegrees(controls.camera.azimuth.toDouble()),
            Math.toDegrees(controls.camera.elevation.toDouble()),
            controls.camera.radius,
        ),
        adapterName,
    )

    try {
        while (currentCoroutineContext().isActive &&
            !controls.closeRequested &&
            (frameLimit == 0 || presented < frameLimit.toUInt())
        ) {
            if (controls.toggleMode) {
                controls.toggleMode = false
                mode = when (mode) {
                    RenderMode.PATH_TRACING -> RenderMode.RASTERIZER
                    RenderMode.RASTERIZER -> RenderMode.RAYTRACER
                    RenderMode.RAYTRACER -> RenderMode.PATH_TRACING
                }
                if (mode == RenderMode.PATH_TRACING) accumulated = 0u
                lastHudAt = 0L // force a HUD refresh with the new mode
            }
            if (controls.cameraDirty) {
                controls.cameraDirty = false
                renderer.updateCamera(controls.camera.basis())
                // Restart the mean from scratch: blending HDR history across viewpoints
                // smears the light source into psychedelic trails through the tonemap.
                accumulated = 0u
            }
            val frameStartedAt = System.nanoTime()
            if (presentation.withFrame { frame ->
                    renderer.renderFrame(frame, accumulated + 1u, mode, controls.camera, hudRenderer, hudVertices)
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
                hudVertices = hudRenderer.buildVertices(
                    hudLines(samples).map { hudText!!.layoutLine(it) },
                    baseX = 12f,
                    baseY = 30f,
                    lineHeight = 26f,
                )
            }
            delay(16)
        }
        println("hello-dawn4k frames=$presented")
        if (frameLimit > 0) {
            // Refresh the HUD so the dump reports the final state, not the last 2 Hz update.
            if (hudRenderer != null) {
                val samples = ((accumulated + 1u) * WINDOW_SAMPLES_PER_FRAME.toUInt()).toInt()
                hudVertices = hudRenderer.buildVertices(
                    hudLines(samples).map { hudText!!.layoutLine(it) },
                    baseX = 12f,
                    baseY = 30f,
                    lineHeight = 26f,
                )
            }
            gpu.run { renderer.dumpPresentedFrame("cornell-window.png", mode, controls.camera, hudRenderer, hudVertices) }
        }
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

/**
 * Owns every GPU resource of the three renderers, mirroring the webgpu-samples classes:
 * shared scene buffers (Common), the photon radiosity lightmap (Radiosity), the raster
 * pipeline over an indexed quad mesh (Rasterizer), the ray-traced lightmap view
 * (Raytracer) and the tonemapping present pass (Tonemapper). Close before the session ends.
 */
private class CornellWindowRenderer(private val session: DawnSession, private val format: GPUTextureFormat) : AutoCloseable {
    private var closed = false
    private val scene = CornellBox.scene()
    private val quadBytes = (scene.quadData.size * 4).toULong()
    private val radianceBytes = (WIDTH * HEIGHT * 16).toULong()

    // --- Shared scene buffers (the sample's Common) ---
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

    init {
        session.queue.writeBuffer(quads, 0uL, ArrayBuffer.of(scene.quadData))
        session.queue.writeBuffer(dims, 0uL, ArrayBuffer.of(uintArrayOf(WIDTH.toUInt(), HEIGHT.toUInt(), 0u, 0u)))
    }

    /** The sample's concatenation pattern: every pass shader is common.wgsl + its own file. */
    private fun shader(name: String): String =
        resource("/common.wgsl") + "\n" + resource(name)

    // --- Path tracer (ours) ---
    private val traceShader = session.device.createShaderModule(ShaderModuleDescriptor(shader("/cornell.wgsl")))
    private val tracePipeline = session.device.createComputePipeline(
        ComputePipelineDescriptor(ProgrammableStage(traceShader, "main")),
    )
    private val traceLayout = tracePipeline.getBindGroupLayout(0u)
    private val traceBinding = session.device.createBindGroup(
        BindGroupDescriptor(
            traceLayout,
            listOf(BindGroupEntry(0u, quads), BindGroupEntry(1u, radiance), BindGroupEntry(2u, params), BindGroupEntry(3u, cameraBuffer)),
        ),
    )

    // --- Radiosity (the sample's Radiosity): photons -> atomic u32 -> 256x256 lightmap ---
    private val lightmapSize = 256u
    private val photonTexels = (lightmapSize * lightmapSize * scene.quadCount.toUInt()).toDouble()
    private val photonAccumulation = session.device.createBuffer(
        BufferDescriptor(
            3uL * 4uL * lightmapSize * lightmapSize * scene.quadCount.toULong(),
            GPUBufferUsage.Storage,
        ),
    )
    private val lightmapTexture = session.device.createTexture(
        TextureDescriptor(
            size = Extent3D(lightmapSize, lightmapSize, scene.quadCount.toUInt()),
            format = GPUTextureFormat.RGBA16Float,
            usage = GPUTextureUsage.StorageBinding or GPUTextureUsage.TextureBinding,
        ),
    )
    private val lightmapView = lightmapTexture.createView()
    private val lightmapSampler = session.device.createSampler(SamplerDescriptor(
        magFilter = GPUFilterMode.Linear,
        minFilter = GPUFilterMode.Linear,
    ))
    private val photonParams = session.device.createBuffer(
        BufferDescriptor(32uL, GPUBufferUsage.Uniform or GPUBufferUsage.CopyDst),
    )
    private val radiosityShader = session.device.createShaderModule(ShaderModuleDescriptor(shader("/radiosity.wgsl")))
    private val radiosityPipeline = session.device.createComputePipeline(
        ComputePipelineDescriptor(ProgrammableStage(radiosityShader, "radiosity")),
    )
    private val publishPipeline = session.device.createComputePipeline(
        ComputePipelineDescriptor(ProgrammableStage(radiosityShader, "publishLightmap")),
    )
    private val radiosityLayout = radiosityPipeline.getBindGroupLayout(0u)
    private val radiosityBinding = session.device.createBindGroup(
        BindGroupDescriptor(
            radiosityLayout,
            listOf(
                BindGroupEntry(0u, quads),
                BindGroupEntry(2u, params),
                BindGroupEntry(4u, photonAccumulation),
                BindGroupEntry(5u, lightmapView),
                BindGroupEntry(6u, photonParams),
            ),
        ),
    )
    private val publishLayout = publishPipeline.getBindGroupLayout(0u)
    private val publishBinding = session.device.createBindGroup(
        BindGroupDescriptor(
            publishLayout,
            listOf(
                BindGroupEntry(4u, photonAccumulation),
                BindGroupEntry(5u, lightmapView),
                BindGroupEntry(6u, photonParams),
            ),
        ),
    )
    private var accumulationMean = 0.0
    var photonsEmitted = 0.0
        private set

    // --- Ray-traced lightmap view (the sample's Raytracer) ---
    private val raytracerShader = session.device.createShaderModule(ShaderModuleDescriptor(shader("/raytracer.wgsl")))
    private val raytracerPipeline = session.device.createComputePipeline(
        ComputePipelineDescriptor(ProgrammableStage(raytracerShader, "main")),
    )
    private val raytracerLayout = raytracerPipeline.getBindGroupLayout(0u)
    private val raytracerBinding = session.device.createBindGroup(
        BindGroupDescriptor(
            raytracerLayout,
            listOf(
                BindGroupEntry(0u, quads),
                BindGroupEntry(1u, radiance),
                BindGroupEntry(2u, params),
                BindGroupEntry(3u, cameraBuffer),
                BindGroupEntry(4u, lightmapView),
                BindGroupEntry(5u, lightmapSampler),
            ),
        ),
    )

    // --- Rasterized lightmap view (the sample's Rasterizer, their default renderer) ---
    private val rasterUniforms = session.device.createBuffer(
        BufferDescriptor(64uL, GPUBufferUsage.Uniform or GPUBufferUsage.CopyDst),
    )
    private val mesh = buildMesh()
    private val meshVertices: FloatArray get() = mesh.first
    private val meshIndices: UShortArray get() = mesh.second
    private val vertexBuffer = session.device.createBuffer(
        BufferDescriptor((meshVertices.size * 4).toULong(), GPUBufferUsage.Vertex or GPUBufferUsage.CopyDst),
    )
    private val indexBuffer = session.device.createBuffer(
        BufferDescriptor((meshIndices.size * 2).toULong(), GPUBufferUsage.Index or GPUBufferUsage.CopyDst),
    )
    private val rasterShader = session.device.createShaderModule(
        ShaderModuleDescriptor(resource("/rasterizer.wgsl")),
    )
    private val rasterPipeline = session.device.createRenderPipeline(
        RenderPipelineDescriptor(
            vertex = VertexState(
                module = rasterShader,
                entryPoint = "vs_main",
                buffers = listOf(
                    VertexBufferLayout(
                        arrayStride = 40uL,
                        attributes = listOf(
                            VertexAttribute(GPUVertexFormat.Float32x4, 0uL, 0u),
                            VertexAttribute(GPUVertexFormat.Float32x3, 16uL, 1u),
                            VertexAttribute(GPUVertexFormat.Float32x3, 28uL, 2u),
                        ),
                    ),
                ),
            ),
            fragment = FragmentState(
                module = rasterShader,
                entryPoint = "fs_main",
                targets = listOf(ColorTargetState(format)),
            ),
            primitive = PrimitiveState(),
            depthStencil = DepthStencilState(
                format = GPUTextureFormat.Depth24Plus,
                depthWriteEnabled = true,
                depthCompare = GPUCompareFunction.Less,
            ),
        ),
    )
    private val rasterLayout = rasterPipeline.getBindGroupLayout(0u)
    private val rasterBinding = session.device.createBindGroup(
        BindGroupDescriptor(
            rasterLayout,
            listOf(BindGroupEntry(0u, rasterUniforms), BindGroupEntry(1u, lightmapView), BindGroupEntry(2u, lightmapSampler)),
        ),
    )
    private var depthTexture: GPUTexture? = null
    private var depthView: GPUTextureView? = null
    private var depthWidth = 0
    private var depthHeight = 0

    init {
        session.queue.writeBuffer(vertexBuffer, 0uL, ArrayBuffer.of(meshVertices))
        session.queue.writeBuffer(indexBuffer, 0uL, ArrayBuffer.of(meshIndices))
    }

    /** Writes the camera basis uniform; called whenever the orbit controls move. */
    fun updateCamera(basis: CameraBasis) {
        session.queue.writeBuffer(cameraBuffer, 0uL, ArrayBuffer.of(basis.toUniform()))
    }

    /** One radiosity step plus the selected view pass (plus the HUD), in a single command buffer. */
    fun renderFrame(
        frame: DawnFrame,
        frameIndex: UInt,
        mode: RenderMode,
        orbit: OrbitCamera,
        hud: HudRenderer?,
        hudVertices: FloatArray,
    ) {        session.queue.writeBuffer(params, 0uL, ArrayBuffer.of(uintArrayOf(
            scene.quadCount.toUInt(),
            WIDTH.toUInt(),
            HEIGHT.toUInt(),
            if (mode == RenderMode.PATH_TRACING) WINDOW_SAMPLES_PER_FRAME.toUInt() else 1u,
            scene.lightIndex.toUInt(), 0u, 0u, 0u,
            frameIndex, 0u, 0u, 0u,
        )))
        (session.device.createCommandEncoder() as DawnCommandEncoder).use { encoder ->
            encoder.beginComputePass().use { pass ->
                try {
                    runRadiosity(pass)
                    when (mode) {
                        RenderMode.PATH_TRACING -> {
                            pass.setPipeline(tracePipeline)
                            pass.setBindGroup(0u, traceBinding)
                            pass.dispatchWorkgroups(
                                ((WIDTH + WORKGROUP_SIZE - 1) / WORKGROUP_SIZE).toUInt(),
                                ((HEIGHT + WORKGROUP_SIZE - 1) / WORKGROUP_SIZE).toUInt(),
                            )
                        }
                        RenderMode.RASTERIZER -> {} // rendered by the graphics pass below
                        RenderMode.RAYTRACER -> {
                            pass.setPipeline(raytracerPipeline)
                            pass.setBindGroup(0u, raytracerBinding)
                            pass.dispatchWorkgroups(
                                ((WIDTH + WORKGROUP_SIZE - 1) / WORKGROUP_SIZE).toUInt(),
                                ((HEIGHT + WORKGROUP_SIZE - 1) / WORKGROUP_SIZE).toUInt(),
                            )
                        }
                    }
                } finally {
                    pass.end()
                }
            }
            if (mode == RenderMode.RASTERIZER) {
                rasterize(encoder, frame, orbit)
            } else {
                present(encoder, frame)
            }
            drawHud(encoder, frame, hud, hudVertices)
            encoder.finish().use { session.queue.submit(listOf(it)) }
        }
    }

    /**
     * Photon-mapping radiosity, run every frame regardless of the render mode:
     * 1024 workgroups x 256 photons bounce from the light into the atomic accumulation
     * buffer, then the publish pass writes the progressive mean to the lightmap.
     * Seeds are fresh CPU randoms each frame (the sample's route) so successive frames
     * never retrace the same photons. Mirrors the webgpu-samples overflow rescaling.
     */
    private fun runRadiosity(pass: org.graphiks.webgpu.GPUComputePassEncoder) {
        val photonsPerFrame = 256u * 1024u
        accumulationMean += (photonsPerFrame.toDouble() * 100000.0) / photonTexels
        val toLightmapScale = 1.0 / accumulationMean
        val bufferScale = if (accumulationMean > 2 * 0x10000000) 0.5 else 1.0
        accumulationMean *= bufferScale
        photonsEmitted += photonsPerFrame.toDouble()
        val seeds = kotlin.random.Random.Default
        session.queue.writeBuffer(photonParams, 0uL, ArrayBuffer.of(floatArrayOf(
            toLightmapScale.toFloat(),
            bufferScale.toFloat(),
            Float.fromBits(scene.quadCount),
            Float.fromBits(scene.lightIndex),
            Float.fromBits(seeds.nextInt()),
            Float.fromBits(seeds.nextInt()),
            Float.fromBits(seeds.nextInt()),
            0f,
        )))
        pass.setPipeline(radiosityPipeline)
        pass.setBindGroup(0u, radiosityBinding)
        pass.dispatchWorkgroups(1024u)
        pass.setPipeline(publishPipeline)
        pass.setBindGroup(0u, publishBinding)
        pass.dispatchWorkgroups(lightmapSize / 8u, lightmapSize / 8u, scene.quadCount.toUInt())
    }

    /** The sample's rasterizer: MVP over the indexed quad mesh, tonemapped inline. */
    private fun rasterize(encoder: DawnCommandEncoder, frame: DawnFrame, orbit: OrbitCamera) {
        val extentWidth = frame.extent.width
        val extentHeight = frame.extent.height
        val basis = orbit.basis()
        // The sample's 45-degree fov is NOT ours: the orbit camera frames the box at
        // 2*atan(0.30); using it here made M look like it moved the camera.
        val fovY = 2f * kotlin.math.atan(orbit.tanHalfFov)
        val mvp = Mat4.multiply(
            Mat4.perspective(fovY, extentWidth.toFloat() / extentHeight, 0.5f, 100f),
            Mat4.lookAt(basis.position, orbit.target, V3(0f, 1f, 0f)),
        )
        session.queue.writeBuffer(rasterUniforms, 0uL, ArrayBuffer.of(mvp))
        if (depthWidth != extentWidth || depthHeight != extentHeight) {
            depthView?.close()
            depthTexture?.close()
            depthTexture = session.device.createTexture(
                TextureDescriptor(
                    size = Extent3D(extentWidth.toUInt(), extentHeight.toUInt()),
                    format = GPUTextureFormat.Depth24Plus,
                    usage = GPUTextureUsage.RenderAttachment,
                ),
            )
            depthView = depthTexture!!.createView()
            depthWidth = extentWidth
            depthHeight = extentHeight
        }
        encoder.beginRenderPass(RenderPassDescriptor(
            colorAttachments = listOf(
                RenderPassColorAttachment(
                    frame.view,
                    loadOp = GPULoadOp.Clear,
                    storeOp = GPUStoreOp.Store,
                    clearValue = Color(0.0, 0.0, 0.0, 1.0),
                ),
            ),
            depthStencilAttachment = RenderPassDepthStencilAttachment(
                view = depthView!!,
                depthClearValue = 1.0f,
                depthLoadOp = GPULoadOp.Clear,
                depthStoreOp = GPUStoreOp.Store,
            ),
        )).use { pass ->
            try {
                pass.setPipeline(rasterPipeline)
                pass.setBindGroup(0u, rasterBinding)
                pass.setVertexBuffer(0u, vertexBuffer)
                pass.setIndexBuffer(indexBuffer, GPUIndexFormat.Uint16)
                pass.drawIndexed(meshIndices.size.toUInt())
            } finally {
                pass.end()
            }
        }
    }

    /** The sample's tonemapper folded into a fullscreen pass over the radiance buffer. */
    private fun present(encoder: DawnCommandEncoder, frame: DawnFrame) {
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
    }

    private val presentShader = session.device.createShaderModule(
        ShaderModuleDescriptor(resource("/present.wgsl")),
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
    private val presentLayout = presentPipeline.getBindGroupLayout(0u)
    private val presentBinding = session.device.createBindGroup(
        BindGroupDescriptor(presentLayout, listOf(BindGroupEntry(0u, radiance), BindGroupEntry(1u, dims))),
    )

    private fun drawHud(encoder: DawnCommandEncoder, frame: DawnFrame, hud: HudRenderer?, hudVertices: FloatArray) {
        if (hud == null || hudVertices.isEmpty()) return
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

    /** Triangulates the scene quads: 4 vertices (position, uv+layer, emissive) + 6 indices each. */
    private fun buildMesh(): Pair<FloatArray, UShortArray> {
        val vertices = FloatArray(scene.quadCount * 4 * 10)
        val indices = UShortArray(scene.quadCount * 6)
        val data = scene.quadData
        for (quad in 0 until scene.quadCount) {
            val o = quad * Scene.FLOATS_PER_QUAD
            val origin = floatArrayOf(data[o], data[o + 1], data[o + 2])
            val edge1 = floatArrayOf(data[o + 4], data[o + 5], data[o + 6])
            val edge2 = floatArrayOf(data[o + 8], data[o + 9], data[o + 10])
            val emissive = floatArrayOf(data[o + 16], data[o + 17], data[o + 18])
            val corners = arrayOf(
                origin,
                floatArrayOf(origin[0] + edge1[0], origin[1] + edge1[1], origin[2] + edge1[2]),
                floatArrayOf(origin[0] + edge1[0] + edge2[0], origin[1] + edge1[1] + edge2[1], origin[2] + edge1[2] + edge2[2]),
                floatArrayOf(origin[0] + edge2[0], origin[1] + edge2[1], origin[2] + edge2[2]),
            )
            val uvs = arrayOf(floatArrayOf(0f, 0f), floatArrayOf(1f, 0f), floatArrayOf(1f, 1f), floatArrayOf(0f, 1f))
            for (corner in 0 until 4) {
                val v = (quad * 4 + corner) * 10
                vertices[v] = corners[corner][0]
                vertices[v + 1] = corners[corner][1]
                vertices[v + 2] = corners[corner][2]
                vertices[v + 3] = 1f
                vertices[v + 4] = uvs[corner][0]
                vertices[v + 5] = uvs[corner][1]
                vertices[v + 6] = quad.toFloat()
                vertices[v + 7] = emissive[0]
                vertices[v + 8] = emissive[1]
                vertices[v + 9] = emissive[2]
            }
            val base = quad * 6
            val first = (quad * 4).toUShort()
            indices[base] = first
            indices[base + 1] = (first + 1u).toUShort()
            indices[base + 2] = (first + 2u).toUShort()
            indices[base + 3] = first
            indices[base + 4] = (first + 2u).toUShort()
            indices[base + 5] = (first + 3u).toUShort()
        }
        return vertices to indices
    }

    /**
     * Smoke-test helper: renders the current view into an offscreen RGBA8Unorm texture
     * (swapchain-pipelines cannot write there, so twin pipelines are built) and writes
     * exactly what the window shows to [path], row padding already absent at 640 px.
     * The rasterizer mode draws its mesh there directly; the other modes go through
     * the radiance buffer and the tonemapping present pass.
     */
    suspend fun dumpPresentedFrame(path: String, mode: RenderMode, orbit: OrbitCamera, hud: HudRenderer?, hudVertices: FloatArray) {
        session.createRenderTexture(WIDTH.toUInt(), HEIGHT.toUInt(), GPUTextureFormat.RGBA8Unorm).use { target ->
            session.device.createBuffer(
                BufferDescriptor(2560uL * HEIGHT.toULong(), GPUBufferUsage.CopyDst or GPUBufferUsage.MapRead),
            ).use { staging ->
                if (mode == RenderMode.RASTERIZER) {
                    dumpRaster(target, orbit, hud, hudVertices)
                } else {
                    dumpPresent(target, hud, hudVertices)
                }
                (session.device.createCommandEncoder() as DawnCommandEncoder).use { encoder ->
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

    /** Offscreen twin of the raster pass for dumps (RGBA8 target, dedicated depth texture). */
    private var dumpRasterCache: Triple<org.graphiks.webgpu.GPURenderPipeline, org.graphiks.webgpu.GPUBindGroup, GPUTextureView>? = null

    private suspend fun dumpRaster(target: GPUTexture, orbit: OrbitCamera, hud: HudRenderer?, hudVertices: FloatArray) {
        target.createView().use { view ->
            val (pipeline, binding, depth) = dumpRasterCache ?: run {
                val dumpPipeline = session.device.createRenderPipeline(
                    RenderPipelineDescriptor(
                        vertex = VertexState(
                            module = rasterShader,
                            entryPoint = "vs_main",
                            buffers = listOf(
                                VertexBufferLayout(
                                    arrayStride = 40uL,
                                    attributes = listOf(
                                        VertexAttribute(GPUVertexFormat.Float32x4, 0uL, 0u),
                                        VertexAttribute(GPUVertexFormat.Float32x3, 16uL, 1u),
                                        VertexAttribute(GPUVertexFormat.Float32x3, 28uL, 2u),
                                    ),
                                ),
                            ),
                        ),
                        fragment = FragmentState(
                            module = rasterShader,
                            entryPoint = "fs_main",
                            targets = listOf(ColorTargetState(GPUTextureFormat.RGBA8Unorm)),
                        ),
                        primitive = PrimitiveState(),
                        depthStencil = DepthStencilState(
                            format = GPUTextureFormat.Depth24Plus,
                            depthWriteEnabled = true,
                            depthCompare = GPUCompareFunction.Less,
                        ),
                    ),
                )
                val layout = pipeline0Layout(dumpPipeline)
                val binding0 = session.device.createBindGroup(
                    BindGroupDescriptor(
                        layout,
                        listOf(BindGroupEntry(0u, rasterUniforms), BindGroupEntry(1u, lightmapView), BindGroupEntry(2u, lightmapSampler)),
                    ),
                )
                val depthTexture = session.device.createTexture(
                    TextureDescriptor(
                        size = Extent3D(WIDTH.toUInt(), HEIGHT.toUInt()),
                        format = GPUTextureFormat.Depth24Plus,
                        usage = GPUTextureUsage.RenderAttachment,
                    ),
                )
                Triple(dumpPipeline, binding0, depthTexture.createView()).also {
                    dumpRasterCache = it
                    layout.close()
                }
            }
            val basis = orbit.basis()
            val fovY = 2f * kotlin.math.atan(orbit.tanHalfFov)
            val mvp = Mat4.multiply(
                Mat4.perspective(fovY, WIDTH.toFloat() / HEIGHT, 0.5f, 100f),
                Mat4.lookAt(basis.position, orbit.target, V3(0f, 1f, 0f)),
            )
            session.queue.writeBuffer(rasterUniforms, 0uL, ArrayBuffer.of(mvp))
            (session.device.createCommandEncoder() as DawnCommandEncoder).use { encoder ->
                encoder.beginRenderPass(RenderPassDescriptor(
                    colorAttachments = listOf(
                        RenderPassColorAttachment(
                            view,
                            loadOp = GPULoadOp.Clear,
                            storeOp = GPUStoreOp.Store,
                            clearValue = Color(0.0, 0.0, 0.0, 1.0),
                        ),
                    ),
                    depthStencilAttachment = RenderPassDepthStencilAttachment(
                        view = depth,
                        depthClearValue = 1.0f,
                        depthLoadOp = GPULoadOp.Clear,
                        depthStoreOp = GPUStoreOp.Store,
                    ),
                )).use { pass ->
                    try {
                        pass.setPipeline(pipeline)
                        pass.setBindGroup(0u, binding)
                        pass.setVertexBuffer(0u, vertexBuffer)
                        pass.setIndexBuffer(indexBuffer, GPUIndexFormat.Uint16)
                        pass.drawIndexed(meshIndices.size.toUInt())
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
                encoder.finish().use { session.queue.submit(listOf(it)) }
                session.queue.onSubmittedWorkDone().getOrThrow()
            }
        }
    }

    private fun pipeline0Layout(pipeline: org.graphiks.webgpu.GPURenderPipeline) = pipeline.getBindGroupLayout(0u)

    private suspend fun dumpPresent(target: GPUTexture, hud: HudRenderer?, hudVertices: FloatArray) {
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
            dumpPipeline.getBindGroupLayout(0u).use { dumpLayout ->
                session.device.createBindGroup(
                    BindGroupDescriptor(dumpLayout, listOf(BindGroupEntry(0u, radiance), BindGroupEntry(1u, dims))),
                ).use { dumpBinding ->
                    target.createView().use { view ->
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
                            encoder.finish().use { session.queue.submit(listOf(it)) }
                            session.queue.onSubmittedWorkDone().getOrThrow()
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
        radiosityBinding.close()
        publishBinding.close()
        raytracerBinding.close()
        rasterBinding.close()
        presentBinding.close()
        traceLayout.close()
        radiosityLayout.close()
        publishLayout.close()
        raytracerLayout.close()
        rasterLayout.close()
        presentLayout.close()
        rasterPipeline.close()
        raytracerPipeline.close()
        publishPipeline.close()
        radiosityPipeline.close()
        tracePipeline.close()
        presentPipeline.close()
        rasterShader.close()
        raytracerShader.close()
        radiosityShader.close()
        traceShader.close()
        presentShader.close()
        indexBuffer.close()
        vertexBuffer.close()
        rasterUniforms.close()
        photonParams.close()
        lightmapSampler.close()
        depthView?.close()
        depthTexture?.close()
        lightmapView.close()
        lightmapTexture.close()
        photonAccumulation.close()
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
