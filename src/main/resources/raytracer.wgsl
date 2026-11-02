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
