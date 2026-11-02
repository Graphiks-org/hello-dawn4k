// Lightmap view mode: one primary ray per pixel; the hit surface samples the
// radiosity lightmap converged by photon.wgsl and adds its own emission. No
// accumulation, no shadow rays — the camera moves freely over converged lighting.
// Quad layout, intersection, camera and RNG duplicate cornell.wgsl deliberately.

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
    light: vec4u,
    frame: vec4u,
}

struct Camera {
    position: vec4f,
    right: vec4f,
    up: vec4f,
    forward: vec4f,
}

@group(0) @binding(0) var<storage, read> quads: array<Quad>;
@group(0) @binding(1) var<storage, read_write> radiance: array<vec4f>;
@group(0) @binding(2) var<uniform> params: Params;
@group(0) @binding(3) var<uniform> camera: Camera;
@group(0) @binding(4) var lightmap: texture_2d_array<f32>;
@group(0) @binding(5) var smpl: sampler;

const SURFACE_EPSILON: f32 = 1e-4;
const MISSED: u32 = 0xffffffffu;
const PI: f32 = 3.14159265358979;

// Calibrates the indirect-only lightmap (mean ≈ 1) onto the physical brightness
// range of the path-traced mode; tuned against a 2400-spp reference dump.
const LIGHTMAP_GAIN = 0.8;

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

@compute @workgroup_size(8, 8)
fn main(@builtin(global_invocation_id) gid: vec3u) {
    if (gid.x >= params.width || gid.y >= params.height) {
        return;
    }
    let index = gid.y * params.width + gid.x;

    let jitter = vec2f(0.5, 0.5) / vec2f(f32(params.width), f32(params.height));
    let uv = (vec2f(gid.xy) + jitter) / vec2f(f32(params.width), f32(params.height));
    let ndc = vec2f(uv.x * 2.0 - 1.0, 1.0 - 2.0 * uv.y);
    let aspect = f32(params.width) / f32(params.height);
    let rd = normalize(
        camera.right.xyz * (ndc.x * camera.forward.w * aspect)
            + camera.up.xyz * (ndc.y * camera.forward.w)
            + camera.forward.xyz
    );

    let hit = closestHit(camera.position.xyz, rd);
    if (hit.index == MISSED) {
        radiance[index] = vec4f(0.0, 0.0, 0.0, 1.0);
        return;
    }
    let quad = quads[hit.index];
    let position = camera.position.xyz + rd * hit.t;
    var normal = quad.normalArea.xyz;
    if (dot(normal, rd) > 0.0) {
        normal = -normal;
    }

    // Analytic direct light: one shadow ray to a random point on the light,
    // the same estimator as the path-traced mode.
    var rng = Rng(index + 1u + params.frame.x * 0x9E3779B9u);
    _ = rngNext(&rng);
    let light = quads[params.light.x];
    let lightPoint = light.origin.xyz + light.edge1.xyz * rngFloat(&rng) + light.edge2.xyz * rngFloat(&rng);
    let toLight = lightPoint - position;
    let distanceSquared = dot(toLight, toLight);
    let distance = sqrt(distanceSquared);
    let lightDirection = toLight / distance;
    let cosSurface = dot(normal, lightDirection);
    let cosLight = dot(light.normalArea.xyz, -lightDirection);
    var direct = vec3f(0.0);
    if (cosSurface > 0.0 && cosLight > 0.0) {
        let shadowOrigin = position + normal * SURFACE_EPSILON;
        if (!occluded(shadowOrigin, lightDirection, distance - 2.0 * SURFACE_EPSILON)) {
            direct = light.emissive.xyz * (cosSurface * cosLight * light.normalArea.w / (PI * distanceSquared));
        }
    }

    // Indirect light comes from the photon lightmap (pre-albedo energy), scaled
    // by the receiving surface's albedo, same as the direct term.
    let indirect = textureSampleLevel(lightmap, smpl, vec2f(hit.u, hit.v), hit.index, 0.0).rgb;
    radiance[index] = vec4f(
        quad.albedo.xyz * (direct + indirect * LIGHTMAP_GAIN) + quad.emissive.xyz,
        1.0,
    );
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
