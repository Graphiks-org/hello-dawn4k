# hello-dawn4k — Cornell box

A **Kotlin Multiplatform** project using the
[dawn4k](https://github.com/Graphiks-org/dawn4k) toolkit (Dawn/WebGPU bindings)
to render the famous **Cornell box** on desktop, Android, iOS and the web: the
architecture of the [official WebGPU Cornell sample](https://webgpu.github.io/webgpu-samples/?sample=cornell)
ported to dawn4k — photons converge a per-surface lightmap, and two switchable
views read it.

- red (left) and green (right) walls, everything else white
- two rotated white boxes, one tall, one short
- a ceiling light; the photon radiosity (the sample's semantics) converges a
  lightmap independently of the camera
- Reinhard tonemapping (0.5 exposure) + 2.2 gamma, like their tonemapper

## Run

| Platform | Command | Inputs |
| --- | --- | --- |
| macOS / Linux / Windows (JVM) | `./gradlew runWindow` | drag = orbit, wheel = zoom, `M` = mode, `R` = reset, `Esc` |
| Android (Vulkan, API 28+) | `./gradlew :androidApp:assembleDebug`, then install the APK | one finger = orbit, pinch = zoom, double tap = mode |
| iOS / simulator (Metal) | `cd iosApp && xcodegen generate && xcodebuild -scheme CornellIOS -sdk iphonesimulator build` | one finger = orbit, pinch = zoom, double tap = mode |
| Web (the browser's native WebGPU) | `./gradlew :wasmJsBrowserDevelopmentExecutableDistribution`, then serve `build/dist/wasmJs/developmentExecutable/` (`python3 -m http.server`) → http://localhost:8000 | drag = orbit, wheel/pinch = zoom, `M` or button = mode, `R` = reset |

The web target is **pure Kotlin/WasmJS — zero handwritten JavaScript**:
`webgpu-browser` exposes the browser's `navigator.gpu` behind the same
`GPUDevice`/`GPUQueue` interfaces Dawn provides, one source of truth for every
target.

The Xcode project (`iosApp/CornellBox.xcodeproj`) is generated from `project.yml`
by [XcodeGen](https://github.com/yonaskolb/XcodeGen) and stays ignored; its
pre-build script calls `embedAndSignAppleFrameworkForXcode`, which builds the
`CornellBox.framework` Kotlin framework (static Dawn embedded by the published
klibs — verified by linking + running on the iOS 27 simulator).

Desktop prerequisites: JDK 25 (the Gradle toolchain downloads it if needed)
and a GPU or a software Metal/Vulkan adapter.

The **`M`** key switches between the sample's two views, over a **radiosity**
pass that runs continuously: photons emitted from the light deposit their
energy (post-albedo, the sample's order) into u32 atomics, published to an
rgba16float 256² lightmap with one layer per quad (the sample's anti-overflow
rescaling, fresh CPU seeds every frame).

- **rasterizer** (default) — indexed quad mesh, classic MVP, a fragment
  sampling the lightmap; depth buffer, ~900 fps;
- **raytracer** — one primary ray per pixel over the same lightmap, at the
  window's physical resolution.

The **status** (mode, framerate, accumulated photons, camera, GPU adapter) is
drawn everywhere by the **shared glyph chain**:
[Kalligraphie](https://github.com/Graphiks-org/Kalligraphie) (the
`EditableLineLayoutSession` facade + HarfBuzz shaping) shapes the lines, a CPU
A8 rasterization feeds an R8 atlas, and alpha-blended quads draw the text over
the scene — Liberation Sans embedded as bytes (Base64, like the WGSL). All
five targets display through the same path.

## Tests

`./gradlew jvmTest` runs the **real renderer headless** (a window-less Dawn
session, RGBA8Unorm offscreen target) over seeded photons:

- accumulated photons > 0 in both rasterizer and raytracer;
- structural colors: the left wall is red-dominant, the right wall
  green-dominant;
- convergence: local noise (3×3 luma standard deviation over the back wall)
  drops between 1 and 30 frames — the historical frozen-accumulation
  regression;
- the raytracer is not black (the never-written camera uniform regression);
- the whole glyph chain (font → shaping → rasterization → atlas → alpha GPU
  pass) leaves lit pixels on an offscreen target.

Assertions are structural or statistical, so they are portable across GPUs; a
golden hash would not be (it varies with the driver).

## Structure

| File | Role |
| --- | --- |
| `src/commonMain/kotlin/…/Shaders.kt` | WGSL sources as Kotlin constants (native frameworks bundle no common resources) |
| `src/commonMain/kotlin/…/CornellRenderer.kt` | radiosity + rasterizer + raytracer + tonemap, shared by every host |
| `src/commonMain/kotlin/…/CornellBox.kt`, `Camera.kt`, `Mat4.kt` | scene, orbit camera, perspective/lookAt |
| `src/commonMain/kotlin/…/Hud.kt`, `GlyphOverlay.kt`, `LiberationSansBytes.kt` | shared glyph chain: Kalligraphie shaping, GPU R8 atlas, alpha quads — the status display of all five targets |
| `src/jvmMain/kotlin/…/WindowMain.kt` | desktop GLFW host |
| `src/mobileMain/kotlin/…/CornellLoop.kt` | Android/iOS shared render loop (`CornellHostState`) |
| `src/wasmJsMain/kotlin/…/WebMain.kt` | pure Kotlin/WasmJS web host |
| `src/iosMain/kotlin/…/CornellViewController.kt` | UIKit host (DawnMetalView, touch) |
| `androidApp/src/main/kotlin/…/CornellActivity.kt` | Android host (DawnSurfaceView, touch) |
| `iosApp/project.yml` + `CornellApp/AppDelegate.swift` | XcodeGen-generated iOS app |
| `src/jvmTest/kotlin/…` | seeded headless render tests + noise metric |

## Dependencies

`org.graphiks:dawn4k-toolkit-core:1.0.0-SNAPSHOT`, `dawn4k-toolkit-glfw`,
`org.graphiks.kalligraphie:kalligraphie` (+ `api`, `shaping`, `layout`,
`raster-cpu` — the umbrella POM declares the leaves runtime-only) and
`webgpu-descriptors`, resolved from the organization's Maven Central
snapshots; the JVM libraries embed Dawn, GLFW and HarfBuzz — nothing else to
install.

## Licenses

- This project: **MIT** (see `LICENSE`).
- The radiosity (`radiosity.wgsl`) derives from
  [webgpu-samples](https://github.com/webgpu/webgpu-samples) (**BSD-3-Clause**,
  full text in `THIRD_PARTY_LICENSES/webgpu-samples-LICENSE.txt`).
- The embedded Liberation Sans font is **OFL-1.1**
  (`THIRD_PARTY_LICENSES/LiberationSans-OFL-1.1.txt`).
- dawn4k and Kalligraphie dependencies: **MIT**.
