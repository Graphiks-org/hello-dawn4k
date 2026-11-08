package org.graphiks.hellodawn4k

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The HUD glyph chain on the iOS runtime, at the density the app asks for
 * (18 px × nativeScale = 54 px on @3x). Every refusal inside [HudText] comes
 * back as an empty line — a silently missing HUD — rather than an error, so
 * opening, shaping and rasterization are pinned here on the klibs the iOS
 * target actually links (JVM tests never load them).
 */
class HudGlyphChainIosTest {
    @Test
    fun shapesAndRasterizesAt3xDensity() {
        val text = assertNotNull(HudText.openEmbedded(LIBERATION_SANS_TTF, fontPx = 54f), "embedded catalog should open at 54 px")
        text.use {
            val glyphs = it.layoutLine("60 fps · 0M photons · rasterizer")
            assertTrue(glyphs.isNotEmpty(), "shaping should position glyphs on iOS")
            val coverage = assertNotNull(it.rasterize(glyphs.first().outline), "CPU rasterization at 54 px/em")
            assertTrue(coverage.width > 0 && coverage.height > 0, "coverage should be non-empty (${coverage.width}x${coverage.height})")
        }
    }
}
