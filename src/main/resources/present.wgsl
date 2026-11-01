// Presentation pass: a fullscreen triangle that reads the path-traced radiance
// buffer (internal resolution) and writes Reinhard + gamma encoded pixels to
// the swapchain view at whatever extent the window currently has.

struct Dims {
    width: u32,
    height: u32,
    padding: vec2u,
}

@group(0) @binding(0) var<storage, read> radiance: array<vec4f>;
@group(0) @binding(1) var<uniform> dims: Dims;

struct VertexOutput {
    @builtin(position) position: vec4f,
    @location(0) uv: vec2f,
};

@vertex
fn vs(@builtin(vertex_index) index: u32) -> VertexOutput {
    let corners = array<vec2f, 3>(vec2f(-1.0, -1.0), vec2f(3.0, -1.0), vec2f(-1.0, 3.0));
    let corner = corners[index];
    var output: VertexOutput;
    output.position = vec4f(corner, 0.0, 1.0);
    // uv.y = 0 at the top of the screen, matching the compute shader's row 0.
    output.uv = vec2f(corner.x * 0.5 + 0.5, 0.5 - corner.y * 0.5);
    return output;
}

@fragment
fn fs(input: VertexOutput) -> @location(0) vec4f {
    let x = clamp(u32(input.uv.x * f32(dims.width)), 0u, dims.width - 1u);
    let y = clamp(u32(input.uv.y * f32(dims.height)), 0u, dims.height - 1u);
    let linear = radiance[y * dims.width + x].rgb;
    let reinhard = linear / (vec3f(1.0) + linear);
    let gamma = pow(max(reinhard, vec3f(0.0)), vec3f(1.0 / 2.2));
    return vec4f(gamma, 1.0);
}
