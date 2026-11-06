package org.graphiks.hellodawn4k.app

import android.app.Activity
import android.os.Bundle
import android.view.MotionEvent
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.graphiks.dawn4k.toolkit.surface.DawnSurfaceView
import org.graphiks.dawn4k.toolkit.surface.withDawnSurface
import org.graphiks.hellodawn4k.CornellHostState
import org.graphiks.hellodawn4k.OrbitCamera
import org.graphiks.hellodawn4k.RenderMode
import org.graphiks.hellodawn4k.runCornellLoop

/**
 * Android host over the shared Cornell renderer: the DawnSurfaceView supplies the
 * Vulkan-backed surface, one finger drags orbit the camera, a double tap toggles
 * rasterizer/raytracer. Lifecycle mirrors the toolkit's sample hosts.
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
    private lateinit var status: TextView
    private var rendering: Job? = null
    private var cleanup: Job? = null
    private var lastDownAt = 0L
    private var lastPointer: Pair<Float, Float>? = null
    private var lastPinchDistance: Float? = null

    override suspend fun showStatus(text: String) {
        withContext(Dispatchers.Main) { status.text = text }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { text = "Starting…" }
        surface = DawnSurfaceView(this)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            @Suppress("DEPRECATION")
            setOnApplyWindowInsetsListener { root, insets ->
                root.setPadding(
                    insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom,
                )
                insets
            }
            addView(status, LinearLayout.LayoutParams(-1, -2))
            addView(surface, LinearLayout.LayoutParams(-1, 0, 1f))
        })
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
        status.text = "Starting…"
        val previousCleanup = cleanup
        rendering = scope.launch {
            previousCleanup?.join()
            val target = surface.target
            target.state.first { it.available && it.extent.width > 0 && it.extent.height > 0 }
            withDawnSurface(target) { gpu, presentation ->
                renderCornell(gpu, presentation)
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

    private suspend fun renderCornell(gpu: org.graphiks.dawn4k.toolkit.DawnSession, presentation: org.graphiks.dawn4k.toolkit.surface.DawnPresentation) {
        gpu.runCornellLoop(presentation, this)
    }
}
