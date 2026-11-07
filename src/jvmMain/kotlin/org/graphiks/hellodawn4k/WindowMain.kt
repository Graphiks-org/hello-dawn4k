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
    val camera = OrbitCamera()
    withDawnWindow(
        windowOptions = DawnWindowOptions(title = "hello-dawn4k — Cornell box", width = 960, height = 720),
        sessionOptions = DawnSessionOptions(startupTimeout = 60.seconds),
    ) { gpu, window, presentation ->
        val controls = OrbitControls(window, camera)
        val watcher = launch { controls.collect() }
        try {
            renderCornellFrames(gpu, presentation, controls)
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

/** The desktop render loop: shared renderer, Kalligraphie HUD. */
private suspend fun renderCornellFrames(
    gpu: org.graphiks.dawn4k.toolkit.DawnSession,
    presentation: DawnPresentation,
    controls: OrbitControls,
) {
    val renderer = CornellRenderer(
        gpu.device, gpu.queue, checkNotNull(presentation.configuration.value).format,
    )
    val hudText = HudText.openEmbedded(LIBERATION_SANS_TTF)
    val hudRenderer = hudText?.let { HudRenderer(gpu.device, gpu.queue, it) }
    val adapterName = gpu.adapter.info.description
    var mode = RenderMode.RASTERIZER
    var hudVertices = FloatArray(0)
    var emaFrameMs = 16.0
    var lastHudAt = System.nanoTime()

    fun hudLines(): List<String> = statusLines((1000.0 / emaFrameMs).toInt(), renderer, mode, controls.camera) +
        listOf(adapterName)

    try {
        while (currentCoroutineContext().isActive && !controls.closeRequested) {
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
                        hudRenderer?.drawOver(encoder, frame, renderer.format, hudVertices)
                    }
                    frame.present()
                }) {
                // Frame presented; nothing to count.
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
