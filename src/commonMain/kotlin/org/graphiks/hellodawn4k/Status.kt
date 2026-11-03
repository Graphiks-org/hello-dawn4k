package org.graphiks.hellodawn4k

import kotlin.math.PI
import kotlin.math.roundToInt

/**
 * Shared status lines for the hosts that display text natively (mobile labels,
 * desktop HUD): framerate, accumulated photons, mode and camera state.
 * Formatting stays in common Kotlin — no String.format on every target.
 */
fun statusLines(fps: Int, renderer: CornellRenderer, mode: RenderMode, camera: OrbitCamera): List<String> {
    val degrees = { radians: Float -> (radians * 180f / PI.toFloat()).roundToInt() }
    return listOf(
        "$fps fps · ${(renderer.photonsEmitted / 1_000_000.0).toInt()}M photons · " +
            if (mode == RenderMode.RASTERIZER) "rasterizer" else "raytracer",
        "az ${degrees(camera.azimuth)}°  el ${degrees(camera.elevation)}°  zoom ${camera.radius.times(10f).roundToInt() / 10f}",
    )
}
