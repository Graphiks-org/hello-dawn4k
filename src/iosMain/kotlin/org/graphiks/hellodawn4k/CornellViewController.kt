@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package org.graphiks.hellodawn4k

import kotlinx.cinterop.useContents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.graphiks.dawn4k.toolkit.surface.DawnMetalView
import org.graphiks.dawn4k.toolkit.surface.withDawnSurface
import platform.CoreGraphics.CGRectMake
import platform.UIKit.UIColor
import platform.UIKit.UIEvent
import platform.UIKit.UILabel
import platform.UIKit.UITouch
import platform.UIKit.UIViewController
import platform.UIKit.addSubview
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

/**
 * iOS host over the shared Cornell renderer: UIKit owns the DawnMetalView (Metal-backed),
 * one finger drags orbit the camera, a double tap toggles rasterizer/raytracer. GPU
 * cleanup suspends without blocking UIKit's main queue, like the toolkit's sample host.
 */
fun CornellViewController(): UIViewController = object : UIViewController(nibName = null, bundle = null) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val camera = OrbitCamera()
    private var mode = RenderMode.RASTERIZER
    private var cameraDirty = true
    private var rendering: kotlinx.coroutines.Job? = null
    private var cleanup: kotlinx.coroutines.Job? = null
    private var lastDownAt = 0.0
    private var lastPointer: Pair<Double, Double>? = null
    private var lastPinchDistance: Double? = null

    private fun pinchDistance(touches: Set<*>): Double? {
        val first = touches.elementAtOrNull(0) as? UITouch ?: return null
        val second = touches.elementAtOrNull(1) as? UITouch ?: return null
        val a = first.locationInView(metal).useContents { x to y }
        val b = second.locationInView(metal).useContents { x to y }
        val dx = a.first - b.first
        val dy = a.second - b.second
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }
    private lateinit var metal: DawnMetalView
    private lateinit var status: UILabel

    override fun loadView() {
        metal = DawnMetalView(CGRectMake(0.0, 0.0, 640.0, 480.0))
        view = metal
        status = UILabel(CGRectMake(24.0, 50.0, 1100.0, 80.0)).apply {
            text = "Démarrage…"
            numberOfLines = 0
            textColor = UIColor.whiteColor
        }
        metal.addSubview(status)
    }

    override fun viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        status.setFrame(
            CGRectMake(24.0, 50.0, (metal.bounds.useContents { size.width } - 48.0).coerceAtLeast(0.0), 100.0),
        )
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
            mode = if (mode == RenderMode.RASTERIZER) RenderMode.RAYTRACER else RenderMode.RASTERIZER
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
                camera.zoomRatio(distance / previous)
                cameraDirty = true
            }
            return
        }
        val touch = touches.firstOrNull() as? UITouch ?: return
        val location = touch.locationInView(metal).useContents { x to y }
        val previous = lastPointer
        lastPointer = location
        if (previous != null) {
            camera.drag(location.first - previous.first, location.second - previous.second)
            cameraDirty = true
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
        status.text = "Démarrage…"
        val previousCleanup = cleanup
        rendering = scope.launch {
            previousCleanup?.join()
            val target = metal.target
            target.state.first { it.available && it.extent.width > 0 && it.extent.height > 0 }
            withDawnSurface(target) { gpu, presentation ->
                val renderer = CornellRenderer(gpu.device, gpu.queue, checkNotNull(presentation.configuration.value).format)
                val timeSource = kotlin.time.TimeSource.Monotonic
                var lastStatusAt = timeSource.markNow()
                var emaFrameMs = 16.0
                try {
                    while (currentCoroutineContext().isActive) {
                        if (cameraDirty) {
                            cameraDirty = false
                            renderer.updateCamera(camera.basis())
                        }
                        val frameStartedAt = timeSource.markNow()
                        presentation.withFrame { frame ->
                            renderer.renderFrame(org.graphiks.hellodawn4k.Frame(frame.view, frame.extent.width, frame.extent.height), mode, camera)
                            frame.present()
                        }
                        renderer.consumeResize()
                        emaFrameMs = emaFrameMs * 0.9 + (frameStartedAt.elapsedNow().inWholeNanoseconds / 1_000_000.0) * 0.1
                        if (timeSource.markNow() - lastStatusAt > 500.milliseconds) {
                            lastStatusAt = timeSource.markNow()
                            val text = statusLines((1000.0 / emaFrameMs).roundToInt(), renderer, mode, camera)
                                .joinToString("\n")
                            withContext(Dispatchers.Main) { status.text = text }
                        }
                        delay(16)
                    }
                } finally {
                    renderer.close()
                    gpu.queue.onSubmittedWorkDone().getOrThrow()
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
