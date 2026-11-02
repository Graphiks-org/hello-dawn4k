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
