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
import org.graphiks.dawn4k.toolkit.surface.DawnMetalView
import org.graphiks.dawn4k.toolkit.surface.withDawnSurface
import platform.CoreGraphics.CGRectMake
import platform.UIKit.UIColor
import platform.UIKit.UIEvent
import platform.UIKit.UILabel
import platform.UIKit.UITouch
import platform.UIKit.UIViewController
import platform.UIKit.addSubview

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
        val touch = touches.firstOrNull() as? UITouch ?: return
        val all = withEvent?.allTouches
        if (all != null && all.count() > 1) return // pinch: leave zoom for a later pass
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
                val renderer = CornellRenderer(gpu, checkNotNull(presentation.configuration.value).format)
                try {
                    while (currentCoroutineContext().isActive) {
                        if (cameraDirty) {
                            cameraDirty = false
                            renderer.updateCamera(camera.basis())
                        }
                        presentation.withFrame { frame ->
                            renderer.renderFrame(frame, mode, camera)
                            frame.present()
                        }
                        renderer.consumeResize()
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
