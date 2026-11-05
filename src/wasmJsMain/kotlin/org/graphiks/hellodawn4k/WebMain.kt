package org.graphiks.hellodawn4k

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.graphiks.webgpu.GPUPowerPreference
import org.graphiks.webgpu.GPUTextureView
import org.graphiks.webgpu.browser.CanvasSurface
import org.graphiks.webgpu.browser.HTMLCanvasElement as GpuCanvasElement
import org.graphiks.webgpu.browser.SurfaceConfiguration
import org.graphiks.webgpu.browser.getCanvasSurface
import org.graphiks.webgpu.browser.requestAdapter
import org.graphiks.webgpu.descriptors.RequestAdapterOptions
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLDivElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.MouseEvent
import org.w3c.dom.events.WheelEvent
import kotlin.js.unsafeCast

/**
 * Web host, all Kotlin over the browser's native WebGPU (webgpu-browser wraps navigator.gpu
 * behind the same GPUDevice/GPUQueue interfaces the native targets use through Dawn).
 * Drag orbits, wheel zoom, M/button toggles mode, R resets. Two views of the same canvas:
 * the DOM element (layout, events) and the GPU-facing external interface (context).
 */
private val scope: CoroutineScope = MainScope()
private val camera = OrbitCamera()
private var mode = RenderMode.RASTERIZER

fun main() {
    scope.launch {
        val adapter = requestAdapter(
            RequestAdapterOptions(powerPreference = GPUPowerPreference.HighPerformance),
        ).getOrElse { failure -> fatal("Aucun adaptateur WebGPU : $failure") }
        val device = adapter.requestDevice().getOrElse { failure -> fatal("Device refusé : $failure") }

        val domCanvas = document.getElementById("canvas") as? HTMLCanvasElement
            ?: return@launch fatal("canvas manquant")
        val surface: CanvasSurface = domCanvas.unsafeCast<GpuCanvasElement>().getCanvasSurface()
        val format = surface.preferredCanvasFormat
            ?: return@launch fatal("Le navigateur n'expose pas de format de canvas préféré.")
        surface.configure(SurfaceConfiguration(device = device, format = format))

        val renderer = CornellRenderer(device, device.queue, format)
        val hud = document.getElementById("hud") as? HTMLDivElement ?: return@launch
        val modeButton = document.getElementById("mode") as? HTMLButtonElement ?: return@launch

        fun toggleMode() {
            mode = if (mode == RenderMode.RASTERIZER) RenderMode.RAYTRACER else RenderMode.RASTERIZER
            modeButton.textContent = "Mode : ${if (mode == RenderMode.RASTERIZER) "rasterizer" else "raytracer"} (M)"
        }
        modeButton.onclick = { toggleMode() }
        document.onkeydown = { event: Event ->
            val key = event.unsafeCast<KeyboardEvent>().key.lowercase()
            when (key) {
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

        var lastStatusAt = -1.0
        var lastFrameAt = -1.0
        var emaFrameMs = 16.0

        fun tick() {
            val ratio = window.devicePixelRatio
            val width = (domCanvas.clientWidth * ratio).toInt().coerceAtLeast(1)
            val height = (domCanvas.clientHeight * ratio).toInt().coerceAtLeast(1)
            if (domCanvas.width != width || domCanvas.height != height) {
                domCanvas.width = width
                domCanvas.height = height
            }
            // The raytracer reads the camera uniform; the web host must keep it fresh.
            renderer.updateCamera(camera.basis())
            val texture = surface.getCurrentTexture()
            val view: GPUTextureView = texture.texture.createView()
            renderer.renderFrame(Frame(view, width, height), mode, camera)
            renderer.consumeResize()

            val now = window.performance.now()
            if (lastFrameAt >= 0.0) {
                emaFrameMs = emaFrameMs * 0.9 + (now - lastFrameAt) * 0.1
            }
            lastFrameAt = now
            if (now - lastStatusAt > 500 || lastStatusAt < 0.0) {
                lastStatusAt = now
                hud.textContent = statusLines((1000.0 / emaFrameMs).toInt(), renderer, mode, camera)
                    .joinToString("\n")
            }
            window.requestAnimationFrame { tick() }
        }
        tick()
    }
}

private fun fatal(message: String): Nothing {
    val fallback = document.getElementById("fallback") as? HTMLDivElement
        ?: throw IllegalStateException(message)
    fallback.hidden = false
    fallback.textContent = message
    (document.getElementById("canvas") as? org.w3c.dom.HTMLElement)?.let { it.style.display = "none" }
    (document.getElementById("hud") as? org.w3c.dom.HTMLElement)?.let { it.style.display = "none" }
    (document.getElementById("mode") as? org.w3c.dom.HTMLElement)?.let { it.style.display = "none" }
    throw IllegalStateException(message)
}
