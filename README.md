# hello-dawn4k — Cornell box

Projet minimal qui utilise le toolkit [dawn4k](https://github.com/Graphiks-org/dawn4k)
(bindings Kotlin/JVM pour Dawn/WebGPU) pour rendre la fameuse **Cornell box** :
un path tracer en compute shader WGSL, exécuté hors écran sur le GPU, dont le
résultat est écrit dans `cornell.png`.

- murs rouge (gauche) et vert (droite), le reste blanc
- deux boîtes blanches tournées, une haute et une basse
- une lampe au plafond, éclairage direct par échantillonnage de la lumière (NEE)
- rebonds indirects en échantillonnage pondéré par cosinus, avec roulette russe
- sortie linéaire HDR → tonemapping Reinhard + gamma 2.2 côté hôte

## Lancer

```bash
./gradlew run
```

Prérequis : JDK 25 (le toolchain Gradle le télécharge si besoin) et un GPU
ou un adaptateur logiciel Metal/Vulkan. L'image `cornell.png` est écrite à la
racine du projet.

## Structure

| Fichier | Rôle |
| --- | --- |
| `src/main/resources/cornell.wgsl` | le path tracer (compute shader) |
| `src/main/kotlin/org/graphiks/hellodawn4k/CornellBox.kt` | la scène : quads, matériaux, boîtes tournées |
| `src/main/kotlin/org/graphiks/hellodawn4k/Main.kt` | session toolkit, dispatch GPU, readback, tonemapping |
| `src/main/kotlin/org/graphiks/hellodawn4k/Png.kt` | encodeur PNG minimal (sans dépendance) |

La résolution, le nombre d'échantillons et la taille de workgroup se règlent
en tête de `Main.kt` (`WIDTH`, `HEIGHT`, `SAMPLES_PER_PIXEL`, `WORKGROUP_SIZE`).

## Validation

À chaque exécution, `Reference.kt` relance un mini path tracer **CPU** (même
scène, mêmes maths, échantillonnage indépendant) sur quelques pixels et compare
sa radiance à celle du GPU dans la tolérance de bruit Monte-Carlo. Les deux
implémentations doivent rester d'accord — c'est ce qui a permis de traquer un
test d'intersection quad/triangle lors du développement.

## Dépendances

`org.graphiks:dawn4k-toolkit-core:1.0.0-SNAPSHOT` (et `webgpu-descriptors`)
résolues depuis les snapshots Maven Central de l'organisation ; la bibliothèque
JVM embarque la bibliothèque native Dawn, rien d'autre à installer.
