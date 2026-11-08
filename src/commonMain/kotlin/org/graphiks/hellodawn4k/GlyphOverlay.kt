package org.graphiks.hellodawn4k

import org.graphiks.webgpu.GPUCommandEncoder
import org.graphiks.webgpu.GPUDevice
import org.graphiks.webgpu.GPUQueue
import org.graphiks.webgpu.GPUTextureFormat

/**
 * The shared status overlay: the Kalligraphie glyph chain drawn over the presented
 * frame. The host builds one per GPU session with the presentation format and hands
 * it to its render loop; [update] receives the status text at the loop's refresh
 * cadence and [draw] runs as the frame's extra pass. Every target uses this —
 * it is the one status display the project ships.
 */
class GlyphOverlay(
    device: GPUDevice,
    queue: GPUQueue,
    private val format: GPUTextureFormat,
) : AutoCloseable {
    private val text = HudText.openEmbedded(LIBERATION_SANS_TTF)
    private val renderer = text?.let { HudRenderer(device, queue, it) }
    private var vertices = FloatArray(0)

    fun update(status: String) {
        val hud = renderer ?: return
        val shaped = text ?: return
        vertices = hud.buildVertices(
            status.lines().map { line -> shaped.layoutLine(line) },
            baseX = 24f,
            baseY = 60f,
            lineHeight = 30f,
        )
    }

    fun draw(encoder: GPUCommandEncoder, frame: Frame) {
        renderer?.drawOver(encoder, frame, format, vertices)
    }

    override fun close() {
        renderer?.close()
        text?.close()
    }
}
