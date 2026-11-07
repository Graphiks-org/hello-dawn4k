package org.graphiks.hellodawn4k

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.graphiks.dawn4k.toolkit.DawnSession
import org.graphiks.dawn4k.toolkit.surface.DawnPresentation
import org.graphiks.webgpu.GPUCommandEncoder
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
 * The Android/iOS render loop over the shared Cornell renderer: drains the camera
 * flag, renders and presents one Dawn frame at a time, tracks an EMA of the frame
 * time and refreshes the status at 2 Hz, then drains the GPU on exit.
 *
 * [onStatus] receives the status text at the refresh cadence and [drawOverlay] runs
 * as the frame's extra pass — a host wires its [GlyphOverlay] through both (iOS), or
 * shows the text natively (Android, until Kalligraphie's raster-cpu AAR republish).
 */
suspend fun DawnSession.runCornellLoop(
    presentation: DawnPresentation,
    state: CornellHostState,
    onStatus: ((String) -> Unit)? = null,
    drawOverlay: ((GPUCommandEncoder, Frame) -> Unit)? = null,
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
                ) { encoder, rendered -> drawOverlay?.invoke(encoder, rendered) }
                frame.present()
            }
            renderer.consumeResize()
            if (timeSource.markNow() - lastStatusAt > 500.milliseconds) {
                lastStatusAt = timeSource.markNow()
                val text = statusLines((1000.0 / emaFrameMs).roundToInt(), renderer, state.mode, state.camera)
                    .joinToString("\n")
                state.showStatus(text)
                onStatus?.invoke(text)            }
            delay(16)
        }
    } finally {
        renderer.close()
        queue.onSubmittedWorkDone().getOrThrow()
    }
}
