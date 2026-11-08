@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package org.graphiks.hellodawn4k

import kotlinx.cinterop.useContents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import org.graphiks.dawn4k.toolkit.surface.DawnMetalView
import org.graphiks.dawn4k.toolkit.surface.withDawnSurface
import platform.CoreGraphics.CGRectMake
import platform.UIKit.UIEvent
import platform.UIKit.UIScreen
import platform.UIKit.UITouch
import platform.UIKit.UIViewController

/**
 * iOS host over the shared Cornell renderer: UIKit owns the DawnMetalView (Metal-backed),
 * one finger drags orbit the camera, a double tap toggles rasterizer/raytracer. GPU
 * cleanup suspends without blocking UIKit's main queue, like the toolkit's sample host.
 *
 * Kotlin/Native forbids mixing Objective-C and Kotlin supertypes on one class, so the
 * host state (what the shared loop reads) lives in its own Kotlin class and the
 * controller wraps it.
 */
private class CornellHostStateImpl : CornellHostState {
    override val camera = OrbitCamera()
    private var _mode = RenderMode.RASTERIZER
    private var _cameraDirty = true
    override val mode: RenderMode get() = _mode
    override val cameraDirty: Boolean get() = _cameraDirty
    override fun consumeCameraDirty() { _cameraDirty = false }
    fun dirtyCamera() { _cameraDirty = true }
    fun toggleMode() {
        _mode = if (_mode == RenderMode.RASTERIZER) RenderMode.RAYTRACER else RenderMode.RASTERIZER
    }

    // The status overlay is the shared glyph chain; showStatus is reserved for hosts
    // that still display status natively — this one draws it in the GPU frame.
    override suspend fun showStatus(text: String) {}
}

private class CornellViewControllerImpl : UIViewController(nibName = null, bundle = null) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val state = CornellHostStateImpl()
    private var rendering: kotlinx.coroutines.Job? = null
    private var cleanup: kotlinx.coroutines.Job? = null
    private var lastDownAt = 0.0
    private var lastPointer: Pair<Double, Double>? = null
    private var lastPinchDistance: Double? = null
    private lateinit var metal: DawnMetalView

    override fun loadView() {
        metal = DawnMetalView(CGRectMake(0.0, 0.0, 640.0, 480.0))
        view = metal
    }

    private fun pinchDistance(touches: Set<*>): Double? {
        val first = touches.elementAtOrNull(0) as? UITouch ?: return null
        val second = touches.elementAtOrNull(1) as? UITouch ?: return null
        val a = first.locationInView(metal).useContents { x to y }
        val b = second.locationInView(metal).useContents { x to y }
        val dx = a.first - b.first
        val dy = a.second - b.second
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    override fun touchesBegan(touches: Set<*>, withEvent: UIEvent?) {
        super.touchesBegan(touches, withEvent)
        val touch = touches.firstOrNull() as? UITouch ?: return
        if ((withEvent?.allTouches()?.count() ?: 0) >= 2) {
            // Second finger down: pinch starts, drag pauses.
            lastPointer = null
            lastPinchDistance = withEvent?.allTouches()?.let { pinchDistance(it) }
            return
        }
        val location = touch.locationInView(metal).useContents { x to y }
        val now = touch.timestamp
        if (now - lastDownAt < 0.3) {
            state.toggleMode()
        }
        lastDownAt = now
        lastPointer = location
    }

    override fun touchesMoved(touches: Set<*>, withEvent: UIEvent?) {
        super.touchesMoved(touches, withEvent)
        val all = withEvent?.allTouches()
        if (all != null && all.count() >= 2) {
            // Pinch zoom: the ratio of successive finger distances scales the radius.
            val distance = pinchDistance(all) ?: return
            val previous = lastPinchDistance
            lastPinchDistance = distance
            if (previous != null && previous > 1.0) {
                state.camera.zoomRatio(distance / previous)
                state.dirtyCamera()
            }
            return
        }
        val touch = touches.firstOrNull() as? UITouch ?: return
        val location = touch.locationInView(metal).useContents { x to y }
        val previous = lastPointer
        lastPointer = location
        if (previous != null) {
            state.camera.drag(location.first - previous.first, location.second - previous.second)
            state.dirtyCamera()
        }
    }

    override fun touchesEnded(touches: Set<*>, withEvent: UIEvent?) {
        super.touchesEnded(touches, withEvent)
        lastPointer = null
        lastPinchDistance = null
    }

    override fun viewDidAppear(animated: Boolean) {
        super.viewDidAppear(animated)
        if (rendering != null) return
        val previousCleanup = cleanup
        rendering = scope.launch {
            previousCleanup?.join()
            val target = metal.target
            target.state.first { it.available && it.extent.width > 0 && it.extent.height > 0 }
            withDawnSurface(target) { gpu, presentation ->
                val format = checkNotNull(presentation.configuration.value).format
                // Metal drawables are physical pixels (points × nativeScale): scale the
                // HUD so it keeps its on-screen (point) size on @2x/@3x displays.
                GlyphOverlay(
                    gpu.device, gpu.queue, format,
                    scale = UIScreen.mainScreen.nativeScale.toFloat(),
                ).use { overlay ->
                    gpu.runCornellLoop(presentation, state, overlay)
                }
            }
        }
    }

    override fun viewDidDisappear(animated: Boolean) {
        val previous = rendering
        rendering = null
        cleanup = scope.launch {
            previous?.cancelAndJoin()
            metal.awaitRetirement()
        }
        super.viewDidDisappear(animated)
    }
}

/** The Xcode app's root controller — CornellViewControllerKt.CornellViewController() in Swift. */
fun CornellViewController(): UIViewController = CornellViewControllerImpl()
