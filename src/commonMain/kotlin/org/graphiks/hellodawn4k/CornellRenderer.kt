package org.graphiks.hellodawn4k

import org.graphiks.dawn4k.DawnCommandEncoder
import org.graphiks.dawn4k.toolkit.DawnSession
import org.graphiks.dawn4k.toolkit.surface.DawnFrame
import org.graphiks.webgpu.ArrayBuffer
import org.graphiks.webgpu.GPUBufferUsage
import org.graphiks.webgpu.GPUCompareFunction
import org.graphiks.webgpu.GPUFilterMode
import org.graphiks.webgpu.GPUIndexFormat
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
import org.graphiks.webgpu.descriptors.ComputePipelineDescriptor
import org.graphiks.webgpu.descriptors.DepthStencilState
import org.graphiks.webgpu.descriptors.Extent3D
import org.graphiks.webgpu.descriptors.FragmentState
import org.graphiks.webgpu.descriptors.PrimitiveState
import org.graphiks.webgpu.descriptors.ProgrammableStage
import org.graphiks.webgpu.descriptors.RenderPassColorAttachment
import org.graphiks.webgpu.descriptors.RenderPassDepthStencilAttachment
import org.graphiks.webgpu.descriptors.RenderPassDescriptor
import org.graphiks.webgpu.descriptors.RenderPipelineDescriptor
import org.graphiks.webgpu.descriptors.SamplerDescriptor
import org.graphiks.webgpu.descriptors.ShaderModuleDescriptor
import org.graphiks.webgpu.descriptors.TextureDescriptor
import org.graphiks.webgpu.descriptors.VertexAttribute
import org.graphiks.webgpu.descriptors.VertexBufferLayout
import org.graphiks.webgpu.descriptors.VertexState

const val WORKGROUP_SIZE = 8

/** Rendering modes over the shared photon lightmap, mirroring the webgpu-samples renderers. */
enum class RenderMode { RASTERIZER, RAYTRACER }

/**
 * Owns every GPU resource of the Cornell box renderers, mirroring the webgpu-samples
 * classes: shared scene buffers (Common), the photon radiosity lightmap (Radiosity),
 * the raster pipeline over an indexed quad mesh (Rasterizer), the ray-traced lightmap
 * view (Raytracer) and the tonemapping present pass (Tonemapper). Platform hosts
 * (GLFW window, Android DawnSurfaceView, iOS DawnMetalView) drive it through
 * [renderFrame] inside their own presentation scopes.
 */
class CornellRenderer(internal val session: DawnSession, internal val format: GPUTextureFormat) : AutoCloseable {
    private var closed = false
    private val scene = CornellBox.scene()
    private val quadBytes = (scene.quadData.size * 4).toULong()

    // --- Shared scene buffers (the sample's Common) ---
    internal val quads = session.device.createBuffer(
        BufferDescriptor(quadBytes, GPUBufferUsage.Storage or GPUBufferUsage.CopyDst),
    )
    internal val params = session.device.createBuffer(
        BufferDescriptor(48uL, GPUBufferUsage.Uniform or GPUBufferUsage.CopyDst),
    )
    internal var renderWidth = 640
    internal var renderHeight = 480
    internal var radiance = session.device.createBuffer(
        BufferDescriptor((640 * 480 * 16).toULong(), GPUBufferUsage.Storage),
    )
    internal val dims = session.device.createBuffer(
        BufferDescriptor(16uL, GPUBufferUsage.Uniform or GPUBufferUsage.CopyDst),
    )
    private val cameraBuffer = session.device.createBuffer(
        BufferDescriptor(64uL, GPUBufferUsage.Uniform or GPUBufferUsage.CopyDst),
    )

    init {
        session.queue.writeBuffer(quads, 0uL, ArrayBuffer.of(scene.quadData))
        session.queue.writeBuffer(dims, 0uL, ArrayBuffer.of(uintArrayOf(640u, 480u, 0u, 0u)))
    }

    /** The sample's concatenation pattern: every pass shader is common.wgsl + its own part. */
    private fun shader(pass: String): String = Shaders.COMMON_WGSL + "\n" + pass

    // --- Radiosity (the sample's Radiosity): photons -> atomic u32 -> 256x256 lightmap ---
    private val lightmapSize = 256u
    private val photonTexels = (lightmapSize * lightmapSize * scene.quadCount.toUInt()).toDouble()
    private val photonAccumulation = session.device.createBuffer(
        BufferDescriptor(
            3uL * 4uL * lightmapSize * lightmapSize * scene.quadCount.toULong(),
            GPUBufferUsage.Storage,
        ),
    )
    internal val lightmapTexture = session.device.createTexture(
        TextureDescriptor(
            size = Extent3D(lightmapSize, lightmapSize, scene.quadCount.toUInt()),
            format = GPUTextureFormat.RGBA16Float,
            usage = GPUTextureUsage.StorageBinding or GPUTextureUsage.TextureBinding,
        ),
    )
    internal val lightmapView = lightmapTexture.createView()
    internal val lightmapSampler = session.device.createSampler(SamplerDescriptor(
        magFilter = GPUFilterMode.Linear,
        minFilter = GPUFilterMode.Linear,
    ))
    private val photonParams = session.device.createBuffer(
        BufferDescriptor(32uL, GPUBufferUsage.Uniform or GPUBufferUsage.CopyDst),
    )
    private val radiosityShader = session.device.createShaderModule(ShaderModuleDescriptor(shader(Shaders.RADIOSITY_WGSL)))
    private val radiosityPipeline = session.device.createComputePipeline(
        ComputePipelineDescriptor(ProgrammableStage(radiosityShader, "radiosity")),
    )
    private val publishPipeline = session.device.createComputePipeline(
        ComputePipelineDescriptor(ProgrammableStage(radiosityShader, "publishLightmap")),
    )
    private val radiosityLayout = radiosityPipeline.getBindGroupLayout(0u)
    private val radiosityBinding = session.device.createBindGroup(
        BindGroupDescriptor(
            radiosityLayout,
            listOf(
                BindGroupEntry(0u, quads),
                BindGroupEntry(2u, params),
                BindGroupEntry(4u, photonAccumulation),
                BindGroupEntry(5u, lightmapView),
                BindGroupEntry(6u, photonParams),
            ),
        ),
    )
    private val publishLayout = publishPipeline.getBindGroupLayout(0u)
    private val publishBinding = session.device.createBindGroup(
        BindGroupDescriptor(
            publishLayout,
            listOf(
                BindGroupEntry(4u, photonAccumulation),
                BindGroupEntry(5u, lightmapView),
                BindGroupEntry(6u, photonParams),
            ),
        ),
    )
    private var accumulationMean = 0.0
    var photonsEmitted = 0.0
        private set

    // --- Ray-traced lightmap view (the sample's Raytracer) ---
    private val raytracerShader = session.device.createShaderModule(ShaderModuleDescriptor(shader(Shaders.RAYTRACER_WGSL)))
    private val raytracerPipeline = session.device.createComputePipeline(
        ComputePipelineDescriptor(ProgrammableStage(raytracerShader, "main")),
    )
    private val raytracerLayout = raytracerPipeline.getBindGroupLayout(0u)
    private var raytracerBinding = session.device.createBindGroup(
        BindGroupDescriptor(
            raytracerLayout,
            listOf(
                BindGroupEntry(0u, quads),
                BindGroupEntry(1u, radiance),
                BindGroupEntry(2u, params),
                BindGroupEntry(3u, cameraBuffer),
                BindGroupEntry(4u, lightmapView),
                BindGroupEntry(5u, lightmapSampler),
            ),
        ),
    )

    // --- Rasterized lightmap view (the sample's Rasterizer, their default renderer) ---
    internal val rasterUniforms = session.device.createBuffer(
        BufferDescriptor(64uL, GPUBufferUsage.Uniform or GPUBufferUsage.CopyDst),
    )
    private val mesh = buildMesh()
    private val meshVertices: FloatArray get() = mesh.first
    internal val meshIndices: UShortArray get() = mesh.second
    internal val vertexBuffer = session.device.createBuffer(
        BufferDescriptor((meshVertices.size * 4).toULong(), GPUBufferUsage.Vertex or GPUBufferUsage.CopyDst),
    )
    internal val indexBuffer = session.device.createBuffer(
        BufferDescriptor((meshIndices.size * 2).toULong(), GPUBufferUsage.Index or GPUBufferUsage.CopyDst),
    )
    internal val rasterShader = session.device.createShaderModule(
        ShaderModuleDescriptor(Shaders.RASTERIZER_WGSL),
    )
    internal val rasterPipeline = session.device.createRenderPipeline(
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
                targets = listOf(ColorTargetState(format)),
            ),
            primitive = PrimitiveState(),
            depthStencil = DepthStencilState(
                format = GPUTextureFormat.Depth24Plus,
                depthWriteEnabled = true,
                depthCompare = GPUCompareFunction.Less,
            ),
        ),
    )
    private val rasterLayout = rasterPipeline.getBindGroupLayout(0u)
    private val rasterBinding = session.device.createBindGroup(
        BindGroupDescriptor(
            rasterLayout,
            listOf(BindGroupEntry(0u, rasterUniforms), BindGroupEntry(1u, lightmapView), BindGroupEntry(2u, lightmapSampler)),
        ),
    )
    private var depthTexture: GPUTexture? = null
    private var depthView: GPUTextureView? = null
    private var depthWidth = 0
    private var depthHeight = 0

    // --- Tonemapping present pass (the sample's tonemapper, fullscreen) ---
    internal val presentShader = session.device.createShaderModule(
        ShaderModuleDescriptor(Shaders.PRESENT_WGSL),
    )
    internal val presentPipeline = session.device.createRenderPipeline(
        RenderPipelineDescriptor(
            vertex = VertexState(module = presentShader, entryPoint = "vs"),
            fragment = FragmentState(
                module = presentShader,
                entryPoint = "fs",
                targets = listOf(ColorTargetState(format)),
            ),
        ),
    )
    private val presentLayout = presentPipeline.getBindGroupLayout(0u)
    private var presentBinding = session.device.createBindGroup(
        BindGroupDescriptor(presentLayout, listOf(BindGroupEntry(0u, radiance), BindGroupEntry(1u, dims))),
    )

    init {
        session.queue.writeBuffer(vertexBuffer, 0uL, ArrayBuffer.of(meshVertices))
        session.queue.writeBuffer(indexBuffer, 0uL, ArrayBuffer.of(meshIndices))
    }

    /** Writes the camera basis uniform; called whenever the orbit controls move. */
    fun updateCamera(basis: CameraBasis) {
        session.queue.writeBuffer(cameraBuffer, 0uL, ArrayBuffer.of(basis.toUniform()))
    }

    private var resizePending = false

    /** True once after the render target switched size (the radiance buffer was reallocated). */
    fun consumeResize(): Boolean {
        val pending = resizePending
        resizePending = false
        return pending
    }

    /**
     * The ray-traced view renders at the target's physical extent, like the rasterizer:
     * primary-visibility edges get native pixel density. Swaps the radiance buffer and
     * rebuilds the bind groups that reference it.
     */
    internal fun ensureRenderSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || (width == renderWidth && height == renderHeight)) return
        renderWidth = width
        renderHeight = height
        val replacement = session.device.createBuffer(
            BufferDescriptor((width * height * 16).toULong(), GPUBufferUsage.Storage),
        )
        session.queue.writeBuffer(dims, 0uL, ArrayBuffer.of(uintArrayOf(width.toUInt(), height.toUInt(), 0u, 0u)))
        raytracerBinding.close()
        raytracerBinding = session.device.createBindGroup(
            BindGroupDescriptor(
                raytracerLayout,
                listOf(
                    BindGroupEntry(0u, quads),
                    BindGroupEntry(1u, replacement),
                    BindGroupEntry(2u, params),
                    BindGroupEntry(3u, cameraBuffer),
                    BindGroupEntry(4u, lightmapView),
                    BindGroupEntry(5u, lightmapSampler),
                ),
            ),
        )
        presentBinding.close()
        presentBinding = session.device.createBindGroup(
            BindGroupDescriptor(presentLayout, listOf(BindGroupEntry(0u, replacement), BindGroupEntry(1u, dims))),
        )
        radiance.close()
        radiance = replacement
        resizePending = true
    }

    /** Current render extent, for HUD display. */
    val renderExtent: Pair<Int, Int> get() = renderWidth to renderHeight

    /**
     * One radiosity step plus the selected view pass, in a single command buffer.
     * [extraPass] lets the host draw directly over the presented frame (the desktop HUD).
     */
    fun renderFrame(
        frame: DawnFrame,
        mode: RenderMode,
        orbit: OrbitCamera,
        extraPass: ((DawnCommandEncoder, DawnFrame) -> Unit)? = null,
    ) {
        ensureRenderSize(frame.extent.width, frame.extent.height)
        session.queue.writeBuffer(params, 0uL, ArrayBuffer.of(uintArrayOf(
            scene.quadCount.toUInt(),
            renderWidth.toUInt(),
            renderHeight.toUInt(),
            1u,
            scene.lightIndex.toUInt(), 0u, 0u, 0u,
            1u, 0u, 0u, 0u,
        )))
        val workgroupsX = ((renderWidth + WORKGROUP_SIZE - 1) / WORKGROUP_SIZE).toUInt()
        val workgroupsY = ((renderHeight + WORKGROUP_SIZE - 1) / WORKGROUP_SIZE).toUInt()
        (session.device.createCommandEncoder() as DawnCommandEncoder).use { encoder ->
            encoder.beginComputePass().use { pass ->
                try {
                    runRadiosity(pass)
                    if (mode == RenderMode.RAYTRACER) {
                        pass.setPipeline(raytracerPipeline)
                        pass.setBindGroup(0u, raytracerBinding)
                        pass.dispatchWorkgroups(workgroupsX, workgroupsY)
                    }
                } finally {
                    pass.end()
                }
            }
            if (mode == RenderMode.RASTERIZER) {
                rasterize(encoder, frame, orbit)
            } else {
                present(encoder, frame)
            }
            extraPass?.invoke(encoder, frame)
            encoder.finish().use { session.queue.submit(listOf(it)) }
        }
    }

    /**
     * Photon-mapping radiosity, run every frame regardless of the render mode:
     * 1024 workgroups x 256 photons bounce from the light into the atomic accumulation
     * buffer, then the publish pass writes the progressive mean to the lightmap.
     * Seeds are fresh CPU randoms each frame (the sample's route) so successive frames
     * never retrace the same photons. Mirrors the webgpu-samples overflow rescaling.
     */
    private fun runRadiosity(pass: org.graphiks.webgpu.GPUComputePassEncoder) {
        val photonsPerFrame = 256u * 1024u
        accumulationMean += (photonsPerFrame.toDouble() * 100000.0) / photonTexels
        val toLightmapScale = 1.0 / accumulationMean
        val bufferScale = if (accumulationMean > 2 * 0x10000000) 0.5 else 1.0
        accumulationMean *= bufferScale
        photonsEmitted += photonsPerFrame.toDouble()
        val seeds = kotlin.random.Random.Default
        session.queue.writeBuffer(photonParams, 0uL, ArrayBuffer.of(floatArrayOf(
            toLightmapScale.toFloat(),
            bufferScale.toFloat(),
            Float.fromBits(scene.quadCount),
            Float.fromBits(scene.lightIndex),
            Float.fromBits(seeds.nextInt()),
            Float.fromBits(seeds.nextInt()),
            Float.fromBits(seeds.nextInt()),
            0f,
        )))
        pass.setPipeline(radiosityPipeline)
        pass.setBindGroup(0u, radiosityBinding)
        pass.dispatchWorkgroups(1024u)
        pass.setPipeline(publishPipeline)
        pass.setBindGroup(0u, publishBinding)
        pass.dispatchWorkgroups(lightmapSize / 8u, lightmapSize / 8u, scene.quadCount.toUInt())
    }

    /** The sample's rasterizer: MVP over the indexed quad mesh, tonemapped inline. */
    private fun rasterize(encoder: DawnCommandEncoder, frame: DawnFrame, orbit: OrbitCamera) {
        val extentWidth = frame.extent.width
        val extentHeight = frame.extent.height
        val basis = orbit.basis()
        // The sample's 45-degree fov is NOT ours: the orbit camera frames the box at
        // 2*atan(0.30); using it here made M look like it moved the camera.
        val fovY = 2f * kotlin.math.atan(orbit.tanHalfFov)
        val mvp = Mat4.multiply(
            Mat4.perspective(fovY, extentWidth.toFloat() / extentHeight, 0.5f, 100f),
            Mat4.lookAt(basis.position, orbit.target, V3(0f, 1f, 0f)),
        )
        session.queue.writeBuffer(rasterUniforms, 0uL, ArrayBuffer.of(mvp))
        if (depthWidth != extentWidth || depthHeight != extentHeight) {
            depthView?.close()
            depthTexture?.close()
            depthTexture = session.device.createTexture(
                TextureDescriptor(
                    size = Extent3D(extentWidth.toUInt(), extentHeight.toUInt()),
                    format = GPUTextureFormat.Depth24Plus,
                    usage = GPUTextureUsage.RenderAttachment,
                ),
            )
            depthView = depthTexture!!.createView()
            depthWidth = extentWidth
            depthHeight = extentHeight
        }
        encoder.beginRenderPass(RenderPassDescriptor(
            colorAttachments = listOf(
                RenderPassColorAttachment(
                    frame.view,
                    loadOp = GPULoadOp.Clear,
                    storeOp = GPUStoreOp.Store,
                    clearValue = Color(0.0, 0.0, 0.0, 1.0),
                ),
            ),
            depthStencilAttachment = RenderPassDepthStencilAttachment(
                view = depthView!!,
                depthClearValue = 1.0f,
                depthLoadOp = GPULoadOp.Clear,
                depthStoreOp = GPUStoreOp.Store,
            ),
        )).use { pass ->
            try {
                pass.setPipeline(rasterPipeline)
                pass.setBindGroup(0u, rasterBinding)
                pass.setVertexBuffer(0u, vertexBuffer)
                pass.setIndexBuffer(indexBuffer, GPUIndexFormat.Uint16)
                pass.drawIndexed(meshIndices.size.toUInt())
            } finally {
                pass.end()
            }
        }
    }

    /** The sample's tonemapper folded into a fullscreen pass over the radiance buffer. */
    private fun present(encoder: DawnCommandEncoder, frame: DawnFrame) {
        encoder.beginRenderPass(RenderPassDescriptor(colorAttachments = listOf(
            RenderPassColorAttachment(
                frame.view,
                loadOp = GPULoadOp.Clear,
                storeOp = GPUStoreOp.Store,
                clearValue = Color(0.0, 0.0, 0.0, 1.0),
            ),
        ))).use { pass ->
            try {
                pass.setPipeline(presentPipeline)
                pass.setBindGroup(0u, presentBinding)
                pass.draw(3u)
            } finally {
                pass.end()
            }
        }
    }

    /** Triangulates the scene quads: 4 vertices (position, uv+layer, emissive) + 6 indices each. */
    private fun buildMesh(): Pair<FloatArray, UShortArray> {
        val vertices = FloatArray(scene.quadCount * 4 * 10)
        val indices = UShortArray(scene.quadCount * 6)
        val data = scene.quadData
        for (quad in 0 until scene.quadCount) {
            val o = quad * Scene.FLOATS_PER_QUAD
            val origin = floatArrayOf(data[o], data[o + 1], data[o + 2])
            val edge1 = floatArrayOf(data[o + 4], data[o + 5], data[o + 6])
            val edge2 = floatArrayOf(data[o + 8], data[o + 9], data[o + 10])
            val emissive = floatArrayOf(data[o + 16], data[o + 17], data[o + 18])
            val corners = arrayOf(
                origin,
                floatArrayOf(origin[0] + edge1[0], origin[1] + edge1[1], origin[2] + edge1[2]),
                floatArrayOf(origin[0] + edge1[0] + edge2[0], origin[1] + edge1[1] + edge2[1], origin[2] + edge1[2] + edge2[2]),
                floatArrayOf(origin[0] + edge2[0], origin[1] + edge2[1], origin[2] + edge2[2]),
            )
            val uvs = arrayOf(floatArrayOf(0f, 0f), floatArrayOf(1f, 0f), floatArrayOf(1f, 1f), floatArrayOf(0f, 1f))
            for (corner in 0 until 4) {
                val v = (quad * 4 + corner) * 10
                vertices[v] = corners[corner][0]
                vertices[v + 1] = corners[corner][1]
                vertices[v + 2] = corners[corner][2]
                vertices[v + 3] = 1f
                vertices[v + 4] = uvs[corner][0]
                vertices[v + 5] = uvs[corner][1]
                vertices[v + 6] = quad.toFloat()
                vertices[v + 7] = emissive[0]
                vertices[v + 8] = emissive[1]
                vertices[v + 9] = emissive[2]
            }
            val base = quad * 6
            val first = (quad * 4).toUShort()
            indices[base] = first
            indices[base + 1] = (first + 1u).toUShort()
            indices[base + 2] = (first + 2u).toUShort()
            indices[base + 3] = first
            indices[base + 4] = (first + 2u).toUShort()
            indices[base + 5] = (first + 3u).toUShort()
        }
        return vertices to indices
    }

    override fun close() {
        if (closed) return
        closed = true
        radiosityBinding.close()
        publishBinding.close()
        raytracerBinding.close()
        rasterBinding.close()
        presentBinding.close()
        radiosityLayout.close()
        publishLayout.close()
        raytracerLayout.close()
        rasterLayout.close()
        presentLayout.close()
        rasterPipeline.close()
        raytracerPipeline.close()
        publishPipeline.close()
        radiosityPipeline.close()
        presentPipeline.close()
        rasterShader.close()
        raytracerShader.close()
        radiosityShader.close()
        presentShader.close()
        indexBuffer.close()
        vertexBuffer.close()
        rasterUniforms.close()
        photonParams.close()
        lightmapSampler.close()
        depthView?.close()
        depthTexture?.close()
        lightmapView.close()
        lightmapTexture.close()
        photonAccumulation.close()
        cameraBuffer.close()
        dims.close()
        radiance.close()
        params.close()
        quads.close()
    }
}
