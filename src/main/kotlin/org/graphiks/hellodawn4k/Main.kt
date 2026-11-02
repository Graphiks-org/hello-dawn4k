package org.graphiks.hellodawn4k

import kotlinx.coroutines.runBlocking
import org.graphiks.dawn4k.DawnCommandEncoder
import org.graphiks.dawn4k.toolkit.DawnSessionOptions
import org.graphiks.dawn4k.toolkit.withDawnSession
import org.graphiks.webgpu.ArrayBuffer
import org.graphiks.webgpu.GPUBufferUsage
import org.graphiks.webgpu.GPUMapMode
import org.graphiks.webgpu.descriptors.BindGroupDescriptor
import org.graphiks.webgpu.descriptors.BindGroupEntry
import org.graphiks.webgpu.descriptors.BufferDescriptor
import org.graphiks.webgpu.descriptors.ComputePipelineDescriptor
import org.graphiks.webgpu.descriptors.ProgrammableStage
import org.graphiks.webgpu.descriptors.ShaderModuleDescriptor
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.time.Duration.Companion.seconds

const val WIDTH = 640
const val HEIGHT = 480
const val SAMPLES_PER_PIXEL = 1024
const val WORKGROUP_SIZE = 8

fun main() = runBlocking {
    val scene = CornellBox.scene()
    val wgsl = Path.of("src/main/resources/common.wgsl").readText() + "\n" +
        Path.of("src/main/resources/cornell.wgsl").readText()

    // The scoped session owns bootstrap, the GPU owner thread, progression and shutdown.
    val radiance = withDawnSession(
        DawnSessionOptions(
            startupTimeout = 60.seconds,
            adapterOptions = org.graphiks.webgpu.descriptors.RequestAdapterOptions(
                powerPreference = org.graphiks.webgpu.GPUPowerPreference.HighPerformance,
            ),
        ),
    ) { session ->
        session.run { gpu ->
            val quadBytes = (scene.quadData.size * 4).toULong()
            val radianceBytes = (WIDTH * HEIGHT * 16).toULong()
            gpu.device.createBuffer(BufferDescriptor(quadBytes, GPUBufferUsage.Storage or GPUBufferUsage.CopyDst)).use { quads ->
                gpu.device.createBuffer(BufferDescriptor(48uL, GPUBufferUsage.Uniform or GPUBufferUsage.CopyDst)).use { params ->
                gpu.device.createBuffer(BufferDescriptor(64uL, GPUBufferUsage.Uniform or GPUBufferUsage.CopyDst)).use { camera ->
                    gpu.queue.writeBuffer(camera, 0uL, ArrayBuffer.of(CameraBasis.classic().toUniform()))
                    gpu.device.createBuffer(BufferDescriptor(radianceBytes, GPUBufferUsage.Storage or GPUBufferUsage.CopySrc)).use { radianceBuffer ->
                        gpu.device.createBuffer(BufferDescriptor(radianceBytes, GPUBufferUsage.CopyDst or GPUBufferUsage.MapRead)).use { readback ->
                            gpu.queue.writeBuffer(quads, 0uL, ArrayBuffer.of(scene.quadData))
                            gpu.queue.writeBuffer(params, 0uL, ArrayBuffer.of(uintArrayOf(
                                scene.quadCount.toUInt(),
                                WIDTH.toUInt(),
                                HEIGHT.toUInt(),
                                SAMPLES_PER_PIXEL.toUInt(),
                                scene.lightIndex.toUInt(), 0u, 0u, 0u, // light vec4u, 16-byte aligned
                                1u, 0u, 0u, 0u, // frame: a single-shot render overwrites the buffer
                            )))
                            gpu.device.createShaderModule(ShaderModuleDescriptor(wgsl)).use { shader ->
                                gpu.device.createComputePipeline(ComputePipelineDescriptor(ProgrammableStage(shader, "main"))).use { pipeline ->
                                    pipeline.getBindGroupLayout(0u).use { layout ->
                                        gpu.device.createBindGroup(BindGroupDescriptor(layout, listOf(
                                            BindGroupEntry(0u, quads),
                                            BindGroupEntry(1u, radianceBuffer),
                                            BindGroupEntry(2u, params),
                                            BindGroupEntry(3u, camera),
                                        ))).use { binding ->
                                            (gpu.device.createCommandEncoder() as DawnCommandEncoder).use { encoder ->
                                                encoder.beginComputePass().use { pass ->
                                                    try {
                                                        pass.setPipeline(pipeline)
                                                        pass.setBindGroup(0u, binding)
                                                        pass.dispatchWorkgroups(
                                                            ((WIDTH + WORKGROUP_SIZE - 1) / WORKGROUP_SIZE).toUInt(),
                                                            ((HEIGHT + WORKGROUP_SIZE - 1) / WORKGROUP_SIZE).toUInt(),
                                                        )
                                                    } finally {
                                                        pass.end()
                                                    }
                                                }
                                                encoder.copyBufferToBuffer(radianceBuffer, 0uL, readback, 0uL, radianceBytes)
                                                encoder.finish().use { gpu.queue.submit(listOf(it)) }
                                            }
                                            readback.mapAsync(GPUMapMode.Read).getOrThrow()
                                            try {
                                                readback.getMappedRange().toFloatArray()
                                            } finally {
                                                readback.unmap()
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    }

    Reference.compare(scene, radiance)
    val png = Png.encode(WIDTH, HEIGHT, tonemap(radiance))
    Files.write(Path.of("cornell.png"), png)
    println("Cornell box written to cornell.png (${WIDTH}x${HEIGHT}, $SAMPLES_PER_PIXEL samples per pixel)")
}

/** Linear HDR radiance in, sRGB RGBA8 out: Reinhard tonemap followed by a 2.2 gamma. */
internal fun tonemap(radiance: FloatArray): ByteArray {
    val pixels = radiance.size / 4
    val rgba = ByteArray(pixels * 4)
    for (pixel in 0 until pixels) {
        rgba[pixel * 4] = encodeChannel(radiance[pixel * 4])
        rgba[pixel * 4 + 1] = encodeChannel(radiance[pixel * 4 + 1])
        rgba[pixel * 4 + 2] = encodeChannel(radiance[pixel * 4 + 2])
        rgba[pixel * 4 + 3] = 255.toByte()
    }
    return rgba
}

private fun encodeChannel(linear: Float): Byte {
    val reinhard = (linear / (1f + linear)).coerceAtLeast(0f)
    val gamma = Math.pow(reinhard.toDouble(), 1.0 / 2.2)
    return ((gamma * 255.0) + 0.5).toInt().coerceIn(0, 255).toByte()
}
