# Stereoscopic (StereoCraft)

Side-by-side (SBS) stereoscopic 3D rendering for Minecraft — for 3D TVs, 3D
monitors, and SBS-compatible / VR virtual-screen displays. The world and HUD are
rendered twice per frame (one view per eye) into the left and right halves of the
framebuffer, with a configurable IPD and convergence distance.

This `main` branch is an **overview only** — it intentionally has no code and no
shared history with the implementation branches. The actual implementations (one
per mod loader / Minecraft version) live on the branches listed below.

## Branches

Each loader / Minecraft version is a long-lived branch. There is no shared
history between them — `main` is an orphan overview, and each implementation
branch is independent.

| Branch           | Loader   | Minecraft | Notes                                              |
| ---------------- | -------- | --------- | -------------------------------------------------- |
| `main`           | —        | —         | This overview (orphan, no code).                   |
| `fabric-26.3`    | Fabric   | 26.3      | Unobfuscated; renderpearl GPU layer, SDL3 window, Java 25. |
| `fabric-26.2`    | Fabric   | 26.2      | Unobfuscated, non-remapping Loom, Java 25.         |
| `fabric-26.1.2`  | Fabric   | 26.1.2    | Unobfuscated (no mappings), non-remapping Loom, Java 25. |
| `fabric-1.21.11` | Fabric   | 1.21.11   | Yarn mappings, remapping Loom.                     |
| `neoforge`       | NeoForge | 1.21.1    | Parchment / Mojang mappings.                       |
| `angelica`       | Angelica | 1.7.10    | Angelica/GTNH-era rendering.                       |

> MC 26.x ships unobfuscated (real names + parameter names), so the `fabric-26.*`
> branches drop Yarn/intermediary entirely and use the non-remapping
> `net.fabricmc.fabric-loom` line. See each branch's git log for the porting
> notes (render-state extraction, GUI rewrite, projection UBO, LWJGL 3.4, etc.).

All branches share the same architecture and per-eye strategy, adapted to each
loader's rendering pipeline, and the same feature set (OFF / SBS_HALF / SBS_FULL,
IPD, convergence, swap eyes). Releases are tagged per branch (`<branch>-vX.Y.Z`).

## Automated stereo tests

Each branch has a client test that builds a fixed scene (wool pillars, a sheep,
a particle), screenshots it in every stereo mode, and measures each eye's image:
markers present in both eyes, swap mirroring, parallax sign and depth scaling,
equal convergence shift for terrain / entities / particles, SBS_FULL vs HALF
aspect, identical eyes at zero IPD. The same checks run with a shader pack (TAA
on and off) and on a far scene drawn only by Distant Horizons / Voxy.

| Branch | Command |
| ------ | ------- |
| `fabric-*` | `./gradlew stereoTest` |
| `neoforge` | `./gradlew runSelftest` |
| `angelica` | `./gradlew runClient25 -Pstereoscopic.selftest` |

## Project structure (version branches)

```
src/main/java/com/mitchellmarx/stereoscopic/
  core/      StereoState, StereoOptions, StereoMode, StereoMath — per-frame eye
             state, options, and the convergence/IPD math.
  render/    PerEyeRenderer (per-eye loop, scratch FB, viewport/scissor),
             ViewportMath (eye rects), StereoBlur (per-eye GUI blur).
  cursor/    Async stereo cursor (Windows): a present thread that draws a cursor
             sprite into both eye halves.
  gui/       StereoOptionsPage — Sodium options-page integration.
  compat/    Optional-dependency facades (Iris, Sodium, Voxy, Distant Horizons).
  mixin/
    minecraft/  Core hooks: per-eye world + GUI render, camera, framebuffer,
                mouse, window present.
    iris/       Per-eye Iris shader-pipeline support (render targets, uniforms,
                shadow pass, final pass, DH compat).
    sodium/     Per-eye Sodium terrain (chunk-graph) handling.
```

## License

LGPL-3.0-only (see the version branches for the `LICENSE` file).
