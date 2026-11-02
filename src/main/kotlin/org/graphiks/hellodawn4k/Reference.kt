package org.graphiks.hellodawn4k

import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Single-threaded CPU mirror of the WGSL path tracer, used to validate a handful of
 * GPU-rendered pixels numerically: same scene data, same camera, same NEE + cosine
 * sampling strategy. Random streams differ, so radiance is compared within a noise
 * tolerance rather than bit-for-bit.
 */
object Reference {
    // Must mirror the camera the headless render writes into its uniform.
    private val camera = CameraBasis.classic()
    private const val MAX_BOUNCES = 5
    private const val EPSILON = 1e-4

    private class Quad(
        val origin: DoubleArray,
        val e1: DoubleArray,
        val e2: DoubleArray,
        val albedo: DoubleArray,
        val emissive: DoubleArray,
        val normal: DoubleArray,
        val area: Double,
    )

    private fun unpack(scene: Scene): List<Quad> {
        val f = scene.quadData
        return (0 until scene.quadCount).map { q ->
            val o = q * Scene.FLOATS_PER_QUAD
            Quad(
                doubleArrayOf(f[o].toDouble(), f[o + 1].toDouble(), f[o + 2].toDouble()),
                doubleArrayOf(f[o + 4].toDouble(), f[o + 5].toDouble(), f[o + 6].toDouble()),
                doubleArrayOf(f[o + 8].toDouble(), f[o + 9].toDouble(), f[o + 10].toDouble()),
                doubleArrayOf(f[o + 12].toDouble(), f[o + 13].toDouble(), f[o + 14].toDouble()),
                doubleArrayOf(f[o + 16].toDouble(), f[o + 17].toDouble(), f[o + 18].toDouble()),
                doubleArrayOf(f[o + 20].toDouble(), f[o + 21].toDouble(), f[o + 22].toDouble()),
                f[o + 23].toDouble(),
            )
        }
    }

    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    private fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )

    private fun normalized(v: DoubleArray): DoubleArray {
        val length = sqrt(dot(v, v))
        return doubleArrayOf(v[0] / length, v[1] / length, v[2] / length)
    }

    /** Returns (t, quadIndex) of the closest hit, or null when the ray escapes. */
    private fun closest(quads: List<Quad>, ro: DoubleArray, rd: DoubleArray): Pair<Double, Int>? {
        var bestT = Double.MAX_VALUE
        var best = -1
        for ((index, q) in quads.withIndex()) {
            val t = rayQuad(q, ro, rd) ?: continue
            if (t >= EPSILON && t < bestT) {
                bestT = t
                best = index
            }
        }
        return best.takeIf { it >= 0 }?.let { Pair(bestT, it) }
    }

    private fun rayQuad(q: Quad, ro: DoubleArray, rd: DoubleArray): Double? {
        val pvec = cross(rd, q.e2)
        val det = dot(q.e1, pvec)
        val invDet = 1.0 / det
        val tvec = doubleArrayOf(ro[0] - q.origin[0], ro[1] - q.origin[1], ro[2] - q.origin[2])
        val u = dot(tvec, pvec) * invDet
        if (u < 0.0 || u > 1.0) return null
        val qvec = cross(tvec, q.e1)
        val v = dot(rd, qvec) * invDet
        // Parallelogram quad, not a triangle: accept the full u,v unit square.
        if (v < 0.0 || v > 1.0) return null
        val t = dot(q.e2, qvec) * invDet
        return t.takeIf { it >= EPSILON }
    }

    private fun occluded(quads: List<Quad>, ro: DoubleArray, rd: DoubleArray, maxDistance: Double): Boolean {
        for (q in quads) {
            val t = rayQuad(q, ro, rd) ?: continue
            if (t < maxDistance) return true
        }
        return false
    }

    private fun cosineDirection(n: DoubleArray, u1: Double, u2: Double): DoubleArray {
        val radius = sqrt(u1)
        val angle = 2.0 * Math.PI * u2
        val helper = if (kotlin.math.abs(n[1]) < 0.9) doubleArrayOf(0.0, 1.0, 0.0) else doubleArrayOf(1.0, 0.0, 0.0)
        val tangent = normalized(cross(helper, n))
        val bitangent = cross(n, tangent)
        val up = sqrt(kotlin.math.max(0.0, 1.0 - u1))
        return normalized(
            doubleArrayOf(
                tangent[0] * radius * kotlin.math.cos(angle) + bitangent[0] * radius * kotlin.math.sin(angle) + n[0] * up,
                tangent[1] * radius * kotlin.math.cos(angle) + bitangent[1] * radius * kotlin.math.sin(angle) + n[1] * up,
                tangent[2] * radius * kotlin.math.cos(angle) + bitangent[2] * radius * kotlin.math.sin(angle) + n[2] * up,
            )
        )
    }

    private fun cameraRay(pixelX: Int, pixelY: Int, jitterX: Double, jitterY: Double): Pair<DoubleArray, DoubleArray> {
        val ndcX = ((pixelX + jitterX) / WIDTH * 2.0 - 1.0)
        val ndcY = (1.0 - (pixelY + jitterY) / HEIGHT * 2.0)
        val aspect = WIDTH.toDouble() / HEIGHT
        val tan = camera.tanHalfFov.toDouble()
        val dir = doubleArrayOf(
            camera.right.x * ndcX * tan * aspect + camera.up.x * ndcY * tan + camera.forward.x,
            camera.right.y * ndcX * tan * aspect + camera.up.y * ndcY * tan + camera.forward.y,
            camera.right.z * ndcX * tan * aspect + camera.up.z * ndcY * tan + camera.forward.z,
        )
        val origin = doubleArrayOf(
            camera.position.x.toDouble(),
            camera.position.y.toDouble(),
            camera.position.z.toDouble(),
        )
        return origin to normalized(dir)
    }

    private fun traceSample(quads: List<Quad>, lightIndex: Int, pixelX: Int, pixelY: Int, rnd: Random): DoubleArray {
        val (ro, rd) = cameraRay(pixelX, pixelY, rnd.nextDouble(), rnd.nextDouble())

        val color = DoubleArray(3)
        val throughput = DoubleArray(3) { 1.0 }
        val light = quads[lightIndex]

        for (bounce in 0 until MAX_BOUNCES) {
            val hit = closest(quads, ro, rd) ?: break
            val surface = quads[hit.second]
            if (surface.emissive[0] + surface.emissive[1] + surface.emissive[2] > 0.0) {
                if (bounce == 0) {
                    for (c in 0..2) color[c] += throughput[c] * surface.emissive[c]
                }
                break
            }
            val position = DoubleArray(3) { ro[it] + rd[it] * hit.first }
            val normal = surface.normal.copyOf()
            if (dot(normal, rd) > 0.0) for (c in 0..2) normal[c] = -normal[c]

            val lightPoint = DoubleArray(3) {
                light.origin[it] + light.e1[it] * rnd.nextDouble() + light.e2[it] * rnd.nextDouble()
            }
            val toLight = DoubleArray(3) { lightPoint[it] - position[it] }
            val distanceSquared = dot(toLight, toLight)
            val distance = sqrt(distanceSquared)
            val direction = DoubleArray(3) { toLight[it] / distance }
            val cosSurface = dot(normal, direction)
            val cosLight = dot(light.normal, doubleArrayOf(-direction[0], -direction[1], -direction[2]))
            if (cosSurface > 0.0 && cosLight > 0.0) {
                val shadowOrigin = DoubleArray(3) { position[it] + normal[it] * EPSILON }
                if (!occluded(quads, shadowOrigin, direction, distance - 2.0 * EPSILON)) {
                    for (c in 0..2) {
                        color[c] += throughput[c] * surface.albedo[c] * light.emissive[c] *
                            (cosSurface * cosLight * light.area / (Math.PI * distanceSquared))
                    }
                }
            }

            for (c in 0..2) throughput[c] *= surface.albedo[c]
            val newDirection = cosineDirection(normal, rnd.nextDouble(), rnd.nextDouble())
            for (c in 0..2) rd[c] = newDirection[c]
            for (c in 0..2) ro[c] = position[c] + normal[c] * EPSILON

            if (bounce >= 2) {
                val survival = maxOf(throughput[0], throughput[1], throughput[2])
                if (rnd.nextDouble() > survival) break
                for (c in 0..2) throughput[c] /= survival
            }
        }
        return color
    }

    /**
     * Renders a few probe pixels on the CPU and compares them with the GPU radiance.
     * Random streams differ between the two implementations, so means are compared
     * within a sampling-noise tolerance.
     */
    fun compare(scene: Scene, gpuRadiance: FloatArray) {
        val quads = unpack(scene)
        val samples = 4096
        val probes = listOf(
            Triple(160, 120, "mur du fond haut-gauche"),
            Triple(470, 240, "a droite de la grande boite"),
            Triple(320, 400, "sol centre-bas"),
            Triple(320, 60, "plafond"),
        )
        println("-- reference CPU (${samples} spp/px) vs GPU --")
        for ((x, y, label) in probes) {
            // Which surface does the primary ray see? (mirrors the shader's ray generation)
            val (probeOrigin, probeRd) = cameraRay(x, y, 0.5, 0.5)
            val primary = closest(quads, probeOrigin, probeRd)
            val sum = DoubleArray(3)
            val rnd = Random(1234L)
            repeat(samples) {
                val sample = traceSample(quads, scene.lightIndex, x, y, rnd)
                for (c in 0..2) sum[c] += sample[c]
            }
            val cpu = DoubleArray(3) { sum[it] / samples }
            val gpu = DoubleArray(3) { gpuRadiance[(y * WIDTH + x) * 4 + it].toDouble() }
            val noise = 4.0 / sqrt(samples.toDouble()) * maxOf(1.0, cpu.max())
            val ok = (0..2).all { kotlin.math.abs(cpu[it] - gpu[it]) <= noise * 3 }
            println(
                "px($x,$y) $label: primary=${primary?.second ?: "rate"} cpu=[${cpu.contentToString()}] gpu=[${gpu.contentToString()}] -> " +
                    if (ok) "OK" else "ECART"
            )
        }
    }
}
