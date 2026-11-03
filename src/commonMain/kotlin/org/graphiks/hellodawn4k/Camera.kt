package org.graphiks.hellodawn4k

import kotlin.math.cos
import kotlin.math.sin

/**
 * Orthonormal camera basis shared by the WGSL shader, the CPU reference and the orbit
 * controls: a ray through a pixel is `normalize(right * ndc.x * tan * aspect + up * ndc.y * tan + forward)`.
 */
class CameraBasis(val position: V3, val forward: V3, val tanHalfFov: Float) {
    val right: V3 = forward.cross(WORLD_UP).normalized()
    val up: V3 = right.cross(forward).normalized()

    /** 16 floats matching the shader's Camera uniform; forward.w carries the fov. */
    fun toUniform(): FloatArray = floatArrayOf(
        position.x, position.y, position.z, 0f,
        right.x, right.y, right.z, 0f,
        up.x, up.y, up.z, 0f,
        forward.x, forward.y, forward.z, tanHalfFov,
    )

    companion object {
        val WORLD_UP = V3(0f, 1f, 0f)

        /** The classic headless framing: fixed position with a slight upward tilt. */
        fun classic(tanHalfFov: Float = 0.42f, lookUp: Float = 0.12f): CameraBasis =
            CameraBasis(V3(0f, 1.05f, 2.8f), V3(0f, lookUp, -1f).normalized(), tanHalfFov)
    }
}

/**
 * Turntable orbit around a fixed target: drag to rotate, scroll to zoom.
 * Bounds keep the camera in the interesting region around the open front face.
 */
class OrbitCamera(
    var azimuth: Float = 0f,
    var elevation: Float = 0.10f,
    var radius: Float = 3.1f,
    val target: V3 = V3(0f, 1.25f, 0f),
    val tanHalfFov: Float = 0.30f,
) {
    /** Drag deltas in pixels; right orbits right, up raises the camera. */
    fun drag(dx: Double, dy: Double) {
        azimuth = (azimuth + (dx * 0.005).toFloat()).coerceIn(-1.4f, 1.4f)
        elevation = (elevation - (dy * 0.005).toFloat()).coerceIn(-0.26f, 0.87f)
    }

    fun zoom(delta: Double) {
        radius = (radius * (1.0 - 0.1 * delta)).toFloat().coerceIn(1.6f, 8.0f)
    }

    /** Pinch zoom: ratio > 1 (fingers apart) moves closer. */
    fun zoomRatio(ratio: Double) {
        radius = (radius / ratio).toFloat().coerceIn(1.6f, 8.0f)
    }

    fun reset() {
        azimuth = 0f
        elevation = 0.10f
        radius = 3.1f
    }

    fun basis(): CameraBasis {
        val position = V3(
            target.x + radius * (cos(elevation) * sin(azimuth)),
            target.y + radius * sin(elevation),
            target.z + radius * (cos(elevation) * cos(azimuth)),
        )
        return CameraBasis(position, (target - position).normalized(), tanHalfFov)
    }
}
