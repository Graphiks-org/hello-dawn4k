package org.graphiks.hellodawn4k

import org.graphiks.dawn4k.DawnRenderPassEncoder
import org.graphiks.dawn4k.toolkit.DawnSession
import org.graphiks.kalligraphie.EditableLineFacadeRequest
import org.graphiks.kalligraphie.EditableLineLayoutSession
import org.graphiks.kalligraphie.FontDirectoryCatalog
import org.graphiks.kalligraphie.FontDirectoryCatalogOptions
import org.graphiks.kalligraphie.Kalligraphie
import org.graphiks.kalligraphie.MacosSystemFontCatalog
import org.graphiks.kalligraphie.MacosSystemFontCatalogOptions
import org.graphiks.kalligraphie.api.BaseDirection
import org.graphiks.kalligraphie.api.EditableLine
import org.graphiks.kalligraphie.api.EditableLineMaterialization
import org.graphiks.kalligraphie.api.EditableLineResult
import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontAssetResolverHandle
import org.graphiks.kalligraphie.api.FontGlyphRequest
import org.graphiks.kalligraphie.api.FontInstance
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontRenderAssetHandle
import org.graphiks.kalligraphie.api.FontRenderVariantSnapshot
import org.graphiks.kalligraphie.api.GlyphOutlineIR
import org.graphiks.kalligraphie.api.GlyphRepresentation
import org.graphiks.kalligraphie.api.LayoutHandle
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.LineVerticalMetrics
import org.graphiks.kalligraphie.api.OutlineProfile
import org.graphiks.kalligraphie.api.TextSlice
import org.graphiks.kalligraphie.api.TextVersion
import org.graphiks.kalligraphie.layout.openLayoutHandle
import org.graphiks.kalligraphie.raster.A8Image
import org.graphiks.kalligraphie.raster.GlyphRasterizer
import org.graphiks.kalligraphie.raster.OutlineRasterRequest
import org.graphiks.kalligraphie.raster.RasterResult
import org.graphiks.kalligraphie.shaping.HarfBuzzShapingBackend
import org.graphiks.webgpu.ArrayBuffer
import org.graphiks.webgpu.GPUAddressMode
import org.graphiks.webgpu.GPUBlendFactor
import org.graphiks.webgpu.GPUBlendOperation
import org.graphiks.webgpu.GPUBufferUsage
import org.graphiks.webgpu.GPUFilterMode
import org.graphiks.webgpu.GPUTextureFormat
import org.graphiks.webgpu.GPUTextureUsage
import org.graphiks.webgpu.descriptors.BindGroupDescriptor
import org.graphiks.webgpu.descriptors.BindGroupEntry
import org.graphiks.webgpu.descriptors.BlendComponent
import org.graphiks.webgpu.descriptors.BlendState
import org.graphiks.webgpu.descriptors.BufferDescriptor
import org.graphiks.webgpu.descriptors.ColorTargetState
import org.graphiks.webgpu.descriptors.Extent3D
import org.graphiks.webgpu.descriptors.FragmentState
import org.graphiks.webgpu.descriptors.RenderPipelineDescriptor
import org.graphiks.webgpu.descriptors.SamplerDescriptor
import org.graphiks.webgpu.descriptors.ShaderModuleDescriptor
import org.graphiks.webgpu.descriptors.TexelCopyBufferLayout
import org.graphiks.webgpu.descriptors.TexelCopyTextureInfo
import org.graphiks.webgpu.descriptors.TextureDescriptor
import org.graphiks.webgpu.descriptors.VertexState

/** One placed glyph: pen origin relative to the line baseline (pixels, y-down) plus its outline. */
class HudGlyph(val glyphId: Int, val penX: Float, val penY: Float, val outline: GlyphOutlineIR)

/**
 * CPU side of the HUD: shapes single lines through Kalligraphie's editable-line facade
 * against Menlo from the macOS system catalog and returns per-glyph outlines ready for
 * rasterization. Refusals come back as empty lines so the window keeps running without text.
 */
class HudText private constructor(
    private val fontPx: Float,
    private val font: FontInstance,
    private val resolver: FontAssetResolverHandle,
    private val session: EditableLineLayoutSession,
    private val requirements: FontAccessRequirementsSnapshot,
    private val tempRoot: java.nio.file.Path?,
) : AutoCloseable {

    override fun close() {
        session.close()
        resolver.close()
        tempRoot?.toFile()?.deleteRecursively()
    }

    fun layoutLine(text: String): List<HudGlyph> {
        val decoded = Kalligraphie.decodeUtf8(TextVersion.create(), listOf(TextSlice.Utf8(text.encodeToByteArray())))
        val result = session.layout(EditableLineFacadeRequest(
            snapshot = decoded.snapshot,
            font = font,
            baseDirection = BaseDirection.LEFT_TO_RIGHT,
            language = "en",
            featurePolicy = HarfBuzzShapingBackend.pinnedFeaturePolicy,
            features = emptyList(),
            verticalMetrics = LineVerticalMetrics(LayoutUnit(fontPx), LayoutUnit(fontPx / 4)),
            materialization = EditableLineMaterialization.Renderable(resolver, FontRenderVariantSnapshot.default, requirements),
        ))
        val line = (result as? EditableLineResult.Success)?.line ?: return emptyList()
        val handle = line.openLayoutHandle(resolver).valueOrNull() ?: return emptyList()
        try {
            val glyphs = ArrayList<HudGlyph>()
            for (run in line.positionedGlyphRuns) {
                for (glyph in run.glyphs) {
                    val certificate = glyph.materializationCertificate ?: continue
                    val retained = handle.retainFontAsset(certificate).valueOrNull() ?: continue
                    try {
                        val representation = retained.resolveGlyph(FontGlyphRequest(glyph.shapedGlyph.glyphId.value))
                            .valueOrNull() as? GlyphRepresentation ?: continue
                        val outline = (representation as? GlyphRepresentation.Outline)?.outline ?: continue
                        glyphs.add(HudGlyph(glyph.shapedGlyph.glyphId.value, glyph.origin.x.value, glyph.origin.y.value, outline))
                    } finally {
                        retained.close()
                    }
                }
            }
            return glyphs
        } finally {
            handle.close()
        }
    }

    /** Rasterizes one outline into an 8-bit coverage image; blanks (spaces) come back empty. */
    fun rasterize(outline: GlyphOutlineIR): A8Image? = when (
        val result = GlyphRasterizer.rasterizeOutline(outline, OutlineRasterRequest(pixelsPerEm = fontPx.toDouble()))
    ) {
        is RasterResult.Success -> result.value
        is RasterResult.Failure -> null
    }

    companion object {
        /**
         * Opens Menlo Regular deterministically: the default system catalog caps acceptance at
         * 32 faces in filesystem enumeration order, so a full-directory scan finds Menlo only
         * intermittently. Copying Menlo.ttc into its own temporary root (the same route the
         * library's own journey tests use) makes the capture exact; a limits-raised system scan
         * remains as fallback. Prints why the HUD disabled itself on failure.
         */
        fun open(fontPx: Float = 18f): HudText? {
            val requirements = FontAccessRequirementsSnapshot.renderable(
                OutlineProfile(
                    maxBytes = 1_000_000,
                    maxContours = 16_384,
                    maxPoints = 1_000_000,
                    maxCompositeDepth = 32,
                    maxCompositeComponents = 16_384,
                ),
            )
            val failures = ArrayList<String>()
            for (attempt in captureAttempts()) {
                val opened = attempt.open()
                val catalog = (opened as? FontOperationResult.Success)?.value
                if (catalog == null) {
                    failures += "catalog refused (${attempt.label}): $opened"
                    attempt.tempDir?.toFile()?.deleteRecursively()
                    continue
                }
                val record = catalog.faces.firstOrNull { face ->
                    attempt.families.any { family ->
                        face.metadata.familyName == family && face.metadata.styleName == "Regular"
                    }
                } ?: catalog.faces.firstOrNull { face -> attempt.families.contains(face.metadata.familyName) }
                if (record == null) {
                    val families = catalog.faces.take(6).joinToString { it.metadata.familyName }
                    failures += "font absent (${attempt.label}, ${catalog.faces.size} faces: $families…)"
                    attempt.tempDir?.toFile()?.deleteRecursively()
                    continue
                }
                val hud = openFrom(catalog, record, requirements, fontPx, attempt.tempDir)
                if (hud == null) {
                    failures += "face/instance/session refused (${attempt.label})"
                }
                return hud
            }
            println("hud disabled: ${failures.joinToString("; ")}")
            return null
        }

        private class CaptureAttempt(
            val label: String,
            val families: List<String>,
            val tempDir: java.nio.file.Path?,
            val open: () -> FontOperationResult<org.graphiks.kalligraphie.api.FontCatalogSnapshot>,
        )

        /**
         * Liberation Sans ships in the resources (OFL-1.1, the font Kalligraphie's own benches
         * exercise most) and is extracted to a private directory — deterministic on every JVM.
         * The macOS system scan stays as a fallback: its default caps accept only 32 faces in
         * enumeration order, and on recent macOS the SFNT name tables of system fonts such as
         * Menlo are rejected, so raised limits are passed but success is not guaranteed.
         */
        private fun captureAttempts(): List<CaptureAttempt> {
            val attempts = ArrayList<CaptureAttempt>()
            runCatching {
                val resource = requireNotNull(javaClass.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
                    "bundled font missing"
                }
                val dir = java.nio.file.Files.createTempDirectory("hellodawn4k-font")
                java.nio.file.Files.copy(resource, dir.resolve("LiberationSans-Regular.ttf"))
                attempts.add(
                    CaptureAttempt(
                        label = "Liberation Sans embarquée",
                        families = listOf("Liberation Sans"),
                        tempDir = dir,
                        open = {
                            FontDirectoryCatalog.open(FontDirectoryCatalogOptions(roots = listOf(dir.toString())))
                        },
                    ),
                )
            }
            val raisedLimits = MacosSystemFontCatalogOptions(
                maxPathsToVisit = 2048,
                maxFaces = 512,
                maxFacesToExamine = 2048,
                maxTotalSourceBytes = 512 * 1024 * 1024,
            )
            attempts.add(
                CaptureAttempt(
                    label = "scan système macOS",
                    families = listOf("Menlo", "Monaco", "Courier New"),
                    tempDir = null,
                    open = { MacosSystemFontCatalog.open(raisedLimits) },
                ),
            )
            return attempts
        }

        private fun openFrom(
            catalog: org.graphiks.kalligraphie.api.FontCatalogSnapshot,
            record: org.graphiks.kalligraphie.api.FontFaceRecord,
            requirements: FontAccessRequirementsSnapshot,
            fontPx: Float,
            tempRoot: java.nio.file.Path?,
        ): HudText? {
            val resolver = catalog.openAssetResolver().valueOrNull() ?: return null
            val resolved = catalog.resolveFace(record.id, requirements).valueOrNull() ?: return null
            val font = resolved.instantiate(FontInstanceDescriptor(LayoutUnit(fontPx))).valueOrNull() ?: return null
            val session = EditableLineLayoutSession.open().valueOrNull() ?: return null
            return HudText(fontPx, font, resolver, session, requirements, tempRoot)
        }

        private fun <T> FontOperationResult<T>.valueOrNull(): T? = (this as? FontOperationResult.Success<T>)?.value
    }
}

/** Pipeline plus its bind group for one target format; the HUD draws into several. */
private class HudPass(
    val pipeline: org.graphiks.webgpu.GPURenderPipeline,
    val layout: org.graphiks.webgpu.GPUBindGroupLayout,
    val binding: org.graphiks.webgpu.GPUBindGroup,
)

/**
 * GPU side of the HUD: a lazily filled R8 coverage atlas (rewritten whole on growth;
 * 512-wide rows keep bytesPerRow a multiple of 256) and one alpha-blended quad draw
 * per glyph through a storage-buffer vertex stream.
 */
class HudRenderer(private val session: DawnSession, private val text: HudText) : AutoCloseable {
    private var closed = false
    private val atlasWidth = 512u
    private val atlasHeight = 192u
    private val atlasBytes = ByteArray((atlasWidth * atlasHeight).toInt())
    private val placements = HashMap<Int, Placement>()
    private val passes = HashMap<GPUTextureFormat, HudPass>()
    private var cursorX = 0u
    private var cursorY = 0u
    private var rowHeight = 0u
    private var atlasDirty = false

    private class Placement(val u: Float, val v: Float, val w: Float, val h: Float, val left: Int, val top: Int)

    private val atlas = session.device.createTexture(
        TextureDescriptor(
            size = Extent3D(atlasWidth, atlasHeight),
            format = GPUTextureFormat.R8Unorm,
            usage = GPUTextureUsage.TextureBinding or GPUTextureUsage.CopyDst,
        ),
    )
    private val atlasView = atlas.createView()
    private val sampler = session.device.createSampler(SamplerDescriptor(
        magFilter = GPUFilterMode.Linear,
        minFilter = GPUFilterMode.Linear,
        addressModeU = GPUAddressMode.ClampToEdge,
        addressModeV = GPUAddressMode.ClampToEdge,
    ))
    private val uniforms = session.device.createBuffer(
        BufferDescriptor(32uL, GPUBufferUsage.Uniform or GPUBufferUsage.CopyDst),
    )
    private val vertices = session.device.createBuffer(
        BufferDescriptor(512uL * 24uL * 4uL, GPUBufferUsage.Storage or GPUBufferUsage.CopyDst), // 512 glyphs
    )
    private val shader = session.device.createShaderModule(
        ShaderModuleDescriptor(
            requireNotNull(javaClass.getResourceAsStream("/hud.wgsl")) { "missing hud.wgsl" }.readBytes().decodeToString(),
        ),
    )

    /** Uploads the quad stream (6 vertices × x,y,u,v per glyph) and draws it blended over the scene. */
    fun draw(
        pass: org.graphiks.webgpu.GPURenderPassEncoder,
        format: GPUTextureFormat,
        extentWidth: Int,
        extentHeight: Int,
        floats: FloatArray,
    ) {
        if (floats.isEmpty()) return
        flushAtlas()
        session.queue.writeBuffer(vertices, 0uL, ArrayBuffer.of(floats))
        session.queue.writeBuffer(uniforms, 0uL, ArrayBuffer.of(floatArrayOf(
            extentWidth.toFloat(), extentHeight.toFloat(), 0f, 0f,
            1.0f, 1.0f, 1.0f, 0.85f,
        )))
        val hudPass = passes.getOrPut(format) {
            createPass(format)
        }
        pass.setPipeline(hudPass.pipeline)
        pass.setBindGroup(0u, hudPass.binding)
        pass.draw((floats.size / 4).toUInt())
    }

    /**
     * Builds the quad stream for pre-shaped [lines]; [baseX]/[baseY] anchor the first
     * baseline and [lineHeight] steps it. Glyphs missing from the atlas are rasterized
     * and packed first.
     */
    fun buildVertices(lines: List<List<HudGlyph>>, baseX: Float, baseY: Float, lineHeight: Float): FloatArray {
        val out = ArrayList<Float>(lines.sumOf { it.size } * 24)
        lines.forEachIndexed { index, line ->
            val baseline = baseY + index * lineHeight
            for (glyph in line) {
                val placement = placements[glyph.glyphId] ?: place(glyph.glyphId, glyph.outline) ?: continue
                if (placement.w <= 0f) continue
                val x0 = baseX + glyph.penX + placement.left
                val y0 = baseline + glyph.penY + placement.top
                val x1 = x0 + placement.w
                val y1 = y0 + placement.h
                val u0 = placement.u
                val v0 = placement.v
                val u1 = placement.u + placement.w / atlasWidth.toFloat()
                val v1 = placement.v + placement.h / atlasHeight.toFloat()
                out.addAll(listOf(
                    x0, y0, u0, v0, x1, y0, u1, v0, x1, y1, u1, v1,
                    x0, y0, u0, v0, x1, y1, u1, v1, x0, y1, u0, v1,
                ))
            }
        }
        return out.toFloatArray()
    }

    private fun place(glyphId: Int, outline: GlyphOutlineIR): Placement? {
        val image = text.rasterize(outline) ?: return null
        if (image.width == 0 || image.height == 0) {
            val blank = Placement(0f, 0f, 0f, 0f, 0, 0)
            placements[glyphId] = blank
            return blank
        }
        if (cursorX + image.width.toUInt() > atlasWidth) {
            cursorX = 0u
            cursorY += rowHeight
            rowHeight = 0u
        }
        if (cursorY + image.height.toUInt() > atlasHeight) return null // atlas full: skip the glyph
        val x = cursorX
        val pixels = image.copyPixels()
        for (row in 0 until image.height) {
            val source = row * image.width
            val target = ((cursorY + row.toUInt()) * atlasWidth + x).toInt()
            pixels.copyInto(atlasBytes, target, source, source + image.width)
        }
        cursorX += image.width.toUInt()
        rowHeight = maxOf(rowHeight, image.height.toUInt())
        val placement = Placement(
            x.toFloat() / atlasWidth.toFloat(),
            cursorY.toFloat() / atlasHeight.toFloat(),
            image.width.toFloat(),
            image.height.toFloat(),
            image.left,
            image.top,
        )
        placements[glyphId] = placement
        atlasDirty = true
        return placement
    }

    private fun flushAtlas() {
        if (!atlasDirty) return
        atlasDirty = false
        session.queue.writeTexture(
            TexelCopyTextureInfo(atlas),
            ArrayBuffer.of(atlasBytes),
            TexelCopyBufferLayout(bytesPerRow = atlasWidth),
            Extent3D(atlasWidth, atlasHeight),
        )
    }

    private fun createPass(format: GPUTextureFormat): HudPass {
        val pipeline = session.device.createRenderPipeline(
            RenderPipelineDescriptor(
                vertex = VertexState(module = shader, entryPoint = "vs"),
                fragment = FragmentState(
                    module = shader,
                    entryPoint = "fs",
                    targets = listOf(
                        ColorTargetState(
                            format,
                            blend = BlendState(
                                color = BlendComponent(
                                    operation = GPUBlendOperation.Add,
                                    srcFactor = GPUBlendFactor.SrcAlpha,
                                    dstFactor = GPUBlendFactor.OneMinusSrcAlpha,
                                ),
                                alpha = BlendComponent(
                                    operation = GPUBlendOperation.Add,
                                    srcFactor = GPUBlendFactor.One,
                                    dstFactor = GPUBlendFactor.OneMinusSrcAlpha,
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val layout = pipeline.getBindGroupLayout(0u)
        val binding = session.device.createBindGroup(
            BindGroupDescriptor(
                layout,
                listOf(
                    BindGroupEntry(0u, uniforms),
                    BindGroupEntry(1u, sampler),
                    BindGroupEntry(2u, atlasView),
                    BindGroupEntry(3u, vertices),
                ),
            ),
        )
        return HudPass(pipeline, layout, binding)
    }

    override fun close() {
        if (closed) return
        closed = true
        passes.values.forEach { it.binding.close(); it.layout.close(); it.pipeline.close() }
        passes.clear()
        shader.close()
        vertices.close()
        uniforms.close()
        sampler.close()
        atlasView.close()
        atlas.close()
    }
}
