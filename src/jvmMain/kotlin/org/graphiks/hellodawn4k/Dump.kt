package org.graphiks.hellodawn4k

import org.graphiks.webgpu.ArrayBuffer
import org.graphiks.webgpu.GPUBufferUsage
import org.graphiks.webgpu.GPUCompareFunction
import org.graphiks.webgpu.GPUIndexFormat
import org.graphiks.webgpu.GPUMapMode
import org.graphiks.webgpu.GPULoadOp
import org.graphiks.webgpu.GPUStoreOp
import org.graphiks.webgpu.GPUTexture
import org.graphiks.webgpu.GPUTextureFormat
import org.graphiks.webgpu.GPUTextureUsage
import org.graphiks.webgpu.GPUTextureView
import org.graphiks.webgpu.GPUVertexFormat
import org.graphiks.webgpu.descriptors.BindGroupDescriptor
import org.graphiks.webgpu.descriptors.BindGroupEntry
import org.graphiks.webgpu.descriptors.BufferDescriptor
import org.graphiks.webgpu.descriptors.Color
import org.graphiks.webgpu.descriptors.ColorTargetState
import org.graphiks.webgpu.descriptors.DepthStencilState
import org.graphiks.webgpu.descriptors.Extent3D
import org.graphiks.webgpu.descriptors.FragmentState
import org.graphiks.webgpu.descriptors.PrimitiveState
import org.graphiks.webgpu.descriptors.RenderPassColorAttachment
import org.graphiks.webgpu.descriptors.RenderPassDepthStencilAttachment
import org.graphiks.webgpu.descriptors.RenderPassDescriptor
import org.graphiks.webgpu.descriptors.RenderPipelineDescriptor
import org.graphiks.webgpu.descriptors.TexelCopyBufferInfo
import org.graphiks.webgpu.descriptors.TexelCopyTextureInfo
import org.graphiks.webgpu.descriptors.TextureDescriptor
import org.graphiks.webgpu.descriptors.VertexAttribute
import org.graphiks.webgpu.descriptors.VertexBufferLayout
import org.graphiks.webgpu.descriptors.VertexState
import java.nio.file.Files
import java.nio.file.Path

/**
 * Desktop-only smoke dumps: renders the current view into an offscreen RGBA8Unorm texture
 * (swapchain pipelines cannot write there, so a twin pipeline is built) and writes exactly
 * what the window shows to [path], row padding already absent at 640 px. The rasterizer
 * mode draws its mesh there directly; the raytracer goes through the radiance buffer and
 * the tonemapping present pass.
 */
suspend fun CornellRenderer.dumpPresentedFrame(
    path: String,
    mode: RenderMode,
    orbit: OrbitCamera,
    hud: HudRenderer?,
    hudVertices: FloatArray,
) {
    device.createTexture(
        TextureDescriptor(
            size = Extent3D(WIDTH.toUInt(), HEIGHT.toUInt()),
            format = GPUTextureFormat.RGBA8Unorm,
            usage = GPUTextureUsage.RenderAttachment or GPUTextureUsage.CopySrc,
        ),
    ).use { target ->
        device.createBuffer(
            BufferDescriptor(2560uL * HEIGHT.toULong(), GPUBufferUsage.CopyDst or GPUBufferUsage.MapRead),
        ).use { staging ->
            if (mode == RenderMode.RASTERIZER) {
                dumpRaster(target, orbit, hud, hudVertices)
            } else {
                dumpPresent(target, hud, hudVertices)
            }
            val encoder = device.createCommandEncoder()
            try {
                encoder.copyTextureToBuffer(
                    TexelCopyTextureInfo(target),
                    TexelCopyBufferInfo(staging, bytesPerRow = 2560u, rowsPerImage = HEIGHT.toUInt()),
                    Extent3D(WIDTH.toUInt(), HEIGHT.toUInt()),
                )
                encoder.finish().use { queue.submit(listOf(it)) }
            } finally {
                encoder.close()
            }
            staging.mapAsync(GPUMapMode.Read).getOrThrow()
            val rgba = try {
                staging.getMappedRange().toByteArray()
            } finally {
                staging.unmap()
            }
            Files.write(Path.of(path), Png.encode(WIDTH, HEIGHT, rgba))
        }
    }
}

/** Offscreen twin of the raster pass for dumps (RGBA8 target, dedicated depth texture). */
private class DumpRasterCache(
    val pipeline: org.graphiks.webgpu.GPURenderPipeline,
    val binding: org.graphiks.webgpu.GPUBindGroup,
    val depthView: GPUTextureView,
    val depthTexture: GPUTexture, // the view does not keep the texture alive in this backend
)

private var dumpRasterCache: DumpRasterCache? = null

private suspend fun CornellRenderer.dumpRaster(target: GPUTexture, orbit: OrbitCamera, hud: HudRenderer?, hudVertices: FloatArray) {
    target.createView().use { view ->
        val cache = dumpRasterCache ?: run {
            val dumpPipeline = device.createRenderPipeline(
                RenderPipelineDescriptor(
                    vertex = VertexState(
                        module = rasterShader,
                        entryPoint = "vs_main",
                        buffers = listOf(
                            VertexBufferLayout(
                                arrayStride = 40uL,
                                attributes = listOf(
                                    VertexAttribute(GPUVertexFormat.Float32x4, 0uL, 0u),
                                    VertexAttribute(GPUVertexFormat.Float32x3, 16uL, 1u),
                                    VertexAttribute(GPUVertexFormat.Float32x3, 28uL, 2u),
                                ),
                            ),
                        ),
                    ),
                    fragment = FragmentState(
                        module = rasterShader,
                        entryPoint = "fs_main",
                        targets = listOf(ColorTargetState(GPUTextureFormat.RGBA8Unorm)),
                    ),
                    primitive = PrimitiveState(),
                    depthStencil = DepthStencilState(
                        format = GPUTextureFormat.Depth24Plus,
                        depthWriteEnabled = true,
                        depthCompare = GPUCompareFunction.Less,
                    ),
                ),
            )
            dumpPipeline.getBindGroupLayout(0u).use { layout ->
                val binding = device.createBindGroup(
                    BindGroupDescriptor(
                        layout,
                        listOf(BindGroupEntry(0u, rasterUniforms), BindGroupEntry(1u, lightmapView), BindGroupEntry(2u, lightmapSampler)),
                    ),
                )
                val depthTexture = device.createTexture(
                    TextureDescriptor(
                        size = Extent3D(WIDTH.toUInt(), HEIGHT.toUInt()),
                        format = GPUTextureFormat.Depth24Plus,
                        usage = GPUTextureUsage.RenderAttachment,
                    ),
                )
                DumpRasterCache(dumpPipeline, binding, depthTexture.createView(), depthTexture)
                    .also { dumpRasterCache = it }
            }
        }
        val basis = orbit.basis()
        val fovY = 2f * kotlin.math.atan(orbit.tanHalfFov)
        val mvp = Mat4.multiply(
            Mat4.perspective(fovY, WIDTH.toFloat() / HEIGHT, 0.5f, 100f),
            Mat4.lookAt(basis.position, orbit.target, V3(0f, 1f, 0f)),
        )
        queue.writeBuffer(rasterUniforms, 0uL, ArrayBuffer.of(mvp))
        val encoder = device.createCommandEncoder()
        try {
            val pass = encoder.beginRenderPass(
                RenderPassDescriptor(
                    colorAttachments = listOf(
                        RenderPassColorAttachment(
                            view,
                            loadOp = GPULoadOp.Clear,
                            storeOp = GPUStoreOp.Store,
                            clearValue = Color(0.0, 0.0, 0.0, 1.0),
                        ),
                    ),
                    depthStencilAttachment = RenderPassDepthStencilAttachment(
                        view = cache.depthView,
                        depthClearValue = 1.0f,
                        depthLoadOp = GPULoadOp.Clear,
                        depthStoreOp = GPUStoreOp.Store,
                    ),
                ),
            )
            try {
                pass.setPipeline(cache.pipeline)
                pass.setBindGroup(0u, cache.binding)
                pass.setVertexBuffer(0u, vertexBuffer)
                pass.setIndexBuffer(indexBuffer, GPUIndexFormat.Uint16)
                pass.drawIndexed(meshIndices.size.toUInt())
            } finally {
                pass.end()
            }
            if (hud != null && hudVertices.isNotEmpty()) {
                drawHudOver(encoder, view, hud, hudVertices)
            }
            encoder.finish().use { queue.submit(listOf(it)) }
            queue.onSubmittedWorkDone().getOrThrow()
        } finally {
            encoder.close()
        }
    }
}

private suspend fun CornellRenderer.dumpPresent(target: GPUTexture, hud: HudRenderer?, hudVertices: FloatArray) {
    device.createRenderPipeline(
        RenderPipelineDescriptor(
            vertex = VertexState(module = presentShader, entryPoint = "vs"),
            fragment = FragmentState(
                module = presentShader,
                entryPoint = "fs",
                targets = listOf(ColorTargetState(GPUTextureFormat.RGBA8Unorm)),
            ),
        ),
    ).use { dumpPipeline ->
        dumpPipeline.getBindGroupLayout(0u).use { dumpLayout ->
            device.createBindGroup(
                BindGroupDescriptor(dumpLayout, listOf(BindGroupEntry(0u, radiance), BindGroupEntry(1u, dims))),
            ).use { dumpBinding ->
                target.createView().use { view ->
                    val encoder = device.createCommandEncoder()
                    try {
                        val pass = encoder.beginRenderPass(RenderPassDescriptor(colorAttachments = listOf(
                            RenderPassColorAttachment(
                                view,
                                loadOp = GPULoadOp.Clear,
                                storeOp = GPUStoreOp.Store,
                                clearValue = Color(0.0, 0.0, 0.0, 1.0),
                            ),
                        )))
                        try {
                            pass.setPipeline(dumpPipeline)
                            pass.setBindGroup(0u, dumpBinding)
                            pass.draw(3u)
                        } finally {
                            pass.end()
                        }
                        if (hud != null && hudVertices.isNotEmpty()) {
                            drawHudOver(encoder, view, hud, hudVertices)
                        }
                        encoder.finish().use { queue.submit(listOf(it)) }
                        queue.onSubmittedWorkDone().getOrThrow()
                    } finally {
                        encoder.close()
                    }
                }
            }
        }
    }
}

private fun drawHudOver(encoder: org.graphiks.webgpu.GPUCommandEncoder, view: GPUTextureView, hud: HudRenderer, hudVertices: FloatArray) {
    val pass = encoder.beginRenderPass(RenderPassDescriptor(colorAttachments = listOf(
        RenderPassColorAttachment(
            view,
            loadOp = GPULoadOp.Load,
            storeOp = GPUStoreOp.Store,
            clearValue = Color(0.0, 0.0, 0.0, 1.0),
        ),
    )))
    try {
        hud.draw(pass, GPUTextureFormat.RGBA8Unorm, WIDTH, HEIGHT, hudVertices)
    } finally {
        pass.end()
    }
}
