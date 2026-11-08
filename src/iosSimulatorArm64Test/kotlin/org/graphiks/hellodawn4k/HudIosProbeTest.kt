package org.graphiks.hellodawn4k

import org.graphiks.kalligraphie.Kalligraphie
import org.graphiks.kalligraphie.api.FontCatalogSnapshot
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontSourceProvenance
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.OutlineProfile
import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Walks every silently-failing stage of the HUD glyph chain on the iOS simulator:
 * the app's overlay is built through the same [HudText] facade, and any stage that
 * refuses elsewhere would come back as an empty HUD. Each assertion names its
 * stage; the first failure is the culprit. The font runs at 3x density (54 px)
 * to match nativeScale on @3x displays.
 */
class HudIosProbeTest {
    private val fontPx = 54f // 18 px × 3 (nativeScale)

    @Test
    fun catalogStagesWalkOnIos() {
        val opened = Kalligraphie.embedded(LIBERATION_SANS_TTF, FontSourceProvenance("Liberation Sans"))
        val catalog = assertNotNull(
            (opened as? FontOperationResult.Success<*>)?.value as? FontCatalogSnapshot,
            "stage: embedded catalog",
        )
        val requirements = FontAccessRequirementsSnapshot.renderable(
            OutlineProfile(maxBytes = 1_000_000, maxContours = 16_384, maxPoints = 1_000_000, maxCompositeDepth = 32, maxCompositeComponents = 16_384),
        )
        val record = assertNotNull(
            catalog.faces.firstOrNull { it.metadata.familyName == "Liberation Sans" && it.metadata.styleName == "Regular" },
            "stage: face record",
        )
        assertNotNull(catalog.openAssetResolver().valueOrNull(), "stage: asset resolver")
        assertNotNull(catalog.resolveFace(record.id, requirements).valueOrNull(), "stage: resolveFace (outline materialization)")
        assertNotNull(
            catalog.resolveFace(record.id, requirements).valueOrNull()!!
                .instantiate(FontInstanceDescriptor(LayoutUnit(fontPx))).valueOrNull(),
            "stage: instantiate at $fontPx px",
        )
    }

    @Test
    fun facadeShapesAndRasterizesOnIos() {
        val text = assertNotNull(HudText.openEmbedded(LIBERATION_SANS_TTF, fontPx = fontPx), "stage: HudText.openEmbedded at $fontPx px")
        text.use {
            val glyphs = it.layoutLine("60 fps · 0M photons · rasterizer")
            assertTrue(glyphs.isNotEmpty(), "stage: shaping positioned glyphs")
            val a8 = assertNotNull(it.rasterize(glyphs.first().outline), "stage: CPU rasterization at $fontPx px/em")
            assertTrue(a8.width > 0 && a8.height > 0, "stage: coverage non-empty (${a8.width}x${a8.height})")
        }
    }

    private fun <T : Any> FontOperationResult<T>.valueOrNull(): T? =
        (this as? FontOperationResult.Success<T>)?.value
}
