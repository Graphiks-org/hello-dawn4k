package org.graphiks.hellodawn4k

import javax.imageio.ImageIO

/**
 * Compares local noise between two unfiltered RGBA PNGs: mean 3x3 luma standard
 * deviation over a back-wall region. Used to prove that progressive accumulation
 * actually converges (it stalled at samples-per-frame before the RNG frame-mix fix).
 */
fun main(args: Array<String>) {
    require(args.size == 2) { "usage: NoiseMetric <a.png> <b.png>" }
    val region = intArrayOf(150, 100, 450, 300)
    for (path in args) {
        val luma = luma(readRgba(path))
        val width = luma.second
        val values = luma.first
        var total = 0.0
        var count = 0
        for (y in region[1] + 1 until region[3] - 1) {
            for (x in region[0] + 1 until region[2] - 1) {
                var sum = 0.0
                var sumSquares = 0.0
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val v = values[(y + dy) * width + (x + dx)]
                        sum += v
                        sumSquares += v * v
                    }
                }
                val mean = sum / 9.0
                total += kotlin.math.sqrt(kotlin.math.max(0.0, (sumSquares - 9.0 * mean * mean) / 9.0))
                count++
            }
        }
        println("${path.substringAfterLast('/')}: mean local noise = ${"%.3f".format(total / count)}")
    }
}

/** Reads an RGBA8 PNG, preferring ImageIO and falling back to a minimal decoder for our own unfiltered files. */
private fun readRgba(path: String): Pair<Array<DoubleArray>, Int> {
    val image = ImageIO.read(java.io.File(path))
    val width = image.width
    val height = image.height
    val rows = Array(height) { DoubleArray(width) }
    for (y in 0 until height) {
        for (x in 0 until width) {
            val argb = image.getRGB(x, y)
            rows[y][x] = 0.299 * ((argb shr 16) and 255) + 0.587 * ((argb shr 8) and 255) + 0.114 * (argb and 255)
        }
    }
    return rows to width
}

private fun luma(rowsWithWidth: Pair<Array<DoubleArray>, Int>): Pair<DoubleArray, Int> {
    val (rows, width) = rowsWithWidth
    return DoubleArray(rows.size * width) { index -> rows[index / width][index % width] } to width
}
