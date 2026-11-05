package org.graphiks.hellodawn4k

/** WGSL sources as Kotlin constants: the single cross-platform source of truth
 * (Kotlin/Native frameworks do not bundle common resources, so files would not
 * reach iOS; the desktop edits these strings like any shader file).
 */
object Shaders {
    const val COMMON_WGSL: String = """
// Shared scene module, concatenated by the loader before each pass-specific shader
// (the webgpu-samples cornell architecture: one common module, several passes).
// Bindings declared here but unused by an entry point are dropped from its
// reflected layout, so every pass sees only its own interface.

struct Quad {
    origin: vec4f,
    edge1: vec4f,
    edge2: vec4f,
    albedo: vec4f,
    emissive: vec4f,
    normalArea: vec4f,
}

struct Params {
    quadCount: u32,
    width: u32,
    height: u32,
    samples: u32,
    light: vec4u, // x: index of the single emissive quad
    frame: vec4u, // x: 1-based frame index of the accumulation
}

struct Camera {
    position: vec4f,
    right: vec4f,
    up: vec4f,
    forward: vec4f, // xyz: unit forward, w: tan of the half vertical fov
}

@group(0) @binding(0) var<storage, read> quads: array<Quad>;
@group(0) @binding(1) var<storage, read_write> radiance: array<vec4f>;
@group(0) @binding(2) var<uniform> params: Params;
@group(0) @binding(3) var<uniform> camera: Camera;

const PI: f32 = 3.14159265358979;
const SURFACE_EPSILON: f32 = 1e-4;
const MISSED: u32 = 0xffffffffu;

struct Rng {
    state: u32,
}

fn rngNext(rng: ptr<function, Rng>) -> u32 {
    // PCG output function over an LCG step (O'Neill).
    (*rng).state = (*rng).state * 747796405u + 2891336453u;
    let word = (((*rng).state >> (((*rng).state >> 28u) + 4u)) ^ (*rng).state) * 277803737u;
    return (word >> 22u) ^ word;
}

fn rngFloat(rng: ptr<function, Rng>) -> f32 {
    return f32(rngNext(rng)) / 4294967296.0;
}

fn randUnitSphere(rng: ptr<function, Rng>) -> vec3f {
    // WGSL requires a function that returns a value to end with a return statement.
    // naga (Firefox) enforces this; Tint (Chrome) does not — so a return that only
    // lives inside the loop is a Firefox-only compile failure. Break out, then return.
    var candidate = vec3f(0.0, 0.0, 0.0);
    loop {
        candidate = vec3f(rngFloat(rng), rngFloat(rng), rngFloat(rng)) * 2.0 - vec3f(1.0);
        if (dot(candidate, candidate) < 1.0) {
            break;
        }
    }
    return candidate;
}

/** Returns (t, u, v); t < 0 on miss. */
fn rayQuad(ro: vec3f, rd: vec3f, q: Quad) -> vec3f {
    let againstEdge2 = cross(rd, q.edge2.xyz);
    let det = dot(q.edge1.xyz, againstEdge2);
    let invDet = 1.0 / det;
    let toOrigin = ro - q.origin.xyz;
    let u = dot(toOrigin, againstEdge2) * invDet;
    if (u < 0.0 || u > 1.0) {
        return vec3f(-1.0, 0.0, 0.0);
    }
    let againstEdge1 = cross(toOrigin, q.edge1.xyz);
    let v = dot(rd, againstEdge1) * invDet;
    // Parallelogram quad, not a triangle: accept the full u,v unit square.
    if (v < 0.0 || v > 1.0) {
        return vec3f(-1.0, 0.0, 0.0);
    }
    let t = dot(q.edge2.xyz, againstEdge1) * invDet;
    if (t < SURFACE_EPSILON) {
        return vec3f(-1.0, 0.0, 0.0);
    }
    return vec3f(t, u, v);
}

struct Hit {
    t: f32,
    u: f32,
    v: f32,
    index: u32,
}

fn closestHit(ro: vec3f, rd: vec3f) -> Hit {
    var best = Hit(-1.0, 0.0, 0.0, MISSED);
    for (var i = 0u; i < params.quadCount; i++) {
        let hit = rayQuad(ro, rd, quads[i]);
        if (hit.x > 0.0 && (best.index == MISSED || hit.x < best.t)) {
            best = Hit(hit.x, hit.y, hit.z, i);
        }
    }
    return best;
}

/** True when any quad stands between the ray and maxDistance. */
fn occluded(ro: vec3f, rd: vec3f, maxDistance: f32) -> bool {
    for (var i = 0u; i < params.quadCount; i++) {
        let hit = rayQuad(ro, rd, quads[i]);
        if (hit.x > 0.0 && hit.x < maxDistance) {
            return true;
        }
    }
    return false;
}

fn cosineWeightedDirection(n: vec3f, u1: f32, u2: f32) -> vec3f {
    let radius = sqrt(u1);
    let angle = 2.0 * PI * u2;
    let helper = select(vec3f(1.0, 0.0, 0.0), vec3f(0.0, 1.0, 0.0), abs(n.y) < 0.9);
    let tangent = normalize(cross(helper, n));
    let bitangent = cross(n, tangent);
    return normalize(
        tangent * (radius * cos(angle))
            + bitangent * (radius * sin(angle))
            + n * sqrt(max(0.0, 1.0 - u1))
    );
}

/** The primary ray through a pixel center, from the shared camera uniform. */
fn cameraRay(pixel: vec2u) -> vec3f {
    let uv = (vec2f(pixel) + vec2f(0.5)) / vec2f(f32(params.width), f32(params.height));
    let ndc = vec2f(uv.x * 2.0 - 1.0, 1.0 - 2.0 * uv.y);
    let aspect = f32(params.width) / f32(params.height);
    return normalize(
        camera.right.xyz * (ndc.x * camera.forward.w * aspect)
            + camera.up.xyz * (ndc.y * camera.forward.w)
            + camera.forward.xyz
    );
}
"""

    const val RADIOSITY_WGSL: String = """
// Photon-mapping radiosity aligned with the webgpu-samples Cornell box
// (sample/cornell/radiosity.wgsl, BSD-3-Clause, Copyright 2019 WebGPU Samples
// Contributors; full license text in THIRD_PARTY_LICENSES/).
//
// Aligned semantics: photons spawn on the light, bounce with the sample's
// reflect-plus-random-sphere kernel, deposit POST-albedo energy (the lightmap
// holds exitant light, so the view displays it directly) on every bounce
// including the first (direct), into one 256x256 lightmap layer per quad.
// The publish entry copies the atomic u32 accumulation into the lightmap with
// the sample's progressive-mean scale and overflow rescaling.
//
// Deviation kept on purpose: seeds come as fresh CPU randoms every frame (also
// the sample's route) instead of a frame-invariant invocation seed, which would
// retrace the same photons forever. Scene helpers live in common.wgsl.

struct PhotonParams {
    accumulationToLightmapScale: f32,
    accumulationBufferScale: f32,
    quadCount: u32,
    lightIndex: u32,
    seeds: vec4u,
}

@group(0) @binding(4) var<storage, read_write> accumulation: array<atomic<u32>>;
@group(0) @binding(5) var lightmap: texture_storage_2d_array<rgba16float, write>;
@group(0) @binding(6) var<uniform> photonParams: PhotonParams;

const PHOTON_ENERGY: f32 = 100000.0;
const PHOTON_BOUNCES: u32 = 4u;
const LIGHT_ABSORPTION: f32 = 0.5;

fn texelBase(u: f32, v: f32, quadIndex: u32) -> u32 {
    let dims = textureDimensions(lightmap);
    let coord = min(vec2u(vec2f(u, v) * vec2f(dims)), dims - vec2u(1u));
    return 3u * (coord.x + dims.x * coord.y + dims.x * dims.y * quadIndex);
}

@compute @workgroup_size(256)
fn radiosity(@builtin(global_invocation_id) gid: vec3u) {
    var rng = Rng(
        photonParams.seeds.x ^ (gid.x * 747796405u) ^ (gid.y * 2891336453u) ^ (gid.z * 2654435761u),
    );
    _ = rngNext(&rng);

    let light = quads[photonParams.lightIndex];
    var rayOrigin = light.origin.xyz
        + light.edge1.xyz * rngFloat(&rng)
        + light.edge2.xyz * rngFloat(&rng);
    // The light quad's stored normal faces the room; photons launch downward.
    var rayDir = cosineWeightedDirection(light.normalArea.xyz, rngFloat(&rng), rngFloat(&rng));
    var color = PHOTON_ENERGY * vec3f(1.0, 0.88, 0.7);

    for (var bounce = 0u; bounce <= PHOTON_BOUNCES; bounce++) {
        let hit = closestHit(rayOrigin, rayDir);
        if (hit.index == MISSED) {
            break;
        }
        let quad = quads[hit.index];
        let position = rayOrigin + rayDir * hit.t;

        // The sample's bounce kernel: specular reflect blurred by 0.75 of a random unit sphere.
        rayDir = normalize(reflect(rayDir, quad.normalArea.xyz) + randUnitSphere(&rng) * 0.75);
        rayOrigin = position + quad.normalArea.xyz * 1e-5;

        // Deposit AFTER the albedo multiplication (the sample's order): the
        // lightmap carries exitant light and needs no view-side albedo.
        color *= quad.albedo.xyz;
        let energy = color * LIGHT_ABSORPTION;
        let base = texelBase(hit.u, hit.v, hit.index);
        atomicAdd(&accumulation[base + 0u], u32(energy.r + 0.5));
        atomicAdd(&accumulation[base + 1u], u32(energy.g + 0.5));
        atomicAdd(&accumulation[base + 2u], u32(energy.b + 0.5));
    }
}

@compute @workgroup_size(8, 8)
fn publishLightmap(@builtin(global_invocation_id) gid: vec3u, @builtin(workgroup_id) wid: vec3u) {
    let dims = textureDimensions(lightmap);
    let quadIndex = wid.z;
    let coord = gid.xy;
    if (all(coord < dims)) {
        let base = 3u * (coord.x + dims.x * coord.y + dims.x * dims.y * quadIndex);
        let color = vec3f(
            f32(atomicLoad(&accumulation[base + 0u])),
            f32(atomicLoad(&accumulation[base + 1u])),
            f32(atomicLoad(&accumulation[base + 2u])),
        ) * photonParams.accumulationToLightmapScale;

        textureStore(lightmap, coord, quadIndex, vec4f(color, 1.0));

        // Near u32 saturation the whole buffer is scaled down (the sample's trick).
        if (photonParams.accumulationBufferScale != 1.0) {
            let scaled = color * photonParams.accumulationBufferScale + vec3f(0.5);
            atomicStore(&accumulation[base + 0u], u32(scaled.r));
            atomicStore(&accumulation[base + 1u], u32(scaled.g));
            atomicStore(&accumulation[base + 2u], u32(scaled.b));
        }
    }
}
"""

    const val RAYTRACER_WGSL: String = """
// Ray-traced lightmap view aligned with the webgpu-samples Cornell box
// (sample/cornell/raytracer.wgsl): one primary ray per pixel, the hit samples
// the photon lightmap directly (it holds exitant light, albedo included) and
// adds the surface emission. The sample's tonemapper applies a 0.5 exposure,
// mirrored here since this path skips our present pass's exposure of 1.0.
// Scene helpers live in common.wgsl.

@group(0) @binding(4) var lightmap: texture_2d_array<f32>;
@group(0) @binding(5) var smpl: sampler;

const TONEMAP_EXPOSURE: f32 = 0.5;

@compute @workgroup_size(8, 8)
fn main(@builtin(global_invocation_id) gid: vec3u) {
    if (gid.x >= params.width || gid.y >= params.height) {
        return;
    }
    let index = gid.y * params.width + gid.x;

    let hit = closestHit(camera.position.xyz, cameraRay(gid.xy));
    if (hit.index == MISSED) {
        radiance[index] = vec4f(0.0, 0.0, 0.0, 1.0);
        return;
    }
    let quad = quads[hit.index];
    let lit = textureSampleLevel(lightmap, smpl, vec2f(hit.u, hit.v), hit.index, 0.0).rgb;
    radiance[index] = vec4f((lit + quad.emissive.xyz) * TONEMAP_EXPOSURE, 1.0);
}
"""

    const val RASTERIZER_WGSL: String = """
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
"""

    const val PRESENT_WGSL: String = """
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
"""

}
