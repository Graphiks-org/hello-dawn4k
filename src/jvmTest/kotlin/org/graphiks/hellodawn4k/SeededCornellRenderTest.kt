package org.graphiks.hellodawn4k

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Headless regression suite over the real renderer: a seeded Dawn session renders the scene
 * offscreen and the assertions are structural or statistical — portable across GPUs, unlike a
 * golden byte hash. Covers the historical bugs: frozen accumulation (RNG frame correlation)
 * and the never-written camera uniform that blackened the raytracer.
 */
class SeededCornellRenderTest {
    private val w = HeadlessCornell.WIDTH

    @Test
    fun rasterizerAccumulatesPhotons() {
        val result = HeadlessCornell.render(frames = 30, seed = 42, mode = RenderMode.RASTERIZER)
        assertTrue(result.photonsEmitted > 0.0, "photons emitted: ${result.photonsEmitted}")
    }

    @Test
    fun rasterizerPaintsTheWallsTheirColors() {
        val result = HeadlessCornell.render(frames = 30, seed = 42, mode = RenderMode.RASTERIZER)
        val leftRed = HeadlessCornell.channelMean(result.rgba, 0, 100, w / 4, 300, 0)
        val leftGreen = HeadlessCornell.channelMean(result.rgba, 0, 100, w / 4, 300, 1)
        val rightGreen = HeadlessCornell.channelMean(result.rgba, 3 * w / 4, 100, w, 300, 1)
        val rightRed = HeadlessCornell.channelMean(result.rgba, 3 * w / 4, 100, w, 300, 0)
        assertTrue(leftRed > leftGreen * 1.15, "left wall should be red-dominant: R=$leftRed G=$leftGreen")
        assertTrue(rightGreen > rightRed * 1.15, "right wall should be green-dominant: R=$rightRed G=$rightGreen")
    }

    @Test
    fun raytracerReflectsTheRedWallOnTheTallBox() {
        val rasterized = HeadlessCornell.render(frames = 30, seed = 42, mode = RenderMode.RASTERIZER)
        val raytraced = HeadlessCornell.render(frames = 30, seed = 42, mode = RenderMode.RAYTRACER)
        // The tall box's left face mirrors the red wall: 5% reflected light
        // adds red, which the rasterizer does not have.
        val rasterRed = HeadlessCornell.channelMean(rasterized.rgba, 320, 280, 375, 420, 0)
        val tracedRed = HeadlessCornell.channelMean(raytraced.rgba, 320, 280, 375, 420, 0)
        assertTrue(tracedRed > rasterRed + 4.0, "the tall box face should catch the red wall: raster=$rasterRed traced=$tracedRed")

        // First frame, lightmap still empty: the value must stay valid (no NaN).
        val firstFrame = HeadlessCornell.render(frames = 1, seed = 42, mode = RenderMode.RAYTRACER)
        val firstRed = HeadlessCornell.channelMean(firstFrame.rgba, 320, 280, 375, 420, 0)
        assertTrue(firstRed >= 0.0 && firstRed < 255.0, "first traced frame must stay valid: $firstRed")
    }

    /**
     * Both views share geometry, lightmap and tonemap; they differ by design
     * through the sample's reflection term (5 rays mixed at 5%), worth a
     * measured MAE of ~5.2 on this wall. The threshold pins that remainder
     * (a broken lightmap, camera or tonemap blows far past it).
     */
    @Test
    fun rasterizerMatchesRaytracerOnTheRedWall() {
        val rasterized = HeadlessCornell.render(frames = 30, seed = 42, mode = RenderMode.RASTERIZER)
        val raytraced = HeadlessCornell.render(frames = 30, seed = 42, mode = RenderMode.RAYTRACER)
        var absoluteError = 0L
        var pixelCount = 0
        for (y in 100 until 300) {
            for (x in 20 until w / 4) {
                val pixel = (y * w + x) * 4
                for (channel in 0..2) {
                    absoluteError += kotlin.math.abs(
                        (rasterized.rgba[pixel + channel].toInt() and 0xff) -
                            (raytraced.rgba[pixel + channel].toInt() and 0xff),
                    )
                    pixelCount++
                }
            }
        }
        val meanAbsoluteError = absoluteError.toDouble() / pixelCount
        assertTrue(meanAbsoluteError < 11.0, "red wall rasterizer/raytracer MAE=$meanAbsoluteError")
    }

    @Test
    fun accumulationReducesNoise() {
        val afterOne = HeadlessCornell.render(frames = 1, seed = 42, mode = RenderMode.RASTERIZER)
        val afterThirty = HeadlessCornell.render(frames = 30, seed = 42, mode = RenderMode.RASTERIZER)
        val noise1 = meanLocalNoise(afterOne.rgba, w, 150, 100, 450, 300)
        val noise30 = meanLocalNoise(afterThirty.rgba, w, 150, 100, 450, 300)
        assertTrue(noise30 < noise1 * 0.75, "noise should drop with accumulation: 1f=$noise1 30f=$noise30")
    }

    @Test
    fun raytracerRendersTheScene() {
        val result = HeadlessCornell.render(frames = 30, seed = 42, mode = RenderMode.RAYTRACER)
        assertTrue(result.photonsEmitted > 0.0, "photons emitted: ${result.photonsEmitted}")
        // The back wall between the boxes is lit: with the camera uniform written, the raytracer
        // cannot be black (the regression that motivated this test).
        val backLuma = HeadlessCornell.channelMean(result.rgba, 230, 240, 410, 300, 0) * 0.299 +
            HeadlessCornell.channelMean(result.rgba, 230, 240, 410, 300, 1) * 0.587 +
            HeadlessCornell.channelMean(result.rgba, 230, 240, 410, 300, 2) * 0.114
        assertTrue(backLuma > 8.0, "back wall should be lit, luma=$backLuma")
    }
}
