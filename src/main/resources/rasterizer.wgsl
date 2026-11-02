// Rasterized lightmap view aligned with the webgpu-samples Cornell box
// (sample/cornell/rasterizer.wgsl), their default renderer: the scene quads are
// drawn as an indexed triangle list through a classic MVP, the fragment shader
// samples the photon lightmap by (uv, layer) and adds the surface emission.
// Depth-tested, tonemapped inline with the sample's 0.5 exposure.
//
// Deviation kept on purpose: no back-face culling, because the Cornell scene's
// quad windings are not uniform (walls, light and boxes mix edge orders).

struct RasterUniforms {
    mvp: mat4x4f,
}

@group(0) @binding(0) var<uniform> rasterUniforms: RasterUniforms;
@group(0) @binding(1) var lightmap: texture_2d_array<f32>;
@group(0) @binding(2) var smpl: sampler;

struct VertexIn {
    @location(0) position: vec4f,
    @location(1) uv: vec3f, // xy: quad uv, z: lightmap layer
    @location(2) emissive: vec3f,
}

struct VertexOut {
    @builtin(position) position: vec4f,
    @location(0) uv: vec3f,
    @location(1) emissive: vec3f,
}

@vertex
fn vs_main(input: VertexIn) -> VertexOut {
    var output: VertexOut;
    output.position = rasterUniforms.mvp * input.position;
    output.uv = input.uv;
    output.emissive = input.emissive;
    return output;
}

@fragment
fn fs_main(input: VertexOut) -> @location(0) vec4f {
    let lit = textureSample(lightmap, smpl, input.uv.xy, u32(input.uv.z + 0.5));
    let color = (lit.rgb + input.emissive) * 0.5; // the sample's tonemap exposure
    let tonemapped = color / (vec3f(1.0) + color);
    return vec4f(pow(tonemapped, vec3f(1.0 / 2.2)), 1.0);
}
