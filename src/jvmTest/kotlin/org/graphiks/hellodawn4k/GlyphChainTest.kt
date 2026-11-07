package org.graphiks.hellodawn4k

import kotlinx.coroutines.runBlocking
import org.graphiks.dawn4k.toolkit.DawnSessionOptions
import org.graphiks.dawn4k.toolkit.withDawnSession
import org.graphiks.webgpu.GPULoadOp
import org.graphiks.webgpu.GPUStoreOp
import org.graphiks.webgpu.GPUTextureFormat
import org.graphiks.webgpu.GPUTextureUsage
import org.graphiks.webgpu.descriptors.BufferDescriptor
import org.graphiks.webgpu.descriptors.Extent3D
import org.graphiks.webgpu.descriptors.RenderPassColorAttachment
import org.graphiks.webgpu.descriptors.RenderPassDescriptor
import org.graphiks.webgpu.descriptors.TextureDescriptor
import org.graphiks.webgpu.GPUBufferUsage
import org.graphiks.webgpu.GPUMapMode
import org.graphiks.webgpu.descriptors.TexelCopyBufferInfo
import org.graphiks.webgpu.descriptors.TexelCopyTextureInfo
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Exercises the whole shared glyph chain headlessly: desktop font capture (JVM extraction
 * of the bundled Liberation Sans through Kalligraphie), shaping, CPU rasterization, the
 * R8 atlas, the alpha-blended GPU pass and the readback — text drawn over a cleared
 * target must leave lit pixels.
 */
class GlyphChainTest {
    @Test
    fun shapedTextRasterizesAndBlendsOverTheTarget() = runBlocking {
        withDawnSession(DawnSessionOptions(startupTimeout = 60.seconds)) { session ->
            val hudText = DesktopHudFont.open()
            assertTrue(hudText != null, "desktop font capture should succeed")
            hudText!!.use { text ->
                val lines = listOf(
                    text.layoutLine("60 fps · 0M photons · rasterizer"),
                    text.layoutLine("az 0°  el 6°  zoom 3.1"),
                )
                assertTrue(lines.any { it.isNotEmpty() }, "shaping should position glyphs")

                val renderer = HudRenderer(session.device, session.queue, text)
                renderer.use { hud ->
                    val floats = hud.buildVertices(lines, baseX = 12f, baseY = 30f, lineHeight = 26f)
                    assertTrue(floats.isNotEmpty(), "glyph quads should be built")

                    val width = 640
                    val height = 96
                    session.device.createTexture(
                        TextureDescriptor(
                            size = Extent3D(width.toUInt(), height.toUInt()),
                            format = GPUTextureFormat.RGBA8Unorm,
                            usage = GPUTextureUsage.RenderAttachment or GPUTextureUsage.CopySrc,
                        ),
                    ).use { target ->
                        target.createView().use { view ->
                            session.device.createBuffer(
                                BufferDescriptor((width * 4).toULong() * height.toULong(), GPUBufferUsage.CopyDst or GPUBufferUsage.MapRead),
                            ).use { staging ->
                                val encoder = session.device.createCommandEncoder()
                                try {
                                    val pass = encoder.beginRenderPass(
                                        RenderPassDescriptor(colorAttachments = listOf(
                                            RenderPassColorAttachment(
                                                view,
                                                loadOp = GPULoadOp.Clear,
                                                storeOp = GPUStoreOp.Store,
                                                clearValue = org.graphiks.webgpu.descriptors.Color(0.0, 0.0, 0.0, 1.0),
                                            ),
                                        )),
                                    )
                                    try {
                                        hud.draw(pass, GPUTextureFormat.RGBA8Unorm, width, height, floats)
                                    } finally {
                                        pass.end()
                                    }
                                    encoder.copyTextureToBuffer(
                                        TexelCopyTextureInfo(target),
                                        TexelCopyBufferInfo(staging, bytesPerRow = (width * 4).toUInt(), rowsPerImage = height.toUInt()),
                                        Extent3D(width.toUInt(), height.toUInt()),
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
                                val lit = rgba.indices.step(4).count { rgba[it] > 96 }
                                val maxChannel = rgba.max()
                                val firstQuad = floats.take(24).joinToString(",")
                                assertTrue(lit > 200, "text should leave lit pixels over the cleared target: " +
                                    "lit=$lit max=$maxChannel floats0=$firstQuad floats=${floats.size}")
                            }
                        }
                    }
                }
            }
        }
    }
}
