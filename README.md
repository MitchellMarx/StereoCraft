# StereoCraft: Stereoscopic 3D (NeoForge edition)

Side-by-side (SBS) stereoscopic 3D rendering for Minecraft **1.21.1** on **NeoForge**, with full per-eye support under **Sodium** and **Iris** shaders. Splits the Minecraft window into left and right eye views with a configurable interpupillary distance (IPD), producing a stereoscopic image for 3D TVs, 3D monitors, SBS-capable HMDs, and VR passthrough virtual monitors.

This is the NeoForge sibling of [StereoCraft Fabric](https://github.com/MitchellMarx/Stereoscopic) and [StereoCraft Angelica](https://github.com/MitchellMarx/Stereoscopic-Angelica).

## Requirements

- Minecraft **1.21.1**
- NeoForge **21.1.228** or compatible
- Java **21**
- [Sodium](https://modrinth.com/mod/sodium) **0.6.13** (hard dependency)

## Optional

- [Iris](https://modrinth.com/mod/iris) **1.8.12** for shader support.
- Reverse-SBS displays / VR passthrough virtual monitors — toggle **Swap eyes** in the in-game options.

## Installation

1. Install NeoForge 21.1.228+ for Minecraft 1.21.1.
2. Install Sodium 0.6.13 and (optionally) Iris 1.8.12 into your `mods/` folder.
3. Drop `stereoscopic-neoforge-vX.Y.Z.jar` into `mods/`.
4. Launch the game.

## Configuration

Bind the **Open Stereoscopic Options** key under **Options → Controls → Stereoscopic 3D** (default unbound). Pressing the key opens a screen with:

- **Mode** — `OFF` or `SBS_HALF`. Mode changes trigger an Iris pipeline rebuild on the next frame (one-frame flash with shaders loaded).
- **IPD** — 55–75 mm, default 64 mm.
- **Convergence distance** — 0–32 blocks, default 4. 0 = parallel-axis (no shear).
- **Swap eyes** — flip per-eye assignment for reverse-SBS displays / VR virtual-monitor setups.
- **Apply / Cancel** — Apply commits the settings, persists to `config/stereoscopic-options.json`, and triggers the Iris rebuild if mode changed.

You can also edit `config/stereoscopic-options.json` manually — the format is straightforward JSON and changes round-trip cleanly through the options screen.

## Compatibility

- **Iris shaderpacks**: EuphoriaPatcher, BSL, Complementary tested. Per-eye colortex banking keeps each eye's temporal slots (PBR reflection cache, TAA history, screen-space colored blocklight) separate so eyes don't cross-contaminate.
- **Distant Horizons**: per-eye DH-projection shear restoration under shaders.
- **Sable** (Create Aeronautics sub-level physics): sub-levels render per-eye with correct parallax. Our `MixinGameRenderer` is declared `priority = 2000` so our two-pass world wrap applies OUTSIDE Sable's `clip_overwrite/GameRendererMixin` — Sable's clip-overwrite setup/teardown runs per eye, not once across both eyes.
- **Create**: contraptions render per-eye through the normal `LevelRenderer` path.
- **HUDCaching** (NeoForge port if installed): HUD cache invalidates per eye-half via our `Pass.GUI` wrap.

## Known limitations

- **Async cursor presents Windows-only**: cursor mirror uses Win32 cursor APIs. Linux/macOS shows the cursor in only one eye while a GUI is open.
- **Iris pipeline first-frame rebuild**: on world load with shaders already on, Iris loads its pipeline once before our stereo state is published. We auto-trigger a rebuild on the first stereo-active frame — you may see a one-frame flash.
- **Sodium chunk-visibility cull**: BFS skipped on the second eye (chunks visible to one eye are visible to the other at typical IPD). At extreme IPD / convergence near a chunk boundary you might see a missing-chunk artifact for one frame.

## Differences from the Fabric / Angelica siblings

- **`StereoBlur` is not present.** The Fabric mod's `StereoBlur` works around an MC 1.21.6+ regression where `GameRenderer.renderBlur()` routes through `CommandEncoder.createRenderPass` and ignores classic glScissor. MC 1.21.1 still uses the older `PostChain` blur path which respects glScissor, so the workaround isn't needed.
- **Options page is a standalone screen, not a Sodium tab.** Sodium 0.6 has no third-party options-page API (added in 0.7+). Rather than couple to the modpack-specific `sodiumoptionsapi` add-on, we ship a keybinding-driven standalone screen.
- **No `SBS_FULL` / `OUDA_FULL` / `OUDA_HALF` modes.** The Angelica mod has these as prototype-debugging cruft; the canonical feature set (Fabric mod's) only ships `OFF` / `SBS_HALF`. This mod matches the canonical set.

## License

LGPL-3.0-only. See [LICENSE](LICENSE).
