package org.graphiks.hellodawn4k

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.graphiks.dawn4k.toolkit.DawnSession
import org.graphiks.dawn4k.toolkit.surface.DawnPresentation
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

/**
 * The state the shared mobile loop reads from its host. Hosts own lifecycle, surface
 * acquisition, input events and the status view; implement this with volatile-backed
 * properties since input arrives on the UI thread while the loop renders elsewhere.
 */
interface CornellHostState {
    val camera: OrbitCamera
    val mode: RenderMode
    val cameraDirty: Boolean
    fun consumeCameraDirty()
    suspend fun showStatus(text: String)
}

/**
 * A Kalligraphie-drawn status overlay a host can attach to the shared loop: [update]
 * receives the status text at the loop's 2 Hz refresh cadence, [draw] is invoked as the
 * render frame's extra pass, right over the presented texture. Hosts that show status
 * natively (Android's TextView, while Kalligraphie's Android AARs share one namespace)
 * pass no overlay and keep [CornellHostState.showStatus].
 */
interface CornellOverlay {
    fun update(text: String)
    fun draw(encoder: org.graphiks.webgpu.GPUCommandEncoder, frame: Frame)
}

/**
 * The Android/iOS render loop over the shared Cornell renderer: drains the camera
 * flag, renders and presents one Dawn frame at a time, tracks an EMA of the frame
 * time and refreshes the host's status at 2 Hz, then drains the GPU on exit.
 */
suspend fun DawnSession.runCornellLoop(
    presentation: DawnPresentation,
    state: CornellHostState,
    overlay: CornellOverlay? = null,
) {
    val renderer = CornellRenderer(device, queue, checkNotNull(presentation.configuration.value).format)
    val timeSource = kotlin.time.TimeSource.Monotonic
    var lastStatusAt = timeSource.markNow()
    var lastFrameAt: kotlin.time.TimeMark? = null
    var emaFrameMs = 16.0
    try {
        while (currentCoroutineContext().isActive) {
            // Average the full loop period (work + pacing delay), not the bare render
            // work — otherwise the counter reports hundreds of fps nobody sees.
            lastFrameAt?.let { previous ->
                emaFrameMs = emaFrameMs * 0.9 + (previous.elapsedNow().inWholeNanoseconds / 1_000_000.0) * 0.1
            }
            lastFrameAt = timeSource.markNow()
            if (state.cameraDirty) {
                state.consumeCameraDirty()
                renderer.updateCamera(state.camera.basis())
            }
            presentation.withFrame { frame ->
                renderer.renderFrame(
                    Frame(frame.view, frame.extent.width, frame.extent.height),
                    state.mode, state.camera,
                ) { encoder, rendered -> overlay?.draw(encoder, rendered) }
                frame.present()
            }
            renderer.consumeResize()
            if (timeSource.markNow() - lastStatusAt > 500.milliseconds) {
                lastStatusAt = timeSource.markNow()
                val text = statusLines((1000.0 / emaFrameMs).roundToInt(), renderer, state.mode, state.camera)
                    .joinToString("\n")
                state.showStatus(text)
                overlay?.update(text)
            }
            delay(16)
        }
    } finally {
        renderer.close()
        queue.onSubmittedWorkDone().getOrThrow()
    }
}
