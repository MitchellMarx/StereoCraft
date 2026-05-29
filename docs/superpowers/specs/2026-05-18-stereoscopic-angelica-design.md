# Stereoscopic-Angelica — Forge 1.7.10 standalone mod that depends on Angelica

**Date:** 2026-05-18
**Status:** Design approved; implementation plan to follow via `superpowers:writing-plans`.
**Target repository:** `C:\CODE\Stereoscopic-Angelica` (initialized; this spec is its first commit)
**Reference implementations:**
- Primary: `C:\CODE\Angelica-sbs2` branch `stereo-sbs-2` — the canonical port source (Forge 1.7.10, stereo feature shipped in-tree).
- Secondary: `C:\CODE\Stereoscopic` — Fabric 1.21.11 port of the same feature, source for user-facing UX preferences (e.g., the IPD slider).

## Overview

`StereoCraft: Stereoscopic 3D` is a Forge 1.7.10 client-side mod that adds side-by-side stereoscopic 3D rendering to Minecraft. It is functionally equivalent to the stereo SBS feature shipping inside `Angelica-sbs2`, but extracted into a standalone mod that **depends on** Angelica rather than modifying Angelica's source. Installing it alongside Angelica adds the SBS feature; removing it leaves Angelica unchanged.

The only stereo layout implemented in v0.1.0 is `SBS_HALF` — each eye rendered into half the screen width at the monoscopic aspect ratio, world horizontally compressed to fit. Viewer-side display setup (HMD, 3D TV, SBS-compatible monitor) is out of scope.

## Goals (v0.1.0 — full sbs2 feature parity)

- **A.** Core two-pass world rendering with a per-eye camera offset (mixin into `EntityRenderer`).
- **B.** HUD + GUI duplication across both eye halves.
- **C.** Hand renderer with per-eye depth handling.
- **D.** Iris shaderpack support: per-eye `RenderTargets` `eyeCount`, per-eye camera/matrix uniforms, hand-renderer per-eye depth, shadow pass rendered from mono camera (not LEFT-eye-offset).
- **E.** Sodium perf: skip 2nd-eye chunk uploads; skip Iris shadow pass on 2nd eye.
- **F.** Dynamic on/off toggle inside Sodium's video options page; IPD slider in the same group. Pipeline rebuild on toggle (`destroyPipeline → preparePipeline → loadRenderers`).
- **G.** Async OS-arrow cursor presented into each eye half on a separate GL/WGL context (Windows only in v0.1.0; pluggable `CursorBackend` interface with `NoOpCursorBackend` fallback).
- **H.** Cursor UX: cursor trap during gameplay; virtual cursor seeded at LEFT-eye center on GUI open; X delta halved to match horizontal compression.
- **I.** Achievement popup duplicated across eyes.
- **J.** `TIMER` / `tickDelta` frozen between LEFT and RIGHT eye `renderWorld` calls (so dynamic systems don't advance mid-frame).
- **K.** `ChromaticTooltips` re-armed between LEFT/RIGHT post-render events.
- **L.** lwjgl3ify-3.0.17 compatibility (SDL3 path, no GLFW assumptions).
- **M.** Clean shutdown: cursor present thread stopped on MC shutdown.

Items A–M map one-to-one to the feature commits in `Angelica-sbs2/stereo-sbs-2`. The implementation plan (next phase) sequences the work by feature area, not by reference commit.

## Non-Goals

- Other stereo layouts (`SBS_FULL`, `OU_HALF`, `OU_FULL`). `StereoMode` enum stays open to future extension; no code paths exist.
- Server-side anything. Mod is strictly client-side.
- macOS / Linux real OS-arrow cursor. Interface exists, only Windows is implemented.
- Migrating user config from a previous `Angelica-sbs2` install (those settings live on `AngelicaConfig`; users re-set them once in our UI).
- Upstream Angelica PRs. External mixins only. If a hookpoint turns out not to be externally mixinable, *that* failure becomes a candidate for an upstream PR — not a v0.1.0 scope cut.
- HUDCaching parity. Not touched.
- Modrinth / CurseForge publishing config (no project IDs filled until you decide to publish).
- GitHub Actions CI.
- Cross-platform deploy hook. The `copyToTestInstance` task assumes a Windows Prism path.

## Architecture

### Mod identity

- Display name: `StereoCraft: Stereoscopic 3D`
- Mod ID: `stereoscopic`
- Root Java package: `com.mitchellmarx.stereoscopic`
- Maven group: `com.mitchellmarx`
- License: `LGPL-3.0-only` (matches Angelica)

### Dependency stance

| Dependency | Version | Stance | Notes |
|---|---|---|---|
| Angelica | `com.github.GTNewHorizons:Angelica:[2.1,2.2)` | **Required** | Pinned to the 2.1.x patch stream. Brings shaded Iris-for-1.7.10 (`net.coderbot.iris.*`) and Sodium-for-1.7.10 (`me.jellysquid.mods.sodium.*`) inside its jar. |
| GTNHLib | `com.github.GTNewHorizons:GTNHLib:0.9.52:dev` | **Required** | Angelica needs it; also provides `jvmDowngrader` stubs at runtime. |
| Minecraft Forge | `10.13.4.1614` | **Required** | Standard 1.7.10 Forge. |
| UniMixins | (auto via `gtnhsettingsconvention`) | **Required** | Loads our `mixins.stereoscopic.json`. |
| Lwjgl3ify | not a direct dep | Environment | Test target is the Java 17 lwjgl3ify GTNH profile; our compat assumes lwjgl3ify-3.0.17 conventions (SDL3 path). |

Java syntax: `enableModernJavaSyntax = jvmDowngrader`, `jvmDowngraderMultiReleaseVersions = 17, 21, 25`, `jvmDowngraderStubsProvider = gtnhlib`. Matches Angelica's setup; lets the source use modern Java idioms while shipping a J8 bytecode entry plus J17/J21/J25 multi-release classes.

### Source tree

```
C:\CODE\Stereoscopic-Angelica\
├── build.gradle.kts                  (RFG via gtnhsettingsconvention 2.0.24)
├── settings.gradle.kts
├── gradle.properties                 (mod id/group, MC/Forge/Java config)
├── dependencies.gradle
├── repositories.gradle
├── LICENSE                           (LGPL-3.0-only)
├── README.md
├── docs/superpowers/
│   ├── specs/2026-05-18-stereoscopic-angelica-design.md   (this file)
│   └── plans/                                              (filled by writing-plans)
└── src/
    ├── main/
    │   ├── java/com/mitchellmarx/stereoscopic/
    │   │   ├── Stereoscopic.java                          (@Mod entrypoint)
    │   │   ├── config/StereoConfig.java                   (single Forge Configuration)
    │   │   ├── core/
    │   │   │   ├── StereoMode.java                        (OFF | SBS_HALF)
    │   │   │   ├── StereoState.java                       (singleton + Eye enum)
    │   │   │   ├── StereoHudMode.java                     (internal — not user-facing)
    │   │   │   ├── StereoDebugEye.java                    (internal — not user-facing)
    │   │   │   └── StereoGLSMBridge.java                  (calls Angelica's GLSM)
    │   │   └── cursor/
    │   │       ├── CursorBackend.java                     (interface)
    │   │       ├── WindowsCursorBackend.java              (only impl in v0.1.0)
    │   │       ├── NoOpCursorBackend.java                 (Mac/Linux fallback)
    │   │       ├── CursorPresentThread.java               (async WGL present)
    │   │       └── StereoCursor.java                      (main-thread facade)
    │   └── resources/
    │       ├── mcmod.info
    │       ├── mixins.stereoscopic.json
    │       ├── pack.mcmeta
    │       └── assets/stereoscopic/lang/en_US.lang
    └── mixin/java/com/mitchellmarx/stereoscopic/mixin/
        ├── minecraft/    (target Minecraft 1.7.10 classes)
        │   ├── MixinEntityRenderer_Stereo.java            (two-pass world loop)
        │   ├── MixinEntityRenderer_StereoCamera.java      (per-eye camera offset)
        │   ├── MixinFMLCommonHandler_Stereo.java
        │   ├── MixinFramebuffer_Stereo.java
        │   ├── MixinFramebuffer_AsyncCursor.java
        │   ├── MixinMinecraft_AsyncCursor.java
        │   └── MixinMinecraft_StereoAchievement.java
        ├── iris/         (target Angelica's bundled net.coderbot.iris.*)
        │   └── MixinHandRenderer_StereoDepth.java
        └── sodium/       (target Angelica's bundled me.jellysquid.mods.sodium.*)
            └── MixinSodiumGameOptionPages_StereoToggle.java
```

`mixin/iris/` and `mixin/sodium/` target classes shaded inside Angelica's jar. Mixin doesn't care about the source of a target class — it cares about the fully-qualified name at runtime. As long as Angelica ships those classes unrelocated under `net.coderbot.iris.*` and `me.jellysquid.mods.sodium.*` (verified at first build), external mixin works.

### External mixin layer — four integration touchpoints

`Angelica-sbs2` makes ~four "internal" edits outside the `com.gtnewhorizons.angelica.stereo.*` namespace. Each maps to an external mixin or to our own config in the new mod.

**1. `net.coderbot.iris.pipeline.HandRenderer` → `MixinHandRenderer_StereoDepth`**
- Original (sbs2 `e4345194`): in-line edit teaching `HandRenderer` to use a per-eye depth texture.
- Our shape: `@Mixin(HandRenderer.class)` with `@Inject` / `@Redirect` reproducing the edit. Reads the per-eye depth handle from our own `StereoState`.
- **Risk:** Low. Class FQN is stable across Angelica 2.1.x; the version pin enforces that.
- **Failure mode:** Mixin apply error at startup, mod fails to load with explicit reason. Never silent.

**2. `me.jellysquid.mods.sodium.client.gui.SodiumGameOptionPages` → `MixinSodiumGameOptionPages_StereoToggle`**
- Original (sbs2 `60b2f3f9` / `ff9f23f3`): adds an `OptionGroup` containing the SBS tick-box.
- Our shape: `@Inject` at the end of the relevant page-build method appending **two** option entries — the tick-box (parity with sbs2) and an IPD slider (new in this mod, matches the existing Fabric mod's UX).
- **Risk:** Low. The `SliderControl` constructor signature differs across Sodium revisions; we use whatever the pinned Angelica 2.1.x bundles and verify at first compile.
- **Toggle path:** reproduces sbs2's `destroyPipeline → preparePipeline → loadRenderers` dance verbatim — the pipeline must be rebuilt because Iris's `RenderTargets.eyeCount` is captured at pipeline init.

**3. `AngelicaConfig.stereoscopicMode` / `AngelicaConfig.stereoIpd` → `StereoConfig`**
- Adding config fields to Angelica's config via mixin is wrong shape (config UI, save/load, validation all assume Angelica owns its schema).
- Our shape: a single Forge `Configuration` file at `config/stereoscopic.cfg` with the two fields.

**4. `com.gtnewhorizons.angelica.mixins.Mixins` enum entries → `mixins.stereoscopic.json`**
- That enum is Angelica-internal.
- Our shape: standard 1.7.10 mixin config registered through UniMixins. Zero coupling to Angelica's mixin enum.

Risks flagged upfront, not deferred:

- Iris and Sodium classes ship inside Angelica's jar **unrelocated** (verified in `Angelica-sbs2`'s source). If Angelica's release pipeline ever relocates those packages under `com.gtnewhorizons.angelica.shaded.*`, our external mixins break. The `[2.1,2.2)` pin guards this within the 2.1.x patch stream. A 2.2.0 bump would require explicit review.
- `Iris.reload()` is `public static` on `net.coderbot.iris.Iris` — callable directly, no reflection required (see memory: no-reflection-by-yarn-name).

### Config & state

**Persisted fields (two, in `config/stereoscopic.cfg` via Forge `Configuration`):**

- `StereoConfig.stereoscopicMode : StereoMode` — `OFF | SBS_HALF`, default `OFF`. Backed by Sodium UI tick-box.
- `StereoConfig.stereoIpd : float` — eye separation in meters, default `0.064f`. Backed by Sodium UI integer slider in millimeters (range `0..500`, step `1`, default `64`).

No other user-facing knobs. `StereoHudMode` and `StereoDebugEye` exist as **internal** runtime state, not as config knobs.

**`StereoState`** (direct port of `com.gtnewhorizons.angelica.stereo.StereoState`):

```java
public final class StereoState {
    public static final StereoState INSTANCE = new StereoState();
    public enum Eye { LEFT, RIGHT }

    private StereoMode frameMode = StereoMode.OFF;   // snapshot at beginFrame()
    private Eye currentEye = null;                   // null = mono / not in stereo block
    private float frameIpd = 0.064f;                 // snapshot at beginFrame()

    public void beginFrame() {                       // called by EntityRenderer mixin
        frameMode = StereoConfig.stereoscopicMode;
        frameIpd = StereoConfig.stereoIpd > 0f ? StereoConfig.stereoIpd : 0.064f;
    }

    public boolean isActive()      { return frameMode != StereoMode.OFF; }
    public int  stereoEyeCount()   { return isActive() ? 2 : 1; }   // method, not field
    public Eye  getCurrentEye()    { return currentEye; }
    public void setCurrentEye(Eye e) { currentEye = e; }

    // LEFT = +ipd/2, RIGHT = -ipd/2 — do NOT flip these (memory note)
    public float getEyeOffset() {
        if (currentEye == null) return 0f;
        float half = frameIpd * 0.5f;
        return currentEye == Eye.LEFT ? +half : -half;
    }
}
```

**Toggle path** (`MixinSodiumGameOptionPages_StereoToggle`, sbs2-faithful):

```java
(opts, value) -> {
    StereoMode newMode = value ? StereoMode.SBS_HALF : StereoMode.OFF;
    if (StereoConfig.stereoscopicMode == newMode) return;
    StereoConfig.stereoscopicMode = newMode;
    StereoConfig.save();
    StereoState.INSTANCE.beginFrame();             // refresh frame cache NOW
    if (AngelicaConfig.enableIris && Minecraft.getMinecraft().theWorld != null) {
        Iris.getPipelineManager().destroyPipeline();
        Iris.getPipelineManager().preparePipeline(Iris.getCurrentDimensionName());
        Minecraft.getMinecraft().renderGlobal.loadRenderers();
    }
}
```

### Build setup

**Toolchain.** RFG via `com.gtnewhorizons.gtnhsettingsconvention 2.0.24`.

**`gradle.properties` (load-bearing values):**

```properties
gtnh.settings.blowdryerTag = 0.2.2
modName = StereoCraft: Stereoscopic 3D
modId = stereoscopic
modGroup = com.mitchellmarx.stereoscopic
useModGroupForPublishing = false
minecraftVersion = 1.7.10
forgeVersion = 10.13.4.1614
channel = stable
mappingsVersion = 12
enableModernJavaSyntax = jvmDowngrader
jvmDowngraderMultiReleaseVersions = 17, 21, 25
jvmDowngraderStubsProvider = gtnhlib
usesMixins = true
separateMixinSourceSet = mixin
mixinsPackage = mixin
mixinPlugin =
coreModClass =
containsMixinsAndOrCoreModOnly = false
accessTransformersFile =
includeWellKnownRepositories = true
modrinthRelations = required-project:angelica
curseForgeRelations = requiredDependency:angelica;requiredDependency:gtnhlib
disableSpotless = true
```

**`dependencies.gradle`:**

```groovy
dependencies {
    implementation("com.github.GTNewHorizons:Angelica:[2.1,2.2)") { transitive = false }
    api("com.github.GTNewHorizons:GTNHLib:0.9.52:dev")

    compileOnly("org.projectlombok:lombok:1.18.42") { transitive = false }
    annotationProcessor("org.projectlombok:lombok:1.18.42")
    compileOnly("org.jetbrains:annotations:26.0.2")

    testImplementation("org.junit.jupiter:junit-jupiter:6.0.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
```

**Deploy hook** — `copyToTestInstance` Gradle task, modeled on the existing Fabric mod's pattern:

- After `build` (RFG packages a remapped jar; no `remapJar` task in the Fabric sense — the standard `jar` output works).
- Pre-delete stale `stereoscopic-*.jar` in the target dir (avoids duplicate-mod errors).
- Copy `build/libs/stereoscopic-<version>.jar` into the target dir.
- Skip silently if the target dir does not exist (so the build works on machines without this Prism profile).

Target path (overridable via `-Pstereoscopic.deployDir=...`):

```
C:\Users\felix\AppData\Roaming\PrismLauncher\instances\GTNH-daily-2026-05-17+520-mmcprism-java17-25\.minecraft\mods
```

**No `runClient`. No `devOnlyNonPublishable` dev mods (NEI etc.).** Testing happens in the Prism profile via the deploy hook.

## Testing

**1. JUnit (pure logic only):**

- `StereoStateTest` — `getEyeOffset()` sign convention (LEFT = +ipd/2, RIGHT = −ipd/2); `isActive()` reflects `frameMode`; `beginFrame()` snapshots `StereoConfig`.
- `StereoModeTest` — `valueOf()` round-trip (so a hand-edited config file doesn't NPE on load).

No mock-Iris / mock-Sodium tests. The prior agent shipped broken integration behind passing mocked tests (memory: prior agent fabrications); we will not repeat that.

**2. Build-time integrity:**

- Mixin annotation processor resolves all mixin targets at compile (RFG default). Class-not-found in Angelica's `[2.1,2.2)` jar fails the build, not the runtime.
- Spotless disabled (matches Angelica).
- Pre-build verification step: unzip the resolved Angelica `:dev` jar once and grep for `net.coderbot.iris.pipeline.HandRenderer` and `me.jellysquid.mods.sodium.client.gui.SodiumGameOptionPages` to confirm unrelocated FQNs. 5 seconds; prevents a class of confusing "mixin target not found" errors.

**3. Manual test plan** — owned by Mitchell, in the Prism GTNH profile. Document at `docs/superpowers/notes/v0.1.0-manual-test.md`. Covers:

- A. Mono baseline (no regression vs vanilla GTNH).
- B. SBS toggle on (screen splits, both eyes render, no pipeline crash).
- C. SBS toggle off (returns to mono within one frame).
- D. IPD slider (0 → 64 → 200 → 500 mm; eye separation tracks).
- E. Iris shaderpack with SBS on (both eyes render with shaders; hand correct depth; shadows from mono camera per memory).
- F. Async cursor (Windows only): visible in both eyes, X delta halved, mouse trap during gameplay.
- G. HUD/GUI duplication (F3, chat, inventory, achievement popup).
- H. Clean shutdown (cursor present thread terminates; no orphaned JVM).

## Open items / risks

- **Angelica artifact shape verification.** Once the build resolves `[2.1,2.2)`, confirm by jar inspection that Iris/Sodium classes are unrelocated. If they aren't, fall back to a hybrid approach: small upstream PR exposing the hookpoints we need, plus our external mixins for the rest.
- **`SliderControl` signature.** Verify at first compile against the pinned Angelica's bundled Sodium.
- **2.2.0+ Angelica upgrade.** Out of v0.1.0 scope, but flagged so the version bump triggers a review pass on mixin targets.
- **No config migration from `Angelica-sbs2`.** Users re-set the toggle and slider once.
