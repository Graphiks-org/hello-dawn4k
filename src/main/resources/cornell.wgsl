// Minimal path tracer for the classic Cornell box.
//
// Geometry is a flat array of parallelogram quads; materials are pure Lambertian
// plus a single emissive quad on the ceiling. Direct light uses explicit light
// sampling (next-event estimation); indirect bounces use cosine-weighted sampling
// with Russian roulette. One invocation resolves one pixel, all samples included,
// and writes linear HDR radiance to a storage buffer for host-side tonemapping.

const PI: f32 = 3.14159265358979;
const SURFACE_EPSILON: f32 = 1e-4;
const MAX_BOUNCES: u32 = 5u;
const MISSED: u32 = 0xffffffffu;

struct Camera {
    position: vec4f,
    right: vec4f,
    up: vec4f,
    forward: vec4f, // xyz: unit forward direction, w: tan of the half vertical fov
}

struct Quad {
    origin: vec4f,     // xyz: corner, w unused
    edge1: vec4f,      // xyz: first edge
    edge2: vec4f,      // xyz: second edge
    albedo: vec4f,     // rgb: diffuse reflectance
    emissive: vec4f,   // rgb: emitted radiance
    normalArea: vec4f, // xyz: unit normal, w: parallelogram area
}

struct Params {
    quadCount: u32,
    width: u32,
    height: u32,
    samples: u32,
    light: vec4u, // x: index of the single emissive quad
    frame: vec4u, // x: 1-based frame index; radiance holds the progressive mean over frames
}

@group(0) @binding(0) var<storage, read> quads: array<Quad>;
@group(0) @binding(1) var<storage, read_write> radiance: array<vec4f>;
@group(0) @binding(2) var<uniform> params: Params;
@group(0) @binding(3) var<uniform> camera: Camera;

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

fn rayQuad(ro: vec3f, rd: vec3f, q: Quad) -> f32 {
    let againstEdge2 = cross(rd, q.edge2.xyz);
    let det = dot(q.edge1.xyz, againstEdge2);
    let invDet = 1.0 / det;
    let toOrigin = ro - q.origin.xyz;
    let u = dot(toOrigin, againstEdge2) * invDet;
    if (u < 0.0 || u > 1.0) {
        return -1.0;
    }
    let againstEdge1 = cross(toOrigin, q.edge1.xyz);
    let v = dot(rd, againstEdge1) * invDet;
    // Parallelogram quad, not a triangle: accept the full u,v unit square.
    if (v < 0.0 || v > 1.0) {
        return -1.0;
    }
    let t = dot(q.edge2.xyz, againstEdge1) * invDet;
    if (t < SURFACE_EPSILON) {
        return -1.0;
    }
    return t;
}

struct Hit {
    index: u32,
    t: f32,
}

fn closestHit(ro: vec3f, rd: vec3f) -> Hit {
    var best = MISSED;
    var bestT = 1e30;
    for (var i = 0u; i < params.quadCount; i++) {
        let t = rayQuad(ro, rd, quads[i]);
        if (t > 0.0 && t < bestT) {
            bestT = t;
            best = i;
        }
    }
    return Hit(best, bestT);
}

fn occluded(ro: vec3f, rd: vec3f, maxDistance: f32) -> bool {
    for (var i = 0u; i < params.quadCount; i++) {
        let t = rayQuad(ro, rd, quads[i]);
        if (t > 0.0 && t < maxDistance) {
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

fn sampleLightPoint(rng: ptr<function, Rng>) -> vec3f {
    let light = quads[params.light.x];
    let u = rngFloat(rng);
    let v = rngFloat(rng);
    return light.origin.xyz + light.edge1.xyz * u + light.edge2.xyz * v;
}

fn trace(pixel: vec2u, rng: ptr<function, Rng>) -> vec3f {
    let jitter = vec2f(rngFloat(rng), rngFloat(rng));
    let uv = (vec2f(pixel) + jitter) / vec2f(f32(params.width), f32(params.height));
    let ndc = vec2f(uv.x * 2.0 - 1.0, 1.0 - 2.0 * uv.y);
    let aspect = f32(params.width) / f32(params.height);
    var rd = normalize(
        camera.right.xyz * (ndc.x * camera.forward.w * aspect)
            + camera.up.xyz * (ndc.y * camera.forward.w)
            + camera.forward.xyz
    );
    var ro = camera.position.xyz;

    var color = vec3f(0.0);
    var throughput = vec3f(1.0);

    for (var bounce = 0u; bounce < MAX_BOUNCES; bounce++) {
        let hit = closestHit(ro, rd);
        if (hit.index == MISSED) {
            break;
        }
        let surface = quads[hit.index];
        if (surface.emissive.x + surface.emissive.y + surface.emissive.z > 0.0) {
            // Next-event estimation already covers light seen after a bounce;
            // only count the light the camera rays reach directly.
            if (bounce == 0u) {
                color += throughput * surface.emissive.xyz;
            }
            break;
        }
        let position = ro + rd * hit.t;
        var normal = surface.normalArea.xyz;
        if (dot(normal, rd) > 0.0) {
            normal = -normal;
        }

        // Next-event estimation: one shadow ray to a random point on the light.
        let light = quads[params.light.x];
        let lightPoint = sampleLightPoint(rng);
        let toLight = lightPoint - position;
        let distanceSquared = dot(toLight, toLight);
        let distance = sqrt(distanceSquared);
        let lightDirection = toLight / distance;
        let cosSurface = dot(normal, lightDirection);
        let cosLight = dot(light.normalArea.xyz, -lightDirection);
        if (cosSurface > 0.0 && cosLight > 0.0) {
            let shadowOrigin = position + normal * SURFACE_EPSILON;
            if (!occluded(shadowOrigin, lightDirection, distance - 2.0 * SURFACE_EPSILON)) {
                color += throughput * surface.albedo.xyz * light.emissive.xyz
                    * (cosSurface * cosLight * light.normalArea.w / (PI * distanceSquared));
            }
        }

        // Cosine-weighted bounce for indirect light.
        throughput *= surface.albedo.xyz;
        rd = cosineWeightedDirection(normal, rngFloat(rng), rngFloat(rng));
        ro = position + normal * SURFACE_EPSILON;

        // Russian roulette from the third bounce.
        if (bounce >= 2u) {
            let survival = max(throughput.r, max(throughput.g, throughput.b));
            if (rngFloat(rng) > survival) {
                break;
            }
            throughput /= survival;
        }
    }
    return color;
}

@compute @workgroup_size(8, 8)
fn main(@builtin(global_invocation_id) gid: vec3u) {
    if (gid.x >= params.width || gid.y >= params.height) {
        return;
    }
    let pixel = vec2u(gid.xy);
    let index = pixel.y * params.width + pixel.x;
    // Mix the frame index into the seed: with a frame-invariant seed every dispatch
    // retraces the same sample sequence, so progressive accumulation stalls at
    // samples-per-frame effective spp (dumps at 100 and 400 frames were identical).
    var rng = Rng(index + 1u + params.frame.x * 0x9E3779B9u);
    _ = rngNext(&rng); // discard one draw to wash out low-bit seed artifacts
    var accumulated = vec3f(0.0);
    for (var sample = 0u; sample < params.samples; sample++) {
        accumulated += trace(pixel, &rng);
    }
    // Progressive mean over frames: frame 1 overwrites, later frames converge.
    let frameMean = accumulated / f32(params.samples);
    let previous = radiance[index].xyz;
    let weight = 1.0 / f32(params.frame.x);
    radiance[index] = vec4f(previous + (frameMean - previous) * weight, 1.0);
}
