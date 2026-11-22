package org.graphiks.hellodawn4k

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.graphiks.webgpu.GPUPowerPreference
import org.graphiks.webgpu.browser.HTMLCanvasElement as GpuCanvasElement
import org.graphiks.webgpu.toolkit.AdapterHints
import org.graphiks.webgpu.toolkit.surface.withSurface
import org.graphiks.webgpu.toolkit.web.CanvasSurfaceTarget
import org.graphiks.webgpu.toolkit.web.webToolkit
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLDivElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.MouseEvent
import org.w3c.dom.events.WheelEvent
import kotlin.js.unsafeCast

private val scope: CoroutineScope = MainScope()
private val camera = OrbitCamera()
private var mode = RenderMode.RASTERIZER
private var statusText = ""

/**
 * Web host, all Kotlin over the toolkit's web extension: `webToolkit()` fronts the browser's
 * native WebGPU and the canvas is a `CanvasSurfaceTarget` presented through the same
 * session/surface model the native hosts use through Dawn. Drag orbits, wheel zoom, M/button
 * toggles mode, R resets. The DOM keeps layout, controls (the mode button) and the fallback
 * card; the HUD stays the shared Kalligraphie glyph chain.
 */
fun main() {
    scope.launch {
        val domCanvas = document.getElementById("canvas") as? HTMLCanvasElement
            ?: return@launch fatal("missing canvas element")
        val modeButton = document.getElementById("mode") as? HTMLButtonElement
            ?: return@launch
        // On web the HarfBuzz wasm module instantiates asynchronously: await the runtime
        // before opening any facade (a no-op on JVM and iOS).
        org.graphiks.kalligraphie.initialize()

        // DOM controls stay outside the toolkit: mode button, keyboard, pointer orbit.
        fun toggleMode() {
            mode = if (mode == RenderMode.RASTERIZER) RenderMode.RAYTRACER else RenderMode.RASTERIZER
            modeButton.textContent = "Mode: ${if (mode == RenderMode.RASTERIZER) "rasterizer" else "raytracer"} (M)"
        }
        modeButton.onclick = { toggleMode() }
        document.onkeydown = { event: Event ->
            when (event.unsafeCast<KeyboardEvent>().key.lowercase()) {
                "m" -> toggleMode()
                "r" -> camera.reset()
            }
        }
        var dragging = false
        var lastX = 0.0
        var lastY = 0.0
        domCanvas.addEventListener("pointerdown", { event ->
            val mouse = event.unsafeCast<MouseEvent>()
            dragging = true
            lastX = mouse.clientX.toDouble()
            lastY = mouse.clientY.toDouble()
        })
        domCanvas.addEventListener("pointerup", { _ -> dragging = false })
        domCanvas.addEventListener("pointermove", { event ->
            if (!dragging) return@addEventListener
            val mouse = event.unsafeCast<MouseEvent>()
            camera.drag(mouse.clientX - lastX, mouse.clientY - lastY)
            lastX = mouse.clientX.toDouble()
            lastY = mouse.clientY.toDouble()
        })
        domCanvas.addEventListener("wheel", { event ->
            val wheel = event.unsafeCast<WheelEvent>()
            camera.zoomRatio(kotlin.math.exp(-wheel.deltaY * 0.001))
        })

        val target = CanvasSurfaceTarget(domCanvas.unsafeCast<GpuCanvasElement>())

        var firstPass = true
        var consecutiveFailures = 0
        while (currentCoroutineContext().isActive) {
            try {
                runSurfacePass(target, domCanvas)
                firstPass = false
                consecutiveFailures = 0
            } catch (failure: Throwable) {
                consecutiveFailures++
                if (firstPass) {
                    // The first pass owns the fallback card: no adapter, no WebGPU at all.
                    fatal("No WebGPU: $failure")
                } else if (consecutiveFailures >= 3) {
                    // A device lost mid-session or a persistent negotiation failure must not
                    // rot into a silent black canvas — surface the last reason.
                    fatal("WebGPU keeps failing: $failure")
                }
            }
            // A canvas resize bumps the target generation and toolkit-web v1 kills the
            // leased surface, so every resize ends in a fresh withSurface pass (new session
            // and device, renderer rebuilt). The debounce keeps a drag-resize from
            // rebuilding the renderer in a burst; one stale-surface failure retries
            // silently, three in a row fall back to the card.
            delay(150)
        }
    }
}

/**
 * One toolkit surface pass: a session over the browser implementation, the canvas surface
 * negotiated from what it advertises, frames presented through the surface. A canvas resize
 * moves the target to a new generation, which kills the leased surface (toolkit-web v1), so
 * this function returns and the main loop re-bails after the debounce. Failures propagate to
 * the main loop, which owns the fallback policy.
 */
private suspend fun runSurfacePass(target: CanvasSurfaceTarget, domCanvas: HTMLCanvasElement) {
    withSurface(
        extension = webToolkit(),
        target = target,
        hints = AdapterHints(powerPreference = GPUPowerPreference.HighPerformance),
    ) { session, surface ->
        val format = checkNotNull(surface.configuration.value).format
        // The canvas backing store is CSS size × devicePixelRatio, so the overlay takes
        // that ratio as its scale to keep the HUD's on-screen size; browser zoom can
        // change it, hence the rebuild. The generation check below catches the backing
        // store change a dPR bump causes, ending this pass on the new generation.
        var hudScale = window.devicePixelRatio.toFloat()
        val renderer = CornellRenderer(session.device, session.device.queue, format)
        var overlay = GlyphOverlay(session.device, session.device.queue, format, scale = hudScale)
        if (statusText.isNotEmpty()) overlay.update(statusText)
        val generation = target.state.value.generation
        var emaFrameMs = 16.0
        var lastFrameAt = -1.0
        var lastStatusAt = -1.0
        try {
            while (currentCoroutineContext().isActive) {
                // Pull fallback for browsers without ResizeObserver: every state read
                // refreshes the published extent, so this check sees resizes too.
                if (target.state.value.generation != generation) return@withSurface
                resizeCanvasBackingStore(domCanvas)
                val ratio = window.devicePixelRatio.toFloat()
                if (ratio != hudScale) {
                    hudScale = ratio
                    overlay.close()
                    overlay = GlyphOverlay(session.device, session.device.queue, format, scale = hudScale)
                    if (statusText.isNotEmpty()) overlay.update(statusText)
                }
                // The raytracer reads the camera uniform; the web host must keep it fresh.
                renderer.updateCamera(camera.basis())
                val frameStartedAt = window.performance.now()
                surface.withFrame { frame ->
                    renderer.renderFrame(
                        Frame(frame.view, frame.extent.width.toInt(), frame.extent.height.toInt()),
                        mode, camera,
                    ) { encoder, rendered -> overlay.draw(encoder, rendered) }
                    frame.present()
                }
                renderer.consumeResize()

                val now = window.performance.now()
                if (lastFrameAt >= 0.0) {
                    emaFrameMs = emaFrameMs * 0.9 + (now - lastFrameAt) * 0.1
                }
                lastFrameAt = now
                if (now - lastStatusAt > 500 || lastStatusAt < 0.0) {
                    lastStatusAt = now
                    statusText = statusLines((1000.0 / emaFrameMs).toInt(), renderer, mode, camera)
                        .joinToString("\n")
                    overlay.update(statusText)
                }
                delay(16)
            }
        } finally {
            renderer.close()
            overlay.close()
        }
    }
}

/** Canvas backing store = CSS size × devicePixelRatio (sharpness on dense displays). */
private fun resizeCanvasBackingStore(domCanvas: HTMLCanvasElement) {
    val ratio = window.devicePixelRatio
    val width = (domCanvas.clientWidth * ratio).toInt().coerceAtLeast(1)
    val height = (domCanvas.clientHeight * ratio).toInt().coerceAtLeast(1)
    if (domCanvas.width != width || domCanvas.height != height) {
        domCanvas.width = width
        domCanvas.height = height
    }
}

private fun fatal(message: String): Nothing {
    val fallback = document.getElementById("fallback") as? HTMLDivElement
        ?: throw IllegalStateException(message)
    fallback.hidden = false
    fallback.textContent = message
    (document.getElementById("canvas") as? org.w3c.dom.HTMLElement)?.let { it.style.display = "none" }
    (document.getElementById("mode") as? org.w3c.dom.HTMLElement)?.let { it.style.display = "none" }
    throw IllegalStateException(message)
}
