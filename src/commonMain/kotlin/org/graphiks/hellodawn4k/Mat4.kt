package org.graphiks.hellodawn4k

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** Column-major 4x4 matrices (WGSL mat4x4f layout), just what the rasterizer MVP needs. */
object Mat4 {
    fun identity(): FloatArray = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f,
    )

    /** Standard OpenGL-style perspective; column-major. */
    fun perspective(fovYRadians: Float, aspect: Float, near: Float, far: Float): FloatArray {
        val f = 1f / tan(fovYRadians / 2f)
        val nf = 1f / (near - far)
        return floatArrayOf(
            f / aspect, 0f, 0f, 0f,
            0f, f, 0f, 0f,
            0f, 0f, (far + near) * nf, -1f,
            0f, 0f, 2f * far * near * nf, 0f,
        )
    }

    /** Standard right-handed lookAt; column-major. */
    fun lookAt(eye: V3, center: V3, up: V3): FloatArray {
        val zAxis = (eye - center).normalized()
        val xAxis = up.cross(zAxis).normalized()
        val yAxis = zAxis.cross(xAxis)
        return floatArrayOf(
            xAxis.x, yAxis.x, zAxis.x, 0f,
            xAxis.y, yAxis.y, zAxis.y, 0f,
            xAxis.z, yAxis.z, zAxis.z, 0f,
            -xAxis.dot(eye), -yAxis.dot(eye), -zAxis.dot(eye), 1f,
        )
    }

    /** this x other, applied as `other` first` (column-major multiply). */
    fun multiply(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(16)
        for (column in 0 until 4) {
            for (row in 0 until 4) {
                var sum = 0f
                for (k in 0 until 4) {
                    sum += a[k * 4 + row] * b[column * 4 + k]
                }
                out[column * 4 + row] = sum
            }
        }
        return out
    }
}
