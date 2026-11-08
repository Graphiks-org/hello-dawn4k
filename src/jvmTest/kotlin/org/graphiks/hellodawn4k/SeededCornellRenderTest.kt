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
    private val h = HeadlessCornell.HEIGHT

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
