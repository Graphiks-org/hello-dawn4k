# hello-dawn4k — Cornell box

Projet **Kotlin Multiplatform** qui utilise le toolkit
[dawn4k](https://github.com/Graphiks-org/dawn4k) (bindings Dawn/WebGPU) pour
rendre la fameuse **Cornell box** sur desktop, Android et iOS : l'architecture
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
| Web (WebGPU natif du navigateur) | `./gradlew webGen && cd webApp && python3 -m http.server` → http://localhost:8000 | drag = orbite, molette/pincer = zoom, `M` ou bouton = mode, `R` = reset |

La page web est un fichier unique sans dépendance : `webGen` régénère `webApp/generated/`
(scène + shaders WGSL) **depuis les sources Kotlin** — même source de vérité que les
cibles natives, l'API WebGPU du navigateur remplaçant Dawn.

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
dessiné en quads alpha-blendés (`-Pcornell.hud=false` pour le désactiver).
Run fumée fini + dump du framebuffer, avec position orbitale et mode initial
optionnels :

```bash
# écrit cornell-window.png depuis azimut 0.7 rad, élévation 0.25 rad, rayon 2.6
./gradlew runWindow -Pcornell.frames=600 -Pcornell.orbit=0.7,0.25,2.6
# idem, en démarrant en mode raytracer
./gradlew runWindow -Pcornell.frames=600 -Pcornell.mode=raytracer
```

## Structure

| Fichier | Rôle |
| --- | --- |
| `src/commonMain/kotlin/…/Shaders.kt` | sources WGSL en constantes Kotlin (les frameworks natifs n'embarquent pas les ressources communes) |
| `src/commonMain/kotlin/…/CornellRenderer.kt` | radiosity + rasterizer + raytracer + tonemap, partagé par tous les hôtes |
| `src/commonMain/kotlin/…/CornellBox.kt`, `Camera.kt`, `Mat4.kt` | scène, caméra orbitale, perspectives/lookAt |
| `src/jvmMain/kotlin/…/WindowMain.kt` | hôte desktop GLFW (+ dump de fumée dans `Dump.kt`) |
| `src/jvmMain/kotlin/…/Hud.kt` | HUD desktop : layout Kalligraphie + atlas de glyphes GPU |
| `src/iosMain/kotlin/…/CornellViewController.kt` | hôte UIKit (DawnMetalView, toucher) |
| `androidApp/src/main/kotlin/…/CornellActivity.kt` | hôte Android (DawnSurfaceView, toucher) |
| `iosApp/project.yml` + `CornellApp/AppDelegate.swift` | app iOS générée par XcodeGen |
| `src/jvmMain/kotlin/…/Png.kt`, `NoiseMetric.kt` | encodeur PNG et métrique de bruit (desktop) |

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
  (`src/main/resources/fonts/OFL-1.1.txt`).
- Dépendances dawn4k et Kalligraphie : **MIT**.
