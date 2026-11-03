# hello-dawn4k — Cornell box

Projet minimal qui utilise le toolkit [dawn4k](https://github.com/Graphiks-org/dawn4k)
(bindings Kotlin/JVM pour Dawn/WebGPU) pour rendre la fameuse **Cornell box** :
l'architecture de la [démo Cornell officielle WebGPU](https://webgpu.github.io/webgpu-samples/?sample=cornell)
portée sur dawn4k — des photons convergent une lightmap par surface, et deux
rendus commutables la consultent.

- murs rouge (gauche) et vert (droite), le reste blanc
- deux boîtes blanches tournées, une haute et une basse
- une lampe au plafond ; la radiosity photon (sémantiques du sample) converge
  dans une lightmap indépendamment de la caméra
- tonemapping Reinhard (exposition 0.5) + gamma 2.2, comme leur tonemapper

## Lancer

```bash
./gradlew runWindow
```

Prérequis : JDK 25 (le toolchain Gradle le télécharge si besoin) et un GPU
ou un adaptateur logiciel Metal/Vulkan.

| Entrée | Action |
| --- | --- |
| glisser (bouton gauche) | orbiter autour de la boîte |
| molette | zoomer |
| `M` | basculer rasterizer ↔ raytracer |
| `R` | remettre la caméra par défaut |
| `Échap` ou croix | fermer |

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
| `src/main/resources/common.wgsl` | module partagé (quads, intersection, RNG, caméra), concaténé avant chaque passe |
| `src/main/resources/radiosity.wgsl` | photons → lightmap (atomiques, sémantiques du sample) |
| `src/main/resources/raytracer.wgsl` | vue ray-tracée : un rayon primaire, échantillonne la lightmap |
| `src/main/resources/rasterizer.wgsl` | vue rasterizée : MVP sur maille indexée, lightmap au fragment |
| `src/main/resources/present.wgsl` | tonemap plein écran (l'équivalent de leur tonemapper) |
| `src/main/resources/hud.wgsl` | passe HUD : quads texturés alpha-blendés |
| `src/main/kotlin/org/graphiks/hellodawn4k/CornellBox.kt` | la scène : quads, matériaux, boîtes tournées |
| `src/main/kotlin/org/graphiks/hellodawn4k/Camera.kt` | base de caméra partagée + contrôles orbitaux |
| `src/main/kotlin/org/graphiks/hellodawn4k/Mat4.kt` | perspectives/lookAt colonne-majeur pour le rasterizer |
| `src/main/kotlin/org/graphiks/hellodawn4k/WindowMain.kt` | fenêtre GLFW, session, les trois passes GPU |
| `src/main/kotlin/org/graphiks/hellodawn4k/Hud.kt` | HUD : layout Kalligraphie + atlas de glyphes GPU |
| `src/main/kotlin/org/graphiks/hellodawn4k/Png.kt` | encodeur PNG minimal (sans dépendance) |
| `src/main/kotlin/org/graphiks/hellodawn4k/NoiseMetric.kt` | comparaison de bruit local entre dumps |

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
