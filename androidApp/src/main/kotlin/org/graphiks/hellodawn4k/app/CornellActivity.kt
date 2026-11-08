package org.graphiks.hellodawn4k.app

import android.app.Activity
import android.os.Bundle
import android.view.MotionEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import org.graphiks.dawn4k.toolkit.surface.DawnSurfaceView
import org.graphiks.dawn4k.toolkit.surface.withDawnSurface
import org.graphiks.hellodawn4k.CornellHostState
import org.graphiks.hellodawn4k.GlyphOverlay
import org.graphiks.hellodawn4k.OrbitCamera
import org.graphiks.hellodawn4k.RenderMode
import org.graphiks.hellodawn4k.runCornellLoop

/**
 * Android host over the shared Cornell renderer: the DawnSurfaceView supplies the
 * Vulkan-backed surface, one finger drags orbit the camera, a double tap toggles
 * rasterizer/raytracer. The status overlay is the shared Kalligraphie glyph chain,
 * like every other target. Lifecycle mirrors the toolkit's sample hosts.
 */
class CornellActivity : Activity(), CornellHostState {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    override val camera = OrbitCamera()
    private var _mode = RenderMode.RASTERIZER
    private var _cameraDirty = true
    override val mode: RenderMode get() = _mode
    override val cameraDirty: Boolean get() = _cameraDirty
    override fun consumeCameraDirty() { _cameraDirty = false }
    private fun dirtyCamera() { _cameraDirty = true }
    private lateinit var surface: DawnSurfaceView
    private var rendering: Job? = null
    private var cleanup: Job? = null
    private var lastDownAt = 0L
    private var lastPointer: Pair<Float, Float>? = null
    private var lastPinchDistance: Float? = null

    // The status overlay is the shared Kalligraphie glyph chain, drawn over the
    // presented frame like every other target; showStatus stays unused here.
    override suspend fun showStatus(text: String) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        surface = DawnSurfaceView(this)
        setContentView(surface)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.pointerCount >= 2) {
            // Pinch zoom: the ratio of successive finger distances scales the radius.
            lastPointer = null
            val dx = event.getX(0) - event.getX(1)
            val dy = event.getY(0) - event.getY(1)
            val distance = kotlin.math.sqrt(dx * dx + dy * dy)
            val previous = lastPinchDistance
            lastPinchDistance = distance
            if (previous != null && previous > 1f) {
                camera.zoomRatio((distance / previous).toDouble())
                dirtyCamera()
            }
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val now = event.downTime
                if (now - lastDownAt < 300) {
                    _mode = if (_mode == RenderMode.RASTERIZER) RenderMode.RAYTRACER else RenderMode.RASTERIZER
                }
                lastDownAt = now
                lastPointer = event.x to event.y
            }
            MotionEvent.ACTION_MOVE -> {
                val previous = lastPointer
                lastPointer = event.x to event.y
                if (previous != null) {
                    camera.drag((event.x - previous.first).toDouble(), (event.y - previous.second).toDouble())
                    dirtyCamera()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                lastPointer = null
                lastPinchDistance = null
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onStart() {
        super.onStart()
        val previousCleanup = cleanup
        rendering = scope.launch {
            previousCleanup?.join()
            val target = surface.target
            target.state.first { it.available && it.extent.width > 0 && it.extent.height > 0 }
            withDawnSurface(target) { gpu, presentation ->
                val format = checkNotNull(presentation.configuration.value).format
                // The Vulkan surface extent is in physical pixels: scale the HUD by the
                // display density so it keeps its on-screen (dp) size.
                GlyphOverlay(
                    gpu.device, gpu.queue, format,
                    scale = resources.displayMetrics.density,
                ).use { overlay ->
                    gpu.runCornellLoop(presentation, this@CornellActivity, overlay)
                }
            }
        }
    }

    override fun onStop() {
        val previous = rendering
        rendering = null
        cleanup = scope.launch {
            previous?.cancelAndJoin()
            surface.awaitRetirement()
        }
        super.onStop()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
