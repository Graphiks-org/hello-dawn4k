// Path tracer (ours, not part of the webgpu-samples port): iteratively traces
// camera rays with next-event estimation toward the light and cosine-weighted
// indirect bounces, progressively averaging into the radiance buffer.
// Scene helpers live in common.wgsl, concatenated before this file.

const MAX_BOUNCES: u32 = 5u;

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
        let lightPoint = light.origin.xyz + light.edge1.xyz * rngFloat(rng) + light.edge2.xyz * rngFloat(rng);
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
