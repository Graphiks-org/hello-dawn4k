package org.graphiks.hellodawn4k

import kotlinx.coroutines.runBlocking
import org.graphiks.dawn4k.toolkit.DawnSession
import org.graphiks.dawn4k.toolkit.DawnSessionOptions
import org.graphiks.dawn4k.toolkit.withDawnSession
import org.graphiks.webgpu.ArrayBuffer
import org.graphiks.webgpu.GPUBufferUsage
import org.graphiks.webgpu.GPUMapMode
import org.graphiks.webgpu.GPUTextureFormat
import org.graphiks.webgpu.GPUTextureUsage
import org.graphiks.webgpu.descriptors.BufferDescriptor
import org.graphiks.webgpu.descriptors.Extent3D
import org.graphiks.webgpu.descriptors.TexelCopyBufferInfo
import org.graphiks.webgpu.descriptors.TexelCopyTextureInfo
import org.graphiks.webgpu.descriptors.TextureDescriptor
import kotlin.time.Duration.Companion.seconds

/**
 * Headless seeded rendering for tests: a window-less Dawn session renders [frames] frames of the
 * scene into a 640x480 RGBA8Unorm offscreen target through the real renderer (rasterizer or
 * raytracer), then reads the pixels back. The photon stream is seeded, so identical arguments
 * produce identical pixels on a given GPU/driver pair — statistics stay portable.
 */
object HeadlessCornell {
    const val WIDTH = 640
    const val HEIGHT = 480
    private const val BYTES_PER_ROW = WIDTH * 4

    class Result(val photonsEmitted: Double, val rgba: ByteArray)

    fun render(frames: Int, seed: Long, mode: RenderMode): Result = runBlocking {
        withDawnSession(DawnSessionOptions(startupTimeout = 60.seconds)) { session ->
            renderInSession(session, frames, seed, mode)
        }
    }

    internal suspend fun renderInSession(session: DawnSession, frames: Int, seed: Long, mode: RenderMode): Result {
        val renderer = CornellRenderer(
            session.device, session.queue, GPUTextureFormat.RGBA8Unorm, kotlin.random.Random(seed),
        )
        val camera = OrbitCamera()
        session.device.createTexture(
            TextureDescriptor(
                size = Extent3D(WIDTH.toUInt(), HEIGHT.toUInt()),
                format = GPUTextureFormat.RGBA8Unorm,
                usage = GPUTextureUsage.RenderAttachment or GPUTextureUsage.CopySrc,
            ),
        ).use { target ->
            target.createView().use { view ->
                session.device.createBuffer(
                    BufferDescriptor(BYTES_PER_ROW.toULong() * HEIGHT.toULong(), GPUBufferUsage.CopyDst or GPUBufferUsage.MapRead),
                ).use { staging ->
                    repeat(frames) {
                        renderer.updateCamera(camera.basis())
                        renderer.renderFrame(Frame(view, WIDTH, HEIGHT), mode, camera)
                        renderer.consumeResize()
                    }
                    val encoder = session.device.createCommandEncoder()
                    try {
                        encoder.copyTextureToBuffer(
                            TexelCopyTextureInfo(target),
                            TexelCopyBufferInfo(staging, bytesPerRow = BYTES_PER_ROW.toUInt(), rowsPerImage = HEIGHT.toUInt()),
                            Extent3D(WIDTH.toUInt(), HEIGHT.toUInt()),
                        )
                        encoder.finish().use { session.queue.submit(listOf(it)) }
                    } finally {
                        encoder.close()
                    }
                    staging.mapAsync(GPUMapMode.Read).getOrThrow()
                    val rgba = try {
                        staging.getMappedRange().toByteArray()
                    } finally {
                        staging.unmap()
                    }
                    val photons = renderer.photonsEmitted
                    renderer.close()
                    return Result(photons, rgba)
                }
            }
        }
    }

    /** Mean of a channel over a rectangle: region = [x0, y0, x1, y1). */
    fun channelMean(rgba: ByteArray, x0: Int, y0: Int, x1: Int, y1: Int, channel: Int): Double {
        var total = 0.0
        var count = 0
        for (y in y0 until y1) {
            for (x in x0 until x1) {
                total += rgba[(y * WIDTH + x) * 4 + channel].toInt() and 0xff
                count++
            }
        }
        return total / count
    }
}
