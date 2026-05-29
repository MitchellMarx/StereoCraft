# Stereoscopic-NeoForge — design

**Date:** 2026-05-29
**Status:** Draft, pre-implementation
**Author:** Mitchell Marx (via Claude brainstorming)

## Purpose

A third sibling of the **StereoCraft: Stereoscopic 3D** mod family — side-by-side
(SBS) stereoscopic 3D rendering, targeting **Minecraft 1.21.1 / NeoForge** so it
runs in the *All of Create — Aeronautics* modpack and the wider NeoForge 1.21.1 +
Sodium 0.6 ecosystem.

Existing siblings:

- `C:\CODE\Stereoscopic` — Fabric 1.21.11 / Sodium 0.8.11 / Iris 1.10.7 (canonical)
- `C:\CODE\Stereoscopic-Angelica` — Forge 1.7.10 / GTNH's Angelica renderer

This project is `C:\CODE\Stereoscopic-Neoforge`.

## Target platform (fixed)

| Component | Pinned version |
|---|---|
| Minecraft | 1.21.1 |
| Loader | NeoForge 21.1.228 |
| Java | 21 |
| Sodium | sodium-neoforge 0.6.13 |
| Iris | iris-neoforge 1.8.12 (optional dep) |
| Test modpack | All of Create — Aeronautics v1.7 (CurseForge) |

These are constraints, not suggestions. The mod targets the modpack the user
plays; if APIs differ from the Fabric mod's targets, the mod adapts, the targets
don't.

## Feature set

Mirrors the canonical Fabric mod feature set exactly:

| Feature | Default |
|---|---|
| Mode | `OFF`, `SBS_HALF` |
| IPD | 64 mm (0.064 m), range 55–75 mm |
| Convergence | 4 blocks (off-axis), 0 = parallel-axis |
| Swap eyes | off |
| HUD | Per-eye duplicated |
| Cursor mirror (Windows) | on |

**Out of scope** (these exist in `Stereoscopic-Angelica` as prototype cruft and
must not appear here): `SBS_FULL`, `OUDA_FULL`, `OUDA_HALF`, HUD modes
(`DUPLICATE`/`STRETCH`/`HIDE`), debug force-eye selector.

## Third-party integrations (all in scope)

| Integration | Treatment |
|---|---|
| **Sodium options page** | Hook Sodium 0.6's native options-API entry point. No hard dep on `sodiumoptionsapi` or `reeses-sodium-options` (both are present in the test modpack and will pick our page up automatically when they are). |
| **Iris (shaders)** | `compileOnly` + soft-loaded mixin config (`required: false`). Per-eye colortex banking + DH-projection shear restoration mirrored from the Fabric mod. Tested under EuphoriaPatcher (BSL / Complementary derivatives ship with the test modpack). |
| **Voxy (LOD chunks)** | `VoxyEyeRebindHooks` ported. The user installs a compatible Voxy NeoForge build into the test modpack for verification. |
| **Distant Horizons** | Per-eye projection-shear restoration inside the Iris compat path (DH terrain alignment under shaders). The user installs a compatible DH NeoForge build for verification. |
| **EuphoriaPatcher** | Tested shader target; called out in the README. |
| **Sable** (`sable-neoforge-1.21.1-1.2.2`) | Sub-level rendering must work in both eyes. Sable's `sublevel_render/LevelRendererMixin` hooks `LevelRenderer.renderLevel` to inject sub-level draws; because our `PerEyeRenderer` calls the world-render path twice per frame, sub-levels render per-eye automatically. New compat module `compat/sable/` handles the non-automatic parts: per-eye mixin ordering against Sable's three `GameRendererMixin`s (in `camera/new_camera_types/`, `entity/entities_turn_with_sub_levels/`, and `clip_overwrite/`), and propagating per-eye colortex banking through Sable's sub-level draw into Iris's pipeline. |
| **Create** (`create-1.21.1-6.0.10`) | Has `LevelRendererMixin` + `GameRendererMixin` + `CameraMixin` for contraption rendering. Lower port risk than Sable: Create exists on Fabric and the Fabric Stereoscopic mod has presumably been verified under it, so the patterns are known. Treat as implicit compat — sanity-check moving contraptions in the manual test checklist; create a `compat/create/` module only if a specific failure surfaces. |
| **AoC Core / `create-aeronautics-bundled`** | No client-render mixins. Pure logic; no compat work needed. |

## Approach

**Strict 1:1 port** of `C:\CODE\Stereoscopic` (the Fabric mod) to NeoForge,
sibling-repo style. Same package layout, same class names, same JSON config
format. Loader-specific files swap (`fabric.mod.json` → `neoforge.mods.toml`,
`FabricLoader` config-dir lookup → `FMLPaths`). Mixins targeting Sodium 0.8 /
Iris 1.10 internals get rewritten against Sodium 0.6.13 / Iris 1.8.12 internals;
the *strategy* (per-eye banking, two-pass render, skip-second-eye-BFS,
single-shadow-pass) is preserved. Pure-Java packages (`core/`, `render/`,
`cursor/`) port with only package/import changes.

Other approaches considered and rejected:

- **Shared `stereoscopic-core` library** — requires restructuring the two
  existing repos for a benefit that doesn't materialize until the second cross-
  port bug-fix. Out of scope.
- **Spec-driven rewrite** — throws away the proven Fabric implementation; higher
  risk of behavioral drift.

## Project layout

```
C:\CODE\Stereoscopic-Neoforge\
├── build.gradle.kts                 (NeoGradle / ModDevGradle, mirrors Fabric mod's structure)
├── gradle.properties                (versions pinned per the table above)
├── settings.gradle.kts
├── LICENSE                          (LGPL-3.0-only)
├── README.md                        (mirrors the Fabric mod's README; NeoForge-specific install instructions)
├── docs/
│   ├── screenshot.png
│   └── superpowers/
│       └── specs/2026-05-29-stereoscopic-neoforge-design.md   (this file)
└── src/main/
    ├── java/com/mitchellmarx/stereoscopic/
    │   ├── Stereoscopic.java                            (@Mod entry point)
    │   ├── core/
    │   │   ├── StereoMath.java
    │   │   ├── StereoMode.java                          ({OFF, SBS_HALF})
    │   │   ├── StereoOptions.java
    │   │   ├── StereoOptionsIO.java
    │   │   └── StereoState.java
    │   ├── render/
    │   │   ├── PerEyeRenderer.java
    │   │   ├── StereoBlur.java
    │   │   └── ViewportMath.java
    │   ├── gui/
    │   │   └── StereoOptionsPage.java                   (Sodium options-API integration)
    │   ├── cursor/
    │   │   ├── CursorBackend.java
    │   │   ├── CursorPresentThread.java
    │   │   ├── NoOpCursorBackend.java
    │   │   ├── StereoCursor.java
    │   │   └── WindowsCursorBackend.java
    │   ├── compat/
    │   │   ├── iris/   (per-eye colortex banking + DH-shear helpers)
    │   │   ├── sable/  (sub-level render coordination + mixin-order shims)
    │   │   ├── sodium/ (Sodium-specific call sites)
    │   │   └── voxy/
    │   │       └── VoxyEyeRebindHooks.java
    │   └── mixin/
    │       ├── minecraft/                               (~11 mixins; see Fabric mod for exact list)
    │       ├── sodium/
    │       │   └── MixinSodiumWorldRenderer.java
    │       └── iris/                                    (9 mixins; see Fabric mod)
    └── resources/
        ├── META-INF/neoforge.mods.toml
        ├── stereoscopic.mixins.json                     (required: true)
        ├── stereoscopic-iris.mixins.json                (required: false)
        └── assets/stereoscopic/
            ├── icon.png
            └── lang/en_us.json
```

## Port-risk classification

Each Fabric-mod component is classified by adaptation effort:

| Component | Effort | Notes |
|---|---|---|
| `core/` | **Direct port** | `FabricLoader.getConfigDir()` → `FMLPaths.CONFIGDIR.get()`. JSON format unchanged. |
| `render/` | **Minor** | Possible MC 1.21.1 vs 1.21.11 `RenderSystem` signature deltas. |
| `gui/StereoOptionsPage` | **Minor** | Wire-up uses Sodium 0.6's native options entry-point mechanism. Verify exact API at implementation time. |
| `cursor/` | **Direct port** | LWJGL 3 + Win32 JNI is identical across loaders. |
| `compat/iris/` | **Minor → Real** | Strategy preserved; Iris 1.8 target classes verified against the actual jar. |
| `compat/sable/` | **New** | No precedent in Fabric / Angelica siblings (neither modpack ships Sable). Surface is small: a couple of mixin-order priority declarations + a per-eye flush around Sable's sub-level draw to keep Iris's colortex banking aligned. |
| `compat/sodium/` | **Minor** | Thin pass-through to mixins. |
| `compat/voxy/` | **Minor** | Verified against the installed Voxy NeoForge build. |
| `mixin/minecraft/` | **Minor** | MC 1.21.1 mapping rebase; no architectural shifts. |
| `mixin/sodium/MixinSodiumWorldRenderer` | **Real rewrite** | Sodium 0.6.13 internals differ from 0.8.11. Effect preserved: gate second-eye BFS, expose per-eye render-pass hooks. |
| `mixin/iris/` (9 mixins) | **Real, mostly** | Pipeline lifecycle shape stable; class internals likely rewritten. |

## Runtime behavior

Identical to the Fabric mod. Documented here only as deltas:

- **Mode toggle**: triggers a one-frame Iris pipeline rebuild (screen flashes).
- **Per-frame, stereo OFF**: every mixin guards on `StereoMode.isActive()` and
  falls through — no measurable overhead.
- **Per-frame, SBS_HALF**: LEFT eye pass (sheared camera, half-viewport,
  per-eye colortex bank) → RIGHT eye pass (mirror; Sodium BFS skipped; Iris
  shadow pass skipped — shared mono shadow map). HUD drawn once per eye at full
  res. Final swap if `swapEyes` set.
- **Cursor on Windows**: ~120 Hz mirror thread synthesizes the OS cursor into
  the *other* eye-half when a GUI is open. Non-Windows: documented limitation,
  cursor appears in only one eye.

## Error handling

| Failure | Behavior |
|---|---|
| Iris not installed | Iris mixin config (`required: false`) does not load. Stereo still works without shaders. |
| Voxy not installed | `compat/voxy` guards on `ModList.get().isLoaded("voxy")`. |
| Sodium version mismatch | Hard pin `[0.6.13,0.7)` in `neoforge.mods.toml`. NeoForge refuses to start the game. No graceful degrade. |
| Iris version mismatch | `breaks` clause on `<1.8.12 \|\| >=1.9`. |
| Iris pipeline rebuild fails | Log + revert mode to `OFF`. Same as Fabric mod. |
| Non-Windows (cursor) | `NoOpCursorBackend`. Documented limitation. |

## Testing

- **Unit**: JUnit 5 on `core/` (math, options IO round-trip). Mirrors the
  Fabric mod's test layout.
- **Manual**: deploy hook copies the remapped jar into the Aeronautics
  instance's `mods/` folder on each `./gradlew build`. Manual checklist:
  - `OFF` → `SBS_HALF` toggle (verify pipeline rebuild flash)
  - IPD slider 55 → 64 → 75 mm
  - Convergence 0 (parallel-axis) and 4 (off-axis) compared
  - Swap eyes toggle
  - HUD positioning in both halves
  - Cursor in open GUIs (chat, inventory, options)
  - EuphoriaPatcher shaders on / off
  - Distant Horizons on / off (after user installs)
  - Voxy on / off (after user installs)
  - Aeronautics ship / sub-level in view (verify parallax on the sub-level, mixin ordering with Sable, no eye-half misalignment) — both with and without shaders
  - Create contraption in motion in view (mechanical bearing rotation, train carriage, deployer chain) under stereo — sanity check; first sign of breakage triggers a `compat/create/` module
- **No CI** for v0.1.0. Matches the existing siblings.

## Versioning and release

- v0.1.0 ships when the manual checklist passes in Aeronautics under
  shaders, Voxy, and DH.
- Mod ID: `stereoscopic` (same as Fabric — these are platform variants of the
  same mod, not competing IDs).
- Maven coordinates: `com.mitchellmarx:stereoscopic-neoforge:0.1.0`.
- License: LGPL-3.0-only.

## Open questions for implementation phase

These are *implementation* unknowns, not design ambiguities — they don't
require resolution before writing the plan, but the plan should call them out
as investigation steps:

1. Sodium 0.6.13 options-page entry-point mechanism — service file, annotated
   class, or both? Verified against the actual jar before the `gui/` package
   port.
2. Iris 1.8.12 internal class layout vs 1.10.7 — each of the 9 Iris mixins gets
   its target signatures re-resolved against the 1.8.12 jar before its rewrite.
3. Voxy NeoForge 1.21.1 — exact build the user installs determines the compat
   surface. The Fabric mod's `VoxyEyeRebindHooks` API gets re-resolved against
   it.
4. Distant Horizons NeoForge 1.21.1 — same; the DH-shear restoration target in
   Iris 1.8 may live in a different module than the Fabric mod expects.
5. **Sable mixin ordering**: Sable declares three `GameRendererMixin`s (in
   `camera/new_camera_types/`, `entity/entities_turn_with_sub_levels/`, and
   `clip_overwrite/`). Our `mixin/minecraft/MixinGameRenderer` may compete for
   the same `@At` targets. The compat module sets explicit `@Mixin(priority=…)`
   values once the conflict points are mapped; verified by running the
   Aeronautics instance under stereo with a flying ship in view.
6. **Sable + Iris colortex banking**: Sable's `sublevel_render/LevelRendererMixin`
   injects sub-level draws inside `LevelRenderer.renderLevel`. Verify those draws
   read/write the same per-eye Iris colortex bank as the main world (not a
   stale or mono bank) — otherwise sub-level reflections / TAA cross-contaminate
   between eyes.
