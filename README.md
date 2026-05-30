# StereoCraft: Stereoscopic 3D (NeoForge edition)

Side-by-side (SBS) stereoscopic 3D rendering for Minecraft **1.21.1** on **NeoForge**, with per-eye world rendering through **Sodium** and **Iris**. Splits the Minecraft window into left and right eye views with a configurable interpupillary distance (IPD), producing a stereoscopic image for 3D TVs, 3D monitors, SBS-capable HMDs, and VR passthrough virtual monitors.

This is the NeoForge sibling of [StereoCraft Fabric](https://github.com/MitchellMarx/Stereoscopic) and [StereoCraft Angelica](https://github.com/MitchellMarx/Stereoscopic-Angelica).

## Requirements

- Minecraft **1.21.1**
- NeoForge **21.1.228** or compatible
- Java **21**
- [Sodium](https://modrinth.com/mod/sodium) **0.6.13** (hard dependency)

## Optional

- [Iris](https://modrinth.com/mod/iris) **1.8.12** for partial shader support — see *Known limitations* below.

## Installation

1. Install NeoForge 21.1.228+ for Minecraft 1.21.1.
2. Install Sodium 0.6.13 and (optionally) Iris 1.8.12 into your `mods/` folder.
3. Drop `stereoscopic-neoforge-vX.Y.Z.jar` into `mods/`.
4. Launch the game.

## Configuration

v0.1.0 ships with **file-based configuration only**. Sodium 0.6's options-page API doesn't accept third-party pages (that arrived in 0.7+).

Edit `config/stereoscopic-options.json` (created with defaults on first launch):

```json
{
    "mode": "SBS_HALF",
    "ipd": 0.064,
    "convergence": 4.0,
    "swapEyes": false
}
```

- **`mode`** — `OFF` or `SBS_HALF`.
- **`ipd`** — interpupillary distance in **meters**. Anthropomorphic range 0.055 – 0.075. Default 0.064 (64 mm).
- **`convergence`** — off-axis convergence distance in blocks. Objects at this depth sit at the screen plane (zero parallax). 0 = parallel-axis (no frustum shear). Default 4.
- **`swapEyes`** — flip per-eye assignment for reverse-SBS displays / VR virtual-monitor setups.

Restart the game after editing.

## What works

- ✅ World rendering split into left / right eye halves with IPD-shifted cameras
- ✅ Off-axis frustum convergence
- ✅ Sodium chunk-graph reuse across the two eyes (RIGHT eye's chunk BFS is skipped — LEFT's output feeds RIGHT's render)
- ✅ Mouse look-X correctly halved under SBS_HALF
- ✅ Virtual cursor seeded at left-half center when a GUI opens
- ✅ Win32 async cursor present thread (Windows only — cursor visible in both eye halves at low FPS)
- ✅ Shader pipeline rebuild on stereo toggle (when Iris is loaded)
- ✅ Iris shadow pass runs once per frame from mono camera (matches both eyes)
- ✅ Iris per-eye uniforms (camera position, matrix history) — TAA / motion vectors / motion blur stay coherent per eye

## Known limitations / v0.1.0 deferrals

- **Per-eye Iris colortex banking is not yet implemented** (`MixinRenderTargets` and `MixinFinalPassRenderer` deferred to v0.1.x). Under shaders, both eyes sample the same colortex bank — TAA / reflections / SSCB will cross-contaminate between eyes. Pure-vanilla stereo (shaders off) is unaffected.
- **HUD overlay renders mono across the full window**, not per-eye. Without a per-eye GUI wrap each physical eye sees half of the HUD. Will be fixed in v0.1.x.
- **No in-game options UI.** Edit `config/stereoscopic-options.json` and restart. Sodium options-page integration deferred to v0.1.x.
- **Voxy compat deferred**: no NeoForge Voxy build with iris module currently exists. The community port `j-shelfwood/voxy-neoforge` excludes its iris module from the jar.
- **Sable compat deferred**: implicit sub-level rendering works through the standard `LevelRenderer` path; explicit per-eye colortex coordination + mixin-priority declarations land alongside `MixinRenderTargets`.
- **Async cursor presents Windows-only**: cursor mirror uses Win32 cursor APIs. Linux/macOS shows the cursor in only one eye while a GUI is open.

## Compatibility notes

- **Create** (`create-1.21.1`): contraptions render per-eye through the normal `LevelRenderer` path with no special handling.
- **EuphoriaPatcher**: shader pack loads; world stereo works; per-eye reflections degraded until `MixinRenderTargets` lands.

## Differences from the Fabric / Angelica siblings

- **`StereoBlur` is not present.** The Fabric mod's `StereoBlur` works around an MC 1.21.6+ regression where `GameRenderer.renderBlur()` routes through `CommandEncoder.createRenderPass` and ignores classic glScissor. MC 1.21.1 still uses the older `PostChain` blur path which respects glScissor, so the workaround isn't needed.
- **Sodium options-page integration is not present** (see above).
- **No `SBS_FULL` / `OUDA_FULL` / `OUDA_HALF` modes.** The Angelica mod has these as prototype-debugging cruft; the canonical feature set (Fabric mod's) only ships `OFF` / `SBS_HALF`. This mod matches the canonical set.

## License

LGPL-3.0-only. See [LICENSE](LICENSE).
