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
    loop {
        let candidate = vec3f(rngFloat(rng), rngFloat(rng), rngFloat(rng)) * 2.0 - vec3f(1.0);
        if (dot(candidate, candidate) < 1.0) {
            return candidate;
        }
    }
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
