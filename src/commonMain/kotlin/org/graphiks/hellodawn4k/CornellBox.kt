package org.graphiks.hellodawn4k

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
/** Tiny immutable 3-vector used to author the scene. */
data class V3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = V3(x * s, y * s, z * s)
    fun dot(o: V3) = x * o.x + y * o.y + z * o.z
    fun cross(o: V3) = V3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun normalized(): V3 {
        val length = sqrt(dot(this))
        return V3(x / length, y / length, z / length)
    }
}

/** The flattened scene: quad float data plus the index of the single emissive quad. */
class Scene(val quadData: FloatArray, val quadCount: Int, val lightIndex: Int) {
    companion object {
        /** Floats per quad: origin, edge1, edge2, albedo, emissive, normal+area (6 x vec4). */
        const val FLOATS_PER_QUAD = 24
    }
}

/**
 * The classic Cornell box: five walls, a ceiling light and two rotated boxes.
 * Room extents are x [-1, 1], y [0, 2], z [-1, 1]; the open front faces the camera.
 */
object CornellBox {
    private val WHITE = V3(0.73f, 0.73f, 0.73f)
    private val RED = V3(0.63f, 0.065f, 0.05f)
    private val GREEN = V3(0.14f, 0.45f, 0.091f)
    private val BLACK = V3(0f, 0f, 0f)
    private val LIGHT_EMISSION = V3(13f, 11.5f, 9f)

    fun scene(): Scene {
        val floats = ArrayList<Float>(18 * Scene.FLOATS_PER_QUAD)
        var lightIndex = -1

        fun quad(origin: V3, edge1: V3, edge2: V3, albedo: V3, emissive: V3 = BLACK) {
            val cross = edge1.cross(edge2)
            val normal = cross.normalized()
            if (emissive != BLACK) lightIndex = floats.size / Scene.FLOATS_PER_QUAD
            floats.addAll(listOf(
                origin.x, origin.y, origin.z, 0f,
                edge1.x, edge1.y, edge1.z, 0f,
                edge2.x, edge2.y, edge2.z, 0f,
                albedo.x, albedo.y, albedo.z, 0f,
                emissive.x, emissive.y, emissive.z, 0f,
                normal.x, normal.y, normal.z, sqrt(cross.dot(cross)),
            ))
        }

        /** Six faces of a box rotated by [angleY] radians around its vertical axis. */
        fun box(center: V3, half: V3, angleY: Float, albedo: V3) {
            val cos = cos(angleY)
            val sin = sin(angleY)
            fun rotateY(v: V3) = V3(cos * v.x + sin * v.z, v.y, -sin * v.x + cos * v.z)
            val axes = listOf(rotateY(V3(half.x, 0f, 0f)), rotateY(V3(0f, half.y, 0f)), rotateY(V3(0f, 0f, half.z)))
            for (i in axes.indices) {
                val axis = axes[i]
                val (first, second) = axes.filterIndexed { index, _ -> index != i }
                val edge1 = first * 2f
                val edge2 = second * 2f
                quad(center + axis - first - second, edge1, edge2, albedo)
                quad(center - axis - first - second, edge1, edge2, albedo)
            }
        }

        quad(V3(-1f, 0f, 1f), V3(2f, 0f, 0f), V3(0f, 0f, -2f), WHITE)   // floor
        quad(V3(-1f, 2f, -1f), V3(2f, 0f, 0f), V3(0f, 0f, 2f), WHITE)   // ceiling
        quad(V3(-1f, 0f, -1f), V3(2f, 0f, 0f), V3(0f, 2f, 0f), WHITE)   // back wall
        quad(V3(-1f, 0f, -1f), V3(0f, 0f, 2f), V3(0f, 2f, 0f), RED)     // left wall
        quad(V3(1f, 0f, -1f), V3(0f, 0f, 2f), V3(0f, 2f, 0f), GREEN)    // right wall
        quad(V3(-0.25f, 1.99f, -0.25f), V3(0.5f, 0f, 0f), V3(0f, 0f, 0.5f), BLACK, LIGHT_EMISSION)
        box(V3(0.33f, 0.6f, -0.22f), V3(0.28f, 0.6f, 0.28f), Math.toRadians(33.0).toFloat(), WHITE)
        box(V3(-0.34f, 0.3f, 0.30f), V3(0.28f, 0.3f, 0.28f), Math.toRadians(-18.0).toFloat(), WHITE)

        check(lightIndex >= 0) { "the Cornell box needs exactly one emissive quad" }
        return Scene(floats.toFloatArray(), floats.size / Scene.FLOATS_PER_QUAD, lightIndex)
    }
}
