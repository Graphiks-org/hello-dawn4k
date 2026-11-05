package org.graphiks.hellodawn4k

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.graphiks.dawn4k.toolkit.DawnSessionOptions
import org.graphiks.dawn4k.toolkit.glfw.DawnWindow
import org.graphiks.dawn4k.toolkit.glfw.DawnWindowEvent
import org.graphiks.dawn4k.toolkit.glfw.DawnWindowOptions
import org.graphiks.dawn4k.toolkit.glfw.withDawnWindow
import org.graphiks.dawn4k.toolkit.surface.DawnPresentation
import kotlin.time.Duration.Companion.seconds

/** Internal dump resolution; the live render follows the window's physical extent. */
const val WIDTH = 640
const val HEIGHT = 480

// GLFW constants forwarded as-is by the toolkit's window events.
private const val GLFW_RELEASE = 0
private const val GLFW_PRESS = 1
private const val GLFW_BUTTON_LEFT = 0
private const val GLFW_KEY_R = 82
private const val GLFW_KEY_M = 77
private const val GLFW_KEY_ESCAPE = 256

/**
 * Desktop host: a GLFW window over the shared Cornell renderer. The radiosity lightmap
 * converges in surface space while M toggles the rasterizer (default) and raytracer
 * views; drag orbits, the wheel zooms, R resets, Escape closes.
 */
fun main() = runBlocking {
    val frameLimit = System.getProperty("cornell.frames")?.toIntOrNull() ?: 0
    // Deterministic-radiosity hook for A/B dump comparisons: fixes the CPU seed
    // stream the photon passes consume, so identical code renders identical pixels.
    val photonRandom = System.getProperty("cornell.seed")?.toLongOrNull()
        ?.let { kotlin.random.Random(it) }
    val initialMode = when (System.getProperty("cornell.mode")) {
        "raytracer", "ray" -> RenderMode.RAYTRACER
        else -> RenderMode.RASTERIZER
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
            renderCornellFrames(gpu, presentation, frameLimit, controls, initialMode, photonRandom)
        } finally {
            watcher.cancelAndJoin()
        }
    }
}

/** Folded window input into one collector; cameraDirty signals an orbit change. */
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

/** The desktop render loop: shared renderer, Kalligraphie HUD, optional finite dump. */
private suspend fun renderCornellFrames(
    gpu: org.graphiks.dawn4k.toolkit.DawnSession,
    presentation: DawnPresentation,
    frameLimit: Int,
    controls: OrbitControls,
    initialMode: RenderMode = RenderMode.RASTERIZER,
    photonRandom: kotlin.random.Random? = null,
) {
    require(frameLimit >= 0)
    val renderer = CornellRenderer(
        gpu.device, gpu.queue, checkNotNull(presentation.configuration.value).format,
        photonRandom ?: kotlin.random.Random.Default,
    )
    val hudText = if (System.getProperty("cornell.hud") != "false") HudText.open() else null
    val hudRenderer = hudText?.let { HudRenderer(gpu, it) }
    val adapterName = gpu.adapter.info.description
    var presented = 0u
    var mode = initialMode
    var hudVertices = FloatArray(0)
    var emaFrameMs = 16.0
    var lastHudAt = System.nanoTime()

    fun hudLines(): List<String> = statusLines((1000.0 / emaFrameMs).toInt(), renderer, mode, controls.camera) +
        listOf(adapterName)

    try {
        while (currentCoroutineContext().isActive &&
            !controls.closeRequested &&
            (frameLimit == 0 || presented < frameLimit.toUInt())
        ) {
            if (controls.toggleMode) {
                controls.toggleMode = false
                mode = if (mode == RenderMode.RASTERIZER) RenderMode.RAYTRACER else RenderMode.RASTERIZER
                lastHudAt = 0L // force a HUD refresh with the new mode
            }
            if (controls.cameraDirty) {
                controls.cameraDirty = false
                renderer.updateCamera(controls.camera.basis())
            }
            val frameStartedAt = System.nanoTime()
            if (presentation.withFrame { frame ->
                    renderer.renderFrame(frame.toFrame(), mode, controls.camera) { encoder, frame ->
                        drawHud(encoder, frame, hudRenderer, hudVertices, renderer.format)
                    }
                    frame.present()
                }) {
                presented++
            }
            renderer.consumeResize()
            val frameMs = (System.nanoTime() - frameStartedAt) / 1_000_000.0
            emaFrameMs = emaFrameMs * 0.9 + frameMs * 0.1
            if (hudRenderer != null && System.nanoTime() - lastHudAt > 500_000_000L) {
                lastHudAt = System.nanoTime()
                hudVertices = hudRenderer.buildVertices(
                    hudLines().map { hudText!!.layoutLine(it) },
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
                hudVertices = hudRenderer.buildVertices(
                    hudLines().map { hudText!!.layoutLine(it) },
                    baseX = 12f,
                    baseY = 30f,
                    lineHeight = 26f,
                )
            }
            renderer.dumpPresentedFrame("cornell-window.png", mode, controls.camera, hudRenderer, hudVertices)
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

private fun org.graphiks.dawn4k.toolkit.surface.DawnFrame.toFrame() =
    org.graphiks.hellodawn4k.Frame(view, extent.width, extent.height)

private fun drawHud(
    encoder: org.graphiks.webgpu.GPUCommandEncoder,
    frame: Frame,
    hud: HudRenderer?,
    hudVertices: FloatArray,
    format: org.graphiks.webgpu.GPUTextureFormat,
) {
    if (hud == null || hudVertices.isEmpty()) return
    val pass = encoder.beginRenderPass(org.graphiks.webgpu.descriptors.RenderPassDescriptor(colorAttachments = listOf(
        org.graphiks.webgpu.descriptors.RenderPassColorAttachment(
            frame.view,
            loadOp = org.graphiks.webgpu.GPULoadOp.Load,
            storeOp = org.graphiks.webgpu.GPUStoreOp.Store,
            clearValue = org.graphiks.webgpu.descriptors.Color(0.0, 0.0, 0.0, 1.0),
        ),
    )))
    try {
        hud.draw(pass, format, frame.width, frame.height, hudVertices)
    } finally {
        pass.end()
    }
}
