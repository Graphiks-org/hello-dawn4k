# hello-dawn4k — Cornell box

Projet **Kotlin Multiplatform** qui utilise le toolkit
[dawn4k](https://github.com/Graphiks-org/dawn4k) (bindings Dawn/WebGPU) pour
rendre la fameuse **Cornell box** sur desktop, Android, iOS et le web : l'architecture
de la [démo Cornell officielle WebGPU](https://webgpu.github.io/webgpu-samples/?sample=cornell)
portée sur dawn4k — des photons convergent une lightmap par surface, et deux
rendus commutables la consultent.

- murs rouge (gauche) et vert (droite), le reste blanc
- deux boîtes blanches tournées, une haute et une basse
- une lampe au plafond ; la radiosity photon (sémantiques du sample) converge
  dans une lightmap indépendamment de la caméra
- tonemapping Reinhard (exposition 0.5) + gamma 2.2, comme leur tonemapper

## Lancer

| Plateforme | Commande | Entrées |
| --- | --- | --- |
| macOS / Linux / Windows (JVM) | `./gradlew runWindow` | drag = orbite, molette = zoom, `M` = mode, `R` = reset, `Échap` |
| Android (Vulkan, API 28+) | `./gradlew :androidApp:assembleDebug` puis installer l'APK | 1 doigt = orbite, pincer = zoom, double tap = mode |
| iOS / simulateur (Metal) | `cd iosApp && xcodegen generate && xcodebuild -scheme CornellIOS -sdk iphonesimulator build` | 1 doigt = orbite, pincer = zoom, double tap = mode |
| Web (WebGPU natif du navigateur) | `./gradlew :wasmJsBrowserDevelopmentExecutableDistribution` puis servir `build/dist/wasmJs/developmentExecutable/` (`python3 -m http.server`) → http://localhost:8000 | drag = orbite, molette/pincer = zoom, `M` ou bouton = mode, `R` = reset |

La cible web est du **Kotlin/WasmJS pur — zéro JavaScript écrit à la main** :
`webgpu-browser` expose le `navigator.gpu` du navigateur derrière les mêmes
interfaces `GPUDevice`/`GPUQueue` que Dawn, même source de vérité que les
cibles natives.

Le projet Xcode (`iosApp/CornellBox.xcodeproj`) est généré depuis `project.yml`
par [XcodeGen](https://github.com/yonaskolb/XcodeGen) et reste ignoré ; son script
de pré-compilation appelle `embedAndSignAppleFrameworkForXcode`, qui construit le
framework Kotlin `CornellBox.framework` (Dawn statique embarquée par les klibs
publiées — vérifié par édition de liens + exécution sur simulateur iOS 27).

Prérequis desktop : JDK 25 (le toolchain Gradle le télécharge si besoin) et un
GPU ou un adaptateur logiciel Metal/Vulkan.

La touche **`M`** bascule entre les deux rendus du sample, au-dessus d'une
passe **radiosity** qui tourne en permanence : des photons émis par la lampe
déposent leur énergie (post-albédo, ordre du sample) dans des atomiques u32,
publiés vers une lightmap rgba16float 256² × une couche par quad (rescaling
anti-overflow du sample, seeds CPU fraîches par frame).

- **rasterizer** (défaut) — maille de quads indexée, MVP classique, fragment
  échantillonnant la lightmap ; depth buffer, ~900 fps ;
- **raytracer** — un rayon primaire par pixel sur la même lightmap, à la
  résolution physique de la fenêtre.

Un **HUD** en haut à gauche affiche mode, framerate, photons accumulés, état
de la caméra et adaptateur GPU : le texte est mis en page par
[Kalligraphie](https://github.com/Graphiks-org/Kalligraphie) (facade
`EditableLineLayoutSession` + shaping HarfBuzz), rastérisé en coverage A8 sur
CPU à partir d'une Liberation Sans embarquée, servi dans un atlas R8 et
dessiné en quads alpha-blendés.

## Tests

`./gradlew jvmTest` fait tourner le **vrai renderer en headless** (session
Dawn sans fenêtre, format RGBA8Unorm offscreen) sur des photons seedés :

- photons accumulés > 0 en rasterizer et raytracer ;
- couleurs structurelles : mur gauche rouge-dominant, mur droit vert-dominant ;
- convergence : le bruit local (écart-type 3×3 sur le mur du fond) chute
  entre 1 et 30 frames — la régression historique d'accumulation gelée ;
- le raytracer n'est pas noir (régression de l'uniform caméra jamais écrite) ;
- la chaîne glyphe complète (fonte → shaping → rasterisation → atlas →
  passe GPU alpha) laisse des pixels allumés sur une cible hors écran.

Les assertions sont structurelles ou statistiques, donc portables entre GPU ;
un hash d'or ne le serait pas (il varie avec le driver).

## Structure

| Fichier | Rôle |
| --- | --- |
| `src/commonMain/kotlin/…/Shaders.kt` | sources WGSL en constantes Kotlin (les frameworks natifs n'embarquent pas les ressources communes) |
| `src/commonMain/kotlin/…/CornellRenderer.kt` | radiosity + rasterizer + raytracer + tonemap, partagé par tous les hôtes |
| `src/commonMain/kotlin/…/CornellBox.kt`, `Camera.kt`, `Mat4.kt` | scène, caméra orbitale, perspectives/lookAt |
| `src/glyphMain/kotlin/…/Hud.kt` | chaîne glyphe commune : shaping Kalligraphie, atlas R8 GPU, quads alpha (jvm + iOS + web ; Android rend son texte nativement et ses AAR Kalligraphie partagent un namespace) |
| `src/jvmMain/kotlin/…/WindowMain.kt` | hôte desktop GLFW |
| `src/jvmMain/kotlin/…/DesktopHudFont.kt` | capture de fonte desktop (extraction Liberation Sans + repli scan macOS) |
| `src/mobileMain/kotlin/…/CornellLoop.kt` | boucle de rendu partagée Android/iOS (`CornellHostState`) |
| `src/wasmJsMain/kotlin/…/WebMain.kt` | hôte web Kotlin/WasmJS pur |
| `src/iosMain/kotlin/…/CornellViewController.kt` | hôte UIKit (DawnMetalView, toucher) |
| `androidApp/src/main/kotlin/…/CornellActivity.kt` | hôte Android (DawnSurfaceView, toucher) |
| `iosApp/project.yml` + `CornellApp/AppDelegate.swift` | app iOS générée par XcodeGen |
| `src/jvmTest/kotlin/…` | tests de rendu headless seedés + métrique de bruit |

## Dépendances

`org.graphiks:dawn4k-toolkit-core:1.0.0-SNAPSHOT`, `dawn4k-toolkit-glfw`,
`kalligraphie` (+ `api`, `shaping`, `layout`, `raster-cpu` — le POM parapluie
les déclare runtime-only) et `webgpu-descriptors`, résolues depuis les
snapshots Maven Central de l'organisation ; les bibliothèques JVM embarquent
Dawn, GLFW et HarfBuzz, rien d'autre à installer.

## Licences

- Ce projet : **MIT** (voir `LICENSE`).
- La radiosity (`radiosity.wgsl`) est dérivée de
  [webgpu-samples](https://github.com/webgpu/webgpu-samples) (**BSD-3-Clause**,
  texte complet dans `THIRD_PARTY_LICENSES/webgpu-samples-LICENSE.txt`).
- La police Liberation Sans embarquée est sous **OFL-1.1**
  (`src/jvmMain/resources/fonts/OFL-1.1.txt`).
- Dépendances dawn4k et Kalligraphie : **MIT**.
