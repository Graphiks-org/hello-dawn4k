# hello-dawn4k — Cornell box

Projet minimal qui utilise le toolkit [dawn4k](https://github.com/Graphiks-org/dawn4k)
(bindings Kotlin/JVM pour Dawn/WebGPU) pour rendre la fameuse **Cornell box** :
un path tracer en compute shader WGSL, exécuté hors écran sur le GPU, dont le
résultat est écrit dans `cornell.png`.

- murs rouge (gauche) et vert (droite), le reste blanc
- deux boîtes blanches tournées, une haute et une basse
- une lampe au plafond, éclairage direct par échantillonnage de la lumière (NEE)
- rebonds indirects en échantillonnage pondéré par cosinus, avec roulette russe
- sortie linéaire HDR → tonemapping Reinhard + gamma 2.2, dans le shader de présentation
- caméra orbitale interactive dans la fenêtre (drag / molette / R / Échap)

## Lancer

```bash
# headless : rend 1024 spp et écrit cornell.png
./gradlew run
# fenêtre GLFW : convergence progressive en direct
./gradlew runWindow
```

La fenêtre accumule 4 échantillons par frame (60 fps ≈ 2400 spp en 10 s) et
affiche la moyenne via une passe de présentation plein écran. La caméra est
orbitale :

| Entrée | Action |
| --- | --- |
| glisser (bouton gauche) | orbiter autour de la boîte |
| molette | zoomer |
| `M` | basculer path tracing ↔ lightmap radiosity |
| `R` | remettre la caméra par défaut |
| `Échap` ou croix | fermer |

Chaque mouvement de caméra relance l'accumulation (l'image redevient bruitée
puis reconverge). La touche **`M`** cycle entre trois rendus, dont deux portés
de la [démo Cornell officielle WebGPU](https://webgpu.github.io/webgpu-samples/?sample=cornell)
(architecture alignée : `common.wgsl` partagé par concaténation, photons →
lightmap par surface, rasterizer MVP et raytracer) :

- **path tracing** — notre mode physique, accumulation progressive dans l'espace écran (grain transitoire au mouvement) ;
- **rasterizer** (le défaut du sample) — maille de quads indexée, MVP classique, fragment échantillonnant la lightmap ; depth buffer, ~900 fps ;
- **raytracer** — un rayon primaire par pixel sur la même lightmap.

Les trois partagent une passe **radiosity** qui converge en permanence,
indépendamment de la caméra : des photons émis par la lampe déposent leur
énergie (post-albédo, ordre du sample) dans des atomiques u32, publiés vers
une lightmap rgba16float 256² × une couche par quad (rescaling anti-overflow
du sample, seeds CPU fraîches par frame). Écarts assumés et documentés dans
les shaders : pas de back-face culling (windings de scène non uniformes) et
seeds de RNG mélangées à la frame.

Un **HUD** en haut à gauche affiche mode, framerate, échantillons ou photons
accumulés, état de la caméra et adaptateur GPU : le texte est mis en page par
[Kalligraphie](https://github.com/Graphiks-org/Kalligraphie) (facade
`EditableLineLayoutSession` + shaping HarfBuzz sur Menlo), rastérisé en
coverage A8 sur CPU, servi dans un atlas R8 et dessiné en quads
alpha-blendés (`-Dcornell.hud=false` pour le désactiver). Run fumée fini +
dump du framebuffer, avec position orbitale et mode initiaux optionnels :

```bash
# écrit cornell-window.png depuis azimut 0.7 rad, élévation 0.25 rad, rayon 2.6
./gradlew runWindow -Pcornell.frames=600 -Pcornell.orbit=0.7,0.25,2.6
# idem, en démarrant en mode lightmap (rasterizer ou raytracer)
./gradlew runWindow -Pcornell.frames=600 -Pcornell.mode=lightmap
./gradlew runWindow -Pcornell.frames=600 -Pcornell.mode=raytracer
```

Prérequis : JDK 25 (le toolchain Gradle le télécharge si besoin) et un GPU
ou un adaptateur logiciel Metal/Vulkan.

## Structure

| Fichier | Rôle |
| --- | --- |
| `src/main/resources/common.wgsl` | module partagé (quads, intersection, RNG, caméra), concaténé avant chaque passe |
| `src/main/resources/cornell.wgsl` | le path tracer (compute shader, accumulation progressive) |
| `src/main/resources/radiosity.wgsl` | photons → lightmap (atomiques, sémantiques du sample) |
| `src/main/resources/raytracer.wgsl` | vue ray-tracée : un rayon primaire, échantillonne la lightmap |
| `src/main/resources/rasterizer.wgsl` | vue rasterizée : MVP sur maille indexée, lightmap au fragment |
| `src/main/resources/present.wgsl` | tonemap plein écran (l'équivalent de leur tonemapper) |
| `src/main/resources/hud.wgsl` | passe HUD : quads texturés alpha-blendés |
| `src/main/kotlin/org/graphiks/hellodawn4k/CornellBox.kt` | la scène : quads, matériaux, boîtes tournées |
| `src/main/kotlin/org/graphiks/hellodawn4k/Camera.kt` | base de caméra partagée + contrôles orbitaux |
| `src/main/kotlin/org/graphiks/hellodawn4k/Main.kt` | session headless, dispatch GPU, readback, tonemapping |
| `src/main/kotlin/org/graphiks/hellodawn4k/WindowMain.kt` | fenêtre GLFW avec convergence en direct |
| `src/main/kotlin/org/graphiks/hellodawn4k/Hud.kt` | HUD : layout Kalligraphie + atlas de glyphes GPU |
| `src/main/kotlin/org/graphiks/hellodawn4k/Png.kt` | encodeur PNG minimal (sans dépendance) |
| `src/main/kotlin/org/graphiks/hellodawn4k/Reference.kt` | path tracer CPU miroir pour la validation numérique |

La résolution, le nombre d'échantillons et la taille de workgroup se règlent
en tête de `Main.kt` (`WIDTH`, `HEIGHT`, `SAMPLES_PER_PIXEL`, `WORKGROUP_SIZE`).

## Validation

À chaque exécution, `Reference.kt` relance un mini path tracer **CPU** (même
scène, mêmes maths, échantillonnage indépendant) sur quelques pixels et compare
sa radiance à celle du GPU dans la tolérance de bruit Monte-Carlo. Les deux
implémentations doivent rester d'accord — c'est ce qui a permis de traquer un
test d'intersection quad/triangle lors du développement.

## Dépendances

`org.graphiks:dawn4k-toolkit-core:1.0.0-SNAPSHOT`, `dawn4k-toolkit-glfw`,
`kalligraphie` (+ `api`, `shaping`, `layout`, `raster-cpu` — le POM parapluie
les déclare runtime-only) et `webgpu-descriptors`, résolues depuis les
snapshots Maven Central de l'organisation ; les bibliothèques JVM embarquent
Dawn, GLFW et HarfBuzz, rien d'autre à installer.

## Licences

- Ce projet : **MIT** (voir `LICENSE`).
- Le mode radiosity (`photon.wgsl`) est dérivé de
  [webgpu-samples](https://github.com/webgpu/webgpu-samples) (**BSD-3-Clause**,
  texte complet dans `THIRD_PARTY_LICENSES/webgpu-samples-LICENSE.txt`).
- La police Liberation Sans embarquée est sous **OFL-1.1**
  (`src/main/resources/fonts/OFL-1.1.txt`).
- Dépendances dawn4k et Kalligraphie : **MIT**.
