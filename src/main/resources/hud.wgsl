// HUD text pass: coverage glyphs from a Kalligraphie-rasterized atlas, tinted
// and alpha-blended over the presented scene. Vertices arrive through a storage
// buffer (x_px, y_px, u, v) so no vertex-buffer plumbing is needed.

struct HudUniforms {
    viewport: vec4f, // xy: framebuffer extent in pixels
    color: vec4f,
}

@group(0) @binding(0) var<uniform> uniforms: HudUniforms;
@group(0) @binding(1) var atlasSampler: sampler;
@group(0) @binding(2) var atlas: texture_2d<f32>;
@group(0) @binding(3) var<storage, read> vertices: array<vec4f>;

struct VertexOutput {
    @builtin(position) position: vec4f,
    @location(0) uv: vec2f,
};

@vertex
fn vs(@builtin(vertex_index) index: u32) -> VertexOutput {
    let glyph = vertices[index];
    var output: VertexOutput;
    let ndc = vec2f(
        glyph.x / uniforms.viewport.x * 2.0 - 1.0,
        1.0 - glyph.y / uniforms.viewport.y * 2.0,
    );
    output.position = vec4f(ndc, 0.0, 1.0);
    output.uv = glyph.zw;
    return output;
}

@fragment
fn fs(input: VertexOutput) -> @location(0) vec4f {
    let coverage = textureSample(atlas, atlasSampler, input.uv).r;
    return vec4f(uniforms.color.rgb, uniforms.color.a * coverage);
}
