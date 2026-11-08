package org.graphiks.hellodawn4k

import kotlinx.coroutines.runBlocking
import org.graphiks.dawn4k.toolkit.DawnSessionOptions
import org.graphiks.dawn4k.toolkit.withDawnSession
import org.graphiks.webgpu.GPULoadOp
import org.graphiks.webgpu.GPUStoreOp
import org.graphiks.webgpu.GPUTextureFormat
import org.graphiks.webgpu.GPUTextureUsage
import org.graphiks.webgpu.descriptors.BufferDescriptor
import org.graphiks.webgpu.descriptors.Color
import org.graphiks.webgpu.descriptors.Extent3D
import org.graphiks.webgpu.descriptors.RenderPassColorAttachment
import org.graphiks.webgpu.descriptors.RenderPassDescriptor
import org.graphiks.webgpu.descriptors.TextureDescriptor
import org.graphiks.webgpu.GPUBufferUsage
import org.graphiks.webgpu.GPUMapMode
import org.graphiks.webgpu.descriptors.TexelCopyBufferInfo
import org.graphiks.webgpu.descriptors.TexelCopyTextureInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The HUD must keep its on-screen size across hosts: the glyph chain works in
 * framebuffer pixels, so every host with a scaled backing store (Retina web,
 * Android densities, iOS @2x/@3x) passes its surface scale to [GlyphOverlay].
 * These tests pin the scale math: the anchors (base 24/60, line height 30,
 * font 18 px) scale with it, and the atlas still places every glyph of the
 * real status text at the densest common density (Android xxxhdpi = 4x).
 */
class GlyphOverlayScaleTest {
    private val width = 320
    private val height = 320

    @Test
    fun scaledOverlayAnchorsAtPhysicalPixels() = runBlocking {
        checkOverlayOrigin(scale = 2f).let { (minColumn, minRow) ->
            // baseX 48 plus a small bearing, cap top ~26 px above baseline 120.
            assertTrue(minColumn in 44..56, "scale 2x should anchor the ink at x≈48, got $minColumn")
            assertTrue(minRow in 88..100, "scale 2x should put the ink top at y≈94, got $minRow")
        }
    }

    @Test
    fun unscaledOverlayKeepsTheDesktopReferenceAnchors() = runBlocking {
        checkOverlayOrigin(scale = 1f).let { (minColumn, minRow) ->
            // The JVM desktop reference: base 24/60 at 1x stays untouched.
            assertTrue(minColumn in 20..32, "scale 1x should anchor the ink at x≈24, got $minColumn")
            assertTrue(minRow in 40..54, "scale 1x should put the ink top at y≈47, got $minRow")
        }
    }

    @Test
    fun xxxhdpiStatusTextPlacesEveryGlyph() = runBlocking {
        withDawnSession(DawnSessionOptions(startupTimeout = 60.seconds)) { session ->
            val statusText = listOf("60 fps · 0M photons · rasterizer", "az 0°  el 6°  zoom 3.1")
            // Densities up to 4x (Android xxxhdpi) quadruple the font pixels; the
            // atlas must still hold the whole two-line status without dropping a glyph.
            val text = checkNotNull(HudText.openEmbedded(LIBERATION_SANS_TTF, fontPx = 72f)) {
                "embedded font catalog should open at 72 px"
            }
            text.use { hud ->
                val lines = statusText.map { line -> hud.layoutLine(line) }
                HudRenderer(session.device, session.queue, hud).use { renderer ->
                    val floats = renderer.buildVertices(lines, baseX = 96f, baseY = 240f, lineHeight = 120f)
                    val expected = statusText.sumOf { line -> line.count { it != ' ' } }
                    assertEquals(expected, floats.size / 24, "every non-space glyph of the status text should place in the atlas")
                }
            }
        }
    }

    /** Draws one "8" through a [GlyphOverlay] at [scale] and returns the first lit pixel (column, row). */
    private suspend fun checkOverlayOrigin(scale: Float): Pair<Int, Int> = runBlocking {
        withDawnSession(DawnSessionOptions(startupTimeout = 60.seconds)) { session ->
            session.device.createTexture(
                TextureDescriptor(
                    size = Extent3D(width.toUInt(), height.toUInt()),
                    format = GPUTextureFormat.RGBA8Unorm,
                    usage = GPUTextureUsage.RenderAttachment or GPUTextureUsage.CopySrc,
                ),
            ).use { target ->
                target.createView().use { view ->
                    GlyphOverlay(session.device, session.queue, GPUTextureFormat.RGBA8Unorm, scale = scale).use { overlay ->
                        overlay.update("8")
                        val staging = session.device.createBuffer(
                            BufferDescriptor(
                                (width * 4).toULong() * height.toULong(),
                                GPUBufferUsage.CopyDst or GPUBufferUsage.MapRead,
                            ),
                        )
                        staging.use {
                            val encoder = session.device.createCommandEncoder()
                            try {
                                overlay.draw(encoder, Frame(view, width, height))
                                encoder.copyTextureToBuffer(
                                    TexelCopyTextureInfo(target),
                                    TexelCopyBufferInfo(staging, bytesPerRow = (width * 4).toUInt(), rowsPerImage = height.toUInt()),
                                    Extent3D(width.toUInt(), height.toUInt()),
                                )
                                encoder.finish().use { finished ->
                                    session.queue.submit(listOf(finished))
                                }
                            } finally {
                                encoder.close()
                            }
                            staging.mapAsync(GPUMapMode.Read).getOrThrow()
                            val rgba = try {
                                staging.getMappedRange().toByteArray()
                            } finally {
                                staging.unmap()
                            }
                            var minColumn = width
                            var minRow = height
                            for (row in 0 until height) {
                                for (column in 0 until width) {
                                    if (rgba[(row * width + column) * 4] > 96) {
                                        if (column < minColumn) minColumn = column
                                        if (row < minRow) minRow = row
                                    }
                                }
                            }
                            assertTrue(minColumn < width && minRow < height, "the overlay should leave lit pixels")
                            minColumn to minRow
                        }
                    }
                }
            }
        }
    }
}
