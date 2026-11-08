package org.graphiks.hellodawn4k

/**
 * Mean local noise: the average 3x3 luma standard deviation over a region. Higher means a
 * noisier (less converged) progressive-accumulation image. Pure math over an RGBA8 buffer so
 * the assertion is independent of any encoder or GPU. Region = [x0, y0, x1, y1).
 */
fun meanLocalNoise(rgba: ByteArray, width: Int, x0: Int, y0: Int, x1: Int, y1: Int): Double {
    fun luma(index: Int): Double {
        val o = index * 4
        return 0.299 * (rgba[o].toInt() and 0xff) + 0.587 * (rgba[o + 1].toInt() and 0xff) + 0.114 * (rgba[o + 2].toInt() and 0xff)
    }

    var total = 0.0
    var count = 0
    for (y in y0 + 1 until y1 - 1) {
        for (x in x0 + 1 until x1 - 1) {
            var sum = 0.0
            var sumSquares = 0.0
            for (dy in -1..1) {
                for (dx in -1..1) {
                    val v = luma((y + dy) * width + (x + dx))
                    sum += v
                    sumSquares += v * v
                }
            }
            val mean = sum / 9.0
            total += kotlin.math.sqrt(kotlin.math.max(0.0, (sumSquares - 9.0 * mean * mean) / 9.0))
            count++
        }
    }
    return total / count
}
