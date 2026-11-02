// Photon-mapping radiosity ported from the webgpu-samples Cornell box: photons
// emitted by the light bounce through the scene and atomically accumulate energy
// into a per-quad lightmap (one array layer per quad), entirely independent of
// the camera. The quad layout, intersection and RNG duplicate cornell.wgsl on
// purpose: WGSL has no includes and both files stay self-contained.
//
// Derived from sample/cornell/radiosity.wgsl of https://github.com/webgpu/webgpu-samples
// (BSD-3-Clause, Copyright 2019 WebGPU Samples Contributors) — full license text
// in THIRD_PARTY_LICENSES/webgpu-samples-LICENSE.txt.

struct Quad {
    origin: vec4f,
    edge1: vec4f,
    edge2: vec4f,
    albedo: vec4f,
    emissive: vec4f,
    normalArea: vec4f,
}

struct PhotonParams {
    accumulationToLightmapScale: f32,
    accumulationBufferScale: f32,
    quadCount: u32,
    lightIndex: u32,
    seeds: vec4u, // fresh CPU-provided random seeds, re-seeded every frame
}

@group(0) @binding(0) var<storage, read> quads: array<Quad>;
@group(0) @binding(1) var<storage, read_write> accumulation: array<atomic<u32>>;
@group(0) @binding(2) var lightmap: texture_storage_2d_array<rgba16float, write>;
@group(0) @binding(3) var<uniform> params: PhotonParams;

const PHOTON_ENERGY: f32 = 100000.0;
const PHOTON_BOUNCES: u32 = 4u;
const LIGHT_ABSORPTION: f32 = 0.5;
const SURFACE_EPSILON: f32 = 1e-4;
const PI: f32 = 3.14159265358979;
const MISSED: u32 = 0xffffffffu;

struct Rng {
    state: u32,
}

fn rngNext(rng: ptr<function, Rng>) -> u32 {
    (*rng).state = (*rng).state * 747796405u + 2891336453u;
    let word = (((*rng).state >> (((*rng).state >> 28u) + 4u)) ^ (*rng).state) * 277803737u;
    return (word >> 22u) ^ word;
}

fn rngFloat(rng: ptr<function, Rng>) -> f32 {
    return f32(rngNext(rng)) / 4294967296.0;
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

fn texelBase(u: f32, v: f32, quadIndex: u32) -> u32 {
    let dims = textureDimensions(lightmap);
    let coord = min(vec2u(vec2f(u, v) * vec2f(dims)), dims - vec2u(1u));
    return 3u * (coord.x + dims.x * coord.y + dims.x * dims.y * quadIndex);
}

@compute @workgroup_size(256)
fn radiosity(@builtin(global_invocation_id) gid: vec3u) {
    var rng = Rng(
        params.seeds.x ^ (gid.x * 747796405u) ^ (gid.y * 2891336453u) ^ (gid.z * 2654435761u),
    );
    _ = rngNext(&rng);

    let light = quads[params.lightIndex];
    let origin = light.origin.xyz
        + light.edge1.xyz * rngFloat(&rng)
        + light.edge2.xyz * rngFloat(&rng);
    var rayOrigin = origin;
    // The light quad's stored normal faces the room; photons launch into that hemisphere.
    var rayDir = cosineWeightedDirection(light.normalArea.xyz, rngFloat(&rng), rngFloat(&rng));
    var color = PHOTON_ENERGY * vec3f(1.0, 0.88, 0.7);

    for (var bounce = 0u; bounce <= PHOTON_BOUNCES; bounce++) {
        let hit = closestHit(rayOrigin, rayDir);
        if (hit.index == MISSED) {
            break;
        }
        let quad = quads[hit.index];
        let position = rayOrigin + rayDir * hit.t;

        // Indirect-only lightmap: the first (direct) bounce is skipped — the view
        // shader adds analytic direct lighting itself, so the lightmap must not
        // double-count it.
        if (bounce > 0u) {
            var energy = color * LIGHT_ABSORPTION;
            let base = texelBase(hit.u, hit.v, hit.index);
            atomicAdd(&accumulation[base + 0u], u32(energy.r + 0.5));
            atomicAdd(&accumulation[base + 1u], u32(energy.g + 0.5));
            atomicAdd(&accumulation[base + 2u], u32(energy.b + 0.5));
        }

        color *= quad.albedo.xyz * (1.0 - LIGHT_ABSORPTION);
        var normal = quad.normalArea.xyz;
        if (dot(normal, rayDir) > 0.0) {
            normal = -normal;
        }
        rayOrigin = position + normal * SURFACE_EPSILON;
        rayDir = cosineWeightedDirection(normal, rngFloat(&rng), rngFloat(&rng));
    }
}

@compute @workgroup_size(8, 8)
fn publishLightmap(@builtin(global_invocation_id) gid: vec3u, @builtin(workgroup_id) wid: vec3u) {
    let dims = textureDimensions(lightmap);
    let quadIndex = wid.z;
    let coord = gid.xy;
    if (all(coord < dims)) {
        let base = 3u * (coord.x + dims.x * coord.y + dims.x * dims.y * quadIndex);
        var color = vec3f(
            f32(atomicLoad(&accumulation[base + 0u])),
            f32(atomicLoad(&accumulation[base + 1u])),
            f32(atomicLoad(&accumulation[base + 2u])),
        );
        // 3x3 gather blur: photon noise dominates the indirect lightmap at these
        // densities, and a one-texel blur costs nine loads against a full re-trace.
        var samples = 1.0;
        for (var dy = -1; dy <= 1; dy++) {
            for (var dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dy == 0) {
                    continue;
                }
                let nx = i32(coord.x) + dx;
                let ny = i32(coord.y) + dy;
                if (nx < 0 || ny < 0 || nx >= i32(dims.x) || ny >= i32(dims.y)) {
                    continue;
                }
                let nbase = 3u * (u32(nx) + dims.x * u32(ny) + dims.x * dims.y * quadIndex);
                color += vec3f(
                    f32(atomicLoad(&accumulation[nbase + 0u])),
                    f32(atomicLoad(&accumulation[nbase + 1u])),
                    f32(atomicLoad(&accumulation[nbase + 2u])),
                );
                samples += 1.0;
            }
        }
        color = color / samples * params.accumulationToLightmapScale;

        textureStore(lightmap, coord, quadIndex, vec4f(color, 1.0));

        // Near u32 saturation the whole buffer is scaled down (see the sample's trick).
        if (params.accumulationBufferScale != 1.0) {
            let scaled = color * params.accumulationBufferScale + vec3f(0.5);
            atomicStore(&accumulation[base + 0u], u32(scaled.r));
            atomicStore(&accumulation[base + 1u], u32(scaled.g));
            atomicStore(&accumulation[base + 2u], u32(scaled.b));
        }
    }
}
