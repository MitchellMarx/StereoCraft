# Plan 1 — Scaffold + Core Stereo + Sodium Toggle

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce a working Forge 1.7.10 mod that depends on Angelica, splits the screen into LEFT/RIGHT eye halves when the Sodium video-options tick-box is enabled, and shifts the world camera per eye. No Iris support, no per-eye HUD work, no async cursor, no Sodium perf skips — those land in Plans 2/3/4.

**Tech Stack:** Java with `jvmDowngrader` (J21 source → J8/J17/J21/J25 multi-release jar), MixinBootstrap via UniMixins, Forge `Configuration` for persistence, JUnit 5 for unit tests.

---

## Porting principle (ALL plans)

**Canonical specification:** `git -C /c/CODE/Angelica-sbs2 diff master...stereo-sbs-2`. That diff IS the work. Nothing outside it is in scope. Nothing inside it is optional.

We are building an external mod that takes the sbs2 branch's modifications to Angelica and applies them against vanilla published Angelica (a hard runtime dependency). Two ways the diff manifests:

1. **NEW files in the diff** (the bulk of sbs2's work — stereo subpackage, FML+RFB transformers, etc.): port the file verbatim into our mod with package rename only. References to existing Angelica classes resolve naturally because Angelica is on our compile + runtime classpath. Drop nothing. Re-namespace nothing else.
2. **MODIFIED Angelica files in the diff** (~10 files, mostly small): we cannot edit Angelica's source. Apply the modification externally via the most surgical mechanism available:

| sbs2 diff target | Our delivery mechanism |
|---|---|
| Any vanilla-MC or shaded-Angelica class (`EntityRenderer`, `Framebuffer`, `SodiumGameOptionPages`, Iris classes, etc.) | **External Mixin** (`@Inject`/`@Redirect`/`@Overwrite`) — `src/mixin/java`, registered in `mixins.stereoscopic.json` |
| `Angelica/loading/fml/compat/CompatHandlers.java` (adds class transformers to FML's chain) | **Our own `IFMLLoadingPlugin`** (`StereoscopicCoreMod`) that registers the same transformer in its `getASMTransformerClass()` |
| `Angelica/loading/rfb/AngelicaRfbPlugin.java` (adds RFB transformers) | **Our own RFB plugin** (`StereoscopicRfbPlugin`) registered via `META-INF/rfb-plugin/stereoscopic.properties` |
| `Angelica/glsm/GLStateManager.java` (inline edits, mostly + `StereoHook` calls) | **External Mixin into `GLStateManager`** — `@Inject` at the equivalent call sites |

Scaffolding required for external delivery (the FMLCorePlugin class itself, the RFB plugin shim, the mixin config JSON, the META-INF/rfb-plugin properties file) is **necessary wiring**, not a "port" — it exists because we can't edit Angelica's existing scaffolding. Each plan distinguishes **port tasks** from **scaffold tasks** explicitly.

**Angelica is a hard runtime dependency**, not optional. Any sbs2 reference to Angelica classes (e.g., `AngelicaClassDump`, `GLStateManager`, `HUDCaching`) resolves naturally — port those references verbatim. Do **not** drop `dumpClass`/`dumpRFBClass` calls "because we don't have AngelicaClassDump"; we do, via the dependency.

**Reference port source:** `C:\CODE\Angelica-sbs2` branch `stereo-sbs-2`. Always read via `git diff` (or `git show <sha>` for a specific commit) to see ONLY what sbs2 added/changed — NEVER read sbs2's full file contents and copy whole, because most of what's in those files is pre-existing Angelica code we shouldn't touch.

**Conventions used by this plan:**
- "Run: `<cmd>`" — execute exactly. `Expected: <text>` describes successful output.
- "Source:" and "Destination:" paths are absolute (Windows + Unix-style both acceptable to the file tools).
- "Verbatim body" = copy line-for-line except for the substitutions called out in the task. NEVER drop sbs2 code on judgment ("we don't need this") — surface it for discussion if it looks irrelevant.

---

## File Structure

**Created in this plan:**

| Path | Responsibility |
|---|---|
| `C:\CODE\Stereoscopic-Angelica\settings.gradle.kts` | GTNH plugin management + project name |
| `C:\CODE\Stereoscopic-Angelica\build.gradle.kts` | Trivial — `gtnhsettingsconvention` does the heavy lifting |
| `C:\CODE\Stereoscopic-Angelica\gradle.properties` | Mod identity, MC/Forge versions, Mixin config |
| `C:\CODE\Stereoscopic-Angelica\dependencies.gradle` | Angelica, GTNHLib, JUnit |
| `C:\CODE\Stereoscopic-Angelica\repositories.gradle` | Inherits well-known repos via setting |
| `C:\CODE\Stereoscopic-Angelica\LICENSE` | LGPL-3.0-only |
| `C:\CODE\Stereoscopic-Angelica\README.md` | Top-line intro |
| `C:\CODE\Stereoscopic-Angelica\.gitignore` | Gradle/IDE artifacts |
| `C:\CODE\Stereoscopic-Angelica\src\main\java\com\mitchellmarx\stereoscopic\Stereoscopic.java` | `@Mod` entrypoint — registers config |
| `C:\CODE\Stereoscopic-Angelica\src\main\java\com\mitchellmarx\stereoscopic\config\StereoConfig.java` | Forge `Configuration` — 2 persisted fields (mode, ipd) + 2 in-memory-only fields (hud, debug). Per spec §"Config & state": HudMode/DebugEye are internal runtime state, not config knobs. |
| `C:\CODE\Stereoscopic-Angelica\src\main\java\com\mitchellmarx\stereoscopic\core\StereoMode.java` | Enum: OFF, SBS_HALF, SBS_FULL, OU_HALF, OU_FULL |
| `C:\CODE\Stereoscopic-Angelica\src\main\java\com\mitchellmarx\stereoscopic\core\StereoHudMode.java` | Enum: DUPLICATE, STRETCH, HIDE |
| `C:\CODE\Stereoscopic-Angelica\src\main\java\com\mitchellmarx\stereoscopic\core\StereoDebugEye.java` | Enum: OFF, LEFT, RIGHT |
| `C:\CODE\Stereoscopic-Angelica\src\main\java\com\mitchellmarx\stereoscopic\core\StereoState.java` | Singleton — frame snapshot + per-eye state machine. Exposes `enterGuiPass`/`enterWorldPass` flags consumed in Plan 2 by the GLSM remap mixin. |
| `C:\CODE\Stereoscopic-Angelica\src\main\java\com\mitchellmarx\stereoscopic\compat\chromatictooltips\ChromaticTooltipsCompat.java` | Reflective shim into the optional `chromatictooltips` mod. Verbatim port from sbs2 (50 lines). Class was added in sbs2 alongside the stereo work; not in published Angelica. |
| `C:\CODE\Stereoscopic-Angelica\src\main\java\com\mitchellmarx\stereoscopic\compat\xaero\XaeroCompat.java` | Reflective shim into Xaero's Minimap mod, mirroring `ChromaticTooltipsCompat`'s pattern. Exposes `beforeIngameGuiRender(float)` that resolves a `MethodHandle` to `XaeroMinimapCore.beforeIngameGuiRender` at mod init and calls through if Xaero is loaded; no-op otherwise. Lets `MixinHUDCaching_Stereo` gate Xaero's pre-render hook to once-per-frame without dragging Xaero onto our compileOnly classpath. |
| `C:\CODE\Stereoscopic-Angelica\src\main\java\com\mitchellmarx\stereoscopic\cursor\CursorBackend.java` | Pluggable OS-cursor backend interface (matches spec §"Source tree"). Methods: `captureArrowBitmap`, `trapCursor(boolean)`, `release`, `isSupported`, plus `Sprite` inner class for BGRA bitmap + hotspot. |
| `C:\CODE\Stereoscopic-Angelica\src\main\java\com\mitchellmarx\stereoscopic\cursor\NoOpCursorBackend.java` | Fallback impl — `isSupported() == false`, all other methods no-op. Used on Mac/Linux and as a safety fallback if `WindowsCursorBackend` can't acquire the HWND. |
| `C:\CODE\Stereoscopic-Angelica\src\main\java\com\mitchellmarx\stereoscopic\cursor\WindowsCursorBackend.java` | Windows impl. Native arrow capture, ClipCursor trap, SDL3-aware cursor visibility. Ported from sbs2's monolithic `CursorPresentThread` Win32 sections, isolated behind the `CursorBackend` interface. Uses `wglGetCurrentDC` + `WindowFromDC` (NOT `glfwGetWin32Window` — see spec goal L: lwjgl3ify-3.0.17 has no GLFW). |
| `C:\CODE\Stereoscopic-Angelica\src\main\java\com\mitchellmarx\stereoscopic\cursor\CursorPresentThread.java` | Async WGL present thread. Refactored sbs2 port (~600 lines after isolating `WindowsCursorBackend`). Uses the backend interface for OS-specific operations; owns the thread lifecycle, the LWJGL 3 JNI `SDL_HideCursor` / `SDL_ShowCursor` calls, and the polling loop. |
| `C:\CODE\Stereoscopic-Angelica\src\main\java\com\mitchellmarx\stereoscopic\cursor\StereoCursor.java` | Main-thread facade. Direct port of sbs2 `StereoCursor` (125 lines), with `AngelicaConfig` → `StereoConfig` substitution. Drives `update()`, `setVirtualPos()`, `virtualX/Y`, the four `getX/Y/EventX/EventY` overrides, and the trap-/hide-on-gui-open lifecycle. |
| `C:\CODE\Stereoscopic-Angelica\src\main\resources\mcmod.info` | Forge mod metadata |
| `C:\CODE\Stereoscopic-Angelica\src\main\resources\mixins.stereoscopic.json` | Mixin config — Plan 1 entries (camera/framebuffer/world-loop + sodium toggle + 2 stereo-extension mixins added in Tasks 22g/22h) |
| `C:\CODE\Stereoscopic-Angelica\src\main\resources\pack.mcmeta` | Resource pack metadata |
| `C:\CODE\Stereoscopic-Angelica\src\mixin\java\com\mitchellmarx\stereoscopic\mixin\minecraft\MixinEntityRenderer_Stereo.java` | Two-pass world render loop. Verbatim port from sbs2 (347 lines) — the integration point consuming the cursor/HUDCaching/Iris work below. |
| `C:\CODE\Stereoscopic-Angelica\src\mixin\java\com\mitchellmarx\stereoscopic\mixin\minecraft\MixinEntityRenderer_StereoCamera.java` | Per-eye projection/modelview offset |
| `C:\CODE\Stereoscopic-Angelica\src\mixin\java\com\mitchellmarx\stereoscopic\mixin\minecraft\MixinFramebuffer_Stereo.java` | Restore eye viewport after Iris framebuffer rebind |
| `C:\CODE\Stereoscopic-Angelica\src\mixin\java\com\mitchellmarx\stereoscopic\mixin\angelica\MixinHUDCaching_Stereo.java` | `@Mixin(HUDCaching.class)` with two `@Redirect`s that make base Angelica's `renderCachedHud` stereo-aware *without* copying its body into our compilation unit. (1) Wraps the `XaeroMinimapCore.beforeIngameGuiRender` call site with a `@Unique` once-per-frame gate that calls through `XaeroCompat`. (2) Wraps the second `ingame.renderGameOverlay(...)` call (the one inside the dirty/cache-fill branch — ordinal 1) to force `glViewport` to full-screen for that call only, restoring the caller's viewport on return. Net effect: the stereo path can invoke `HUDCaching.renderCachedHud(...)` twice with eye-specific viewports and get one cache-fill (at full screen) + two blits (one per eye region), while Xaero's pre-render hook fires only once per frame. |
| `C:\CODE\Stereoscopic-Angelica\src\mixin\java\com\mitchellmarx\stereoscopic\mixin\iris\IStereoPipeline.java` | Duck interface with a `default void setActiveEye(int)` no-op. The mixin below attaches it to Iris's `WorldRenderingPipeline` at runtime so call sites can cast and call against a compiler-visible type. |
| `C:\CODE\Stereoscopic-Angelica\src\mixin\java\com\mitchellmarx\stereoscopic\mixin\iris\MixinWorldRenderingPipeline_SetActiveEye.java` | `@Mixin(WorldRenderingPipeline.class) interface … extends IStereoPipeline` — attaches the duck interface to Iris's `WorldRenderingPipeline` at runtime. Plan 1 ships only the no-op default (inherited from `IStereoPipeline`); Plan 2 mixins into concrete implementers (`DeferredWorldRenderingPipeline`, etc.) to override `setActiveEye` with the real per-eye RenderTargets-swap logic. |
| `C:\CODE\Stereoscopic-Angelica\src\mixin\java\com\mitchellmarx\stereoscopic\mixin\sodium\MixinSodiumGameOptionPages_StereoToggle.java` | Tick-box + IPD slider, pipeline rebuild on toggle |
| `C:\CODE\Stereoscopic-Angelica\src\test\java\com\mitchellmarx\stereoscopic\core\StereoStateTest.java` | Sign convention, isActive, beginFrame snapshot |
| `C:\CODE\Stereoscopic-Angelica\src\test\java\com\mitchellmarx\stereoscopic\core\StereoModeTest.java` | `valueOf()` round-trip |

**Modified in this plan:**

| Path | Why |
|---|---|
| `…/src/main/java/com/mitchellmarx/stereoscopic/Stereoscopic.java` | Task 22a adds an `FMLInitializationEvent` handler that calls `ChromaticTooltipsCompat.init()`. Task 22g extends the same handler to also call `XaeroCompat.init()`. |

---

## Phase 1 — Build skeleton

### Task 1: gradle.properties

**Files:**
- Create: `C:\CODE\Stereoscopic-Angelica\gradle.properties`

- [ ] **Step 1: Write the file verbatim**

```properties
# GTNH settings convention version
gtnh.settings.blowdryerTag = 0.2.2

# Mod identity
modName = StereoCraft: Stereoscopic 3D
modId = stereoscopic
modGroup = com.mitchellmarx.stereoscopic
useModGroupForPublishing = false
autoUpdateBuildScript = false

# Target environment
minecraftVersion = 1.7.10
forgeVersion = 10.13.4.1614
channel = stable
mappingsVersion = 12
remoteMappings = https\://raw.githubusercontent.com/MinecraftForge/FML/1.7.10/conf/

# Modern Java syntax via jvmDowngrader (matches Angelica's setup)
enableModernJavaSyntax = jvmDowngrader
jvmDowngraderMultiReleaseVersions = 17, 21, 25
jvmDowngraderStubsProvider = gtnhlib

# Tags class for version constant
generateGradleTokenClass = com.mitchellmarx.stereoscopic.Tags
gradleTokenVersion = VERSION

# API package (empty — mod has no public API surface for other mods)
apiPackage =

# No access transformers in Plan 1
accessTransformersFile =

# Mixins
usesMixins = true
separateMixinSourceSet = mixin
usesMixinDebug = false
mixinPlugin =
mixinsPackage = mixin
coreModClass =
containsMixinsAndOrCoreModOnly = false
forceEnableMixins = false

# Shadowed deps — none in Plan 1, but flag is harmless
usesShadowedDependencies = false

includeWellKnownRepositories = true

# Modrinth / CurseForge — not publishing in v0.1.0
usesMavenPublishing = false
modrinthRelations = required-project\:angelica
curseForgeRelations = requiredDependency\:angelica;requiredDependency\:gtnhlib

# Disable formatter checks for v0.1.0 (matches Angelica)
disableSpotless = true

# Standard JVM args
org.gradle.jvmargs = -Xmx2g
org.gradle.parallel = true
```

- [ ] **Step 2: Commit**

```bash
git -C /c/CODE/Stereoscopic-Angelica add gradle.properties
git -C /c/CODE/Stereoscopic-Angelica commit -m "chore(scaffold): gradle.properties (mod identity + RFG config)"
```

### Task 2: settings.gradle.kts

**Files:**
- Create: `C:\CODE\Stereoscopic-Angelica\settings.gradle.kts`

- [ ] **Step 1: Write the file**

```kotlin
pluginManagement {
    repositories {
        maven {
            name = "GTNH Maven"
            url = uri("https://nexus.gtnewhorizons.com/repository/public/")
            mavenContent {
                includeGroup("com.gtnewhorizons")
                includeGroupByRegex("com\\.gtnewhorizons\\..+")
            }
        }
        gradlePluginPortal()
        mavenCentral()
        mavenLocal()
    }
}

plugins {
    id("com.gtnewhorizons.gtnhsettingsconvention") version ("2.0.24")
}
```

- [ ] **Step 2: Commit**

```bash
git -C /c/CODE/Stereoscopic-Angelica add settings.gradle.kts
git -C /c/CODE/Stereoscopic-Angelica commit -m "chore(scaffold): settings.gradle.kts via gtnhsettingsconvention"
```

### Task 3: build.gradle.kts (minimal — deploy hook added in Task 7)

**Files:**
- Create: `C:\CODE\Stereoscopic-Angelica\build.gradle.kts`

- [ ] **Step 1: Write the file**

```kotlin
plugins {
    id("com.gtnewhorizons.gtnhconvention")
}

// All the real configuration lives in gradle.properties + dependencies.gradle.
// gtnhconvention (the build plugin) applies RetroFuturaGradle and wires everything
// from there.  Pairs with gtnhsettingsconvention in settings.gradle.kts (the settings
// plugin); the settings plugin provides plugin-management repos and detects Java,
// while the build plugin provides buildscript repositories that Java-25-injected
// dependencies (spotless, etc.) need to resolve.
```

A deploy task is added in Task 7.

**Why the `gtnhconvention` plugin is required:** on Java 25+, `gtnhsettingsconvention` injects spotless into the buildscript classpath but provides no buildscript repos. Without `gtnhconvention`, `./gradlew help` fails with `Cannot resolve external dependency com.diffplug.spotless:spotless-plugin-gradle:8.3.0 because no repositories are defined`.

- [ ] **Step 2: Commit**

```bash
git -C /c/CODE/Stereoscopic-Angelica add build.gradle.kts
git -C /c/CODE/Stereoscopic-Angelica commit -m "chore(scaffold): build.gradle.kts placeholder (RFG configures from gradle.properties)"
```

### Task 4: repositories.gradle + dependencies.gradle

**Files:**
- Create: `C:\CODE\Stereoscopic-Angelica\repositories.gradle`
- Create: `C:\CODE\Stereoscopic-Angelica\dependencies.gradle`

- [ ] **Step 1: Write repositories.gradle**

```groovy
// No extra repositories beyond what gtnhsettingsconvention's includeWellKnownRepositories
// provides. The GTNH Maven (which serves Angelica + GTNHLib) is included by default.
repositories {
    mavenLocal()
}
```

- [ ] **Step 2: Write dependencies.gradle**

```groovy
dependencies {
    // Angelica — pinned to 2.1.x patch stream. Spec section "Dependency stance".
    implementation("com.github.GTNewHorizons:Angelica:[2.1,2.2):dev") { transitive = false }

    // GTNHLib — required; provides jvmDowngrader stubs at runtime.
    api("com.github.GTNewHorizons:GTNHLib:0.9.52:dev")

    // Dev-time conveniences (matches Angelica's pattern)
    compileOnly("org.projectlombok:lombok:1.18.42") { transitive = false }
    annotationProcessor("org.projectlombok:lombok:1.18.42")
    compileOnly("org.jetbrains:annotations:26.0.2")

    // Tests
    testImplementation("org.junit.jupiter:junit-jupiter:6.0.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
```

- [ ] **Step 3: Commit**

```bash
git -C /c/CODE/Stereoscopic-Angelica add repositories.gradle dependencies.gradle
git -C /c/CODE/Stereoscopic-Angelica commit -m "chore(scaffold): dependencies (Angelica [2.1,2.2), GTNHLib, lombok, JUnit)"
```

### Task 5: LICENSE + README + .gitignore

**Files:**
- Create: `C:\CODE\Stereoscopic-Angelica\LICENSE` (LGPL-3.0-only)
- Create: `C:\CODE\Stereoscopic-Angelica\README.md`
- Create: `C:\CODE\Stereoscopic-Angelica\.gitignore`

- [ ] **Step 1: Copy LGPL-3.0-only license**

Read the full LGPL-3.0 license text from `C:\CODE\Angelica-sbs2\LICENSE` (Angelica uses the same license) and write to the destination unchanged.

```bash
cp /c/CODE/Angelica-sbs2/LICENSE /c/CODE/Stereoscopic-Angelica/LICENSE
```

- [ ] **Step 2: Write README.md**

```markdown
# StereoCraft: Stereoscopic 3D

A side-by-side stereoscopic 3D renderer for Minecraft 1.7.10. Depends on Angelica.

## Requirements

- Minecraft 1.7.10 + Forge 10.13.4.1614 or later
- [Angelica](https://github.com/GTNewHorizons/Angelica) `[2.1,2.2)`
- [GTNHLib](https://github.com/GTNewHorizons/GTNHLib) `0.9.52` or later

## Usage

Toggle SBS rendering in Sodium video options. Adjust eye separation (IPD) with the slider in the same group.

## License

LGPL-3.0-only.
```

- [ ] **Step 3: Write .gitignore**

```
# Gradle
.gradle/
build/
out/

# IDE
.idea/
*.iml
*.ipr
*.iws
.vscode/

# OS
.DS_Store
Thumbs.db

# Mod runtime working dirs
run/
logs/

# Test extracts
.*_extract/
.scratch/

# Local-only settings
.claude/settings.local.json
```

- [ ] **Step 4: Commit**

```bash
git -C /c/CODE/Stereoscopic-Angelica add LICENSE README.md .gitignore
git -C /c/CODE/Stereoscopic-Angelica commit -m "chore(scaffold): LICENSE (LGPL-3.0), README, .gitignore"
```

### Task 6: First green build (no source, no mixins yet)

**Files:** none (verification only)

- [ ] **Step 1: Generate Gradle wrapper using GTNH-managed wrapper**

```bash
cd /c/CODE/Stereoscopic-Angelica
gradle wrapper --gradle-version 8.7
```

Expected: `gradlew`, `gradlew.bat`, and `gradle/wrapper/` directory created. If `gradle` isn't on PATH, use a sibling project's wrapper: `cp -r /c/CODE/Angelica-sbs2/gradle . && cp /c/CODE/Angelica-sbs2/gradlew /c/CODE/Angelica-sbs2/gradlew.bat .`.

- [ ] **Step 2: Sanity-build to resolve plugin + deps**

```bash
cd /c/CODE/Stereoscopic-Angelica
./gradlew --no-daemon help
```

Expected: BUILD SUCCESSFUL. Resolves `gtnhsettingsconvention` 2.0.24 from GTNH Maven and applies RFG. Errors here are configuration errors, not source errors — fix gradle.properties / settings.gradle.kts before continuing.

- [ ] **Step 3: Commit wrapper**

```bash
git -C /c/CODE/Stereoscopic-Angelica add gradlew gradlew.bat gradle/
git -C /c/CODE/Stereoscopic-Angelica commit -m "chore(scaffold): gradle wrapper"
```

### Task 7: Deploy hook to Prism profile

**Files:**
- Modify: `C:\CODE\Stereoscopic-Angelica\build.gradle.kts`

- [ ] **Step 1: Replace the placeholder build.gradle.kts**

```kotlin
import org.gradle.api.tasks.Copy

plugins {
    id("com.gtnewhorizons.gtnhconvention")
}

// gtnhconvention applies JUnit 4 by default; our test suite is JUnit 5 (jupiter).
// Without useJUnitPlatform() the test task uses JUnit Vintage and finds zero tests.
tasks.test { useJUnitPlatform() }

// Path to a Prism Launcher GTNH profile's mods dir. Overridable via
// -Pstereoscopic.deployDir=<other-path> when testing on a different instance.
val testInstanceMods = file(
    (project.findProperty("stereoscopic.deployDir") as String?)
        ?: "C:/Users/felix/AppData/Roaming/PrismLauncher/instances/GTNH-daily-2026-05-17+520-mmcprism-java17-25/.minecraft/mods"
)

val copyToTestInstance by tasks.registering(Copy::class) {
    group = "stereoscopic"
    description = "Deploys the built jar into the Prism Launcher GTNH profile."
    dependsOn(tasks.named("jar"))
    onlyIf {
        val ok = testInstanceMods.isDirectory
        if (!ok) logger.lifecycle("Skipping copyToTestInstance: $testInstanceMods not found")
        ok
    }
    from(tasks.named("jar").map { it.outputs.files })
    into(testInstanceMods)
    doFirst {
        testInstanceMods.listFiles { _, name ->
            name.startsWith("stereoscopic-") && name.endsWith(".jar")
        }?.forEach { f ->
            if (!f.delete()) logger.warn("Could not delete stale jar $f (launcher running?)")
        }
    }
}

tasks.named("build") { dependsOn(copyToTestInstance) }
```

- [ ] **Step 2: Verify the task exists**

```bash
cd /c/CODE/Stereoscopic-Angelica
./gradlew tasks --group stereoscopic
```

Expected: `copyToTestInstance — Deploys the built jar into the Prism Launcher GTNH profile.`

- [ ] **Step 3: Commit**

```bash
git -C /c/CODE/Stereoscopic-Angelica add build.gradle.kts
git -C /c/CODE/Stereoscopic-Angelica commit -m "chore(scaffold): build.gradle.kts — copyToTestInstance deploy hook"
```

---

## Phase 2 — Mod entry + resources

### Task 8: mcmod.info + pack.mcmeta

**Files:**
- Create: `C:\CODE\Stereoscopic-Angelica\src\main\resources\mcmod.info`
- Create: `C:\CODE\Stereoscopic-Angelica\src\main\resources\pack.mcmeta`

- [ ] **Step 1: Write mcmod.info**

```json
[
  {
    "modid": "stereoscopic",
    "name": "StereoCraft: Stereoscopic 3D",
    "description": "Side-by-side stereoscopic 3D for Minecraft 1.7.10. Requires Angelica.",
    "version": "${modVersion}",
    "mcversion": "${minecraftVersion}",
    "url": "",
    "authorList": ["Mitchell Samora"],
    "credits": "Ported from the stereo SBS feature on Angelica's stereo-sbs-2 branch.",
    "dependencies": ["angelica", "gtnhlib"]
  }
]
```

> **Note on template tokens:** GTNH's `gtnhconvention` populates `${modVersion}` and `${minecraftVersion}` (camelCase, matching `gradle.properties` keys), *not* Forge's legacy `${version}` / `${mcversion}`. Using the legacy tokens leaves them unsubstituted in the published jar.

- [ ] **Step 2: Write pack.mcmeta**

```json
{
  "pack": {
    "description": "StereoCraft: Stereoscopic 3D",
    "pack_format": 1
  }
}
```

- [ ] **Step 3: Commit**

```bash
git -C /c/CODE/Stereoscopic-Angelica add src/main/resources/mcmod.info src/main/resources/pack.mcmeta
git -C /c/CODE/Stereoscopic-Angelica commit -m "feat(meta): mcmod.info + pack.mcmeta"
```

### Task 9: @Mod entrypoint

**Files:**
- Create: `C:\CODE\Stereoscopic-Angelica\src\main\java\com\mitchellmarx\stereoscopic\Stereoscopic.java`

- [ ] **Step 1: Write the file**

```java
package com.mitchellmarx.stereoscopic;

import com.mitchellmarx.stereoscopic.config.StereoConfig;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

@Mod(
    modid = Stereoscopic.MODID,
    name = "StereoCraft: Stereoscopic 3D",
    version = Tags.VERSION,
    dependencies = "required-after:angelica;required-after:gtnhlib",
    acceptableRemoteVersions = "*",
    acceptedMinecraftVersions = "[1.7.10]"
)
@SideOnly(Side.CLIENT)
public class Stereoscopic {
    public static final String MODID = "stereoscopic";

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        StereoConfig.load(event.getSuggestedConfigurationFile());
    }
}
```

- [ ] **Step 2: Commit**

```bash
git -C /c/CODE/Stereoscopic-Angelica add src/main/java/com/mitchellmarx/stereoscopic/Stereoscopic.java
git -C /c/CODE/Stereoscopic-Angelica commit -m "feat(mod): @Mod entrypoint (client-side, depends on angelica + gtnhlib)"
```

### Task 10: mixins.stereoscopic.json (Plan 1 entries only)

**Files:**
- Create: `C:\CODE\Stereoscopic-Angelica\src\main\resources\mixins.stereoscopic.json`

- [ ] **Step 1: Write the file**

```json
{
  "required": true,
  "minVersion": "0.8",
  "package": "com.mitchellmarx.stereoscopic.mixin",
  "compatibilityLevel": "JAVA_8",
  "refmap": "mixins.stereoscopic.refmap.json",
  "mixins": [],
  "client": [
    "minecraft.MixinEntityRenderer_Stereo",
    "minecraft.MixinEntityRenderer_StereoCamera",
    "minecraft.MixinFramebuffer_Stereo"
  ]
}
```

The Phase-4 mixins (`angelica.MixinHUDCaching_Stereo`, `iris.MixinWorldRenderingPipeline_SetActiveEye`) get appended in Task 22i. The Sodium toggle mixin (`sodium.MixinSodiumGameOptionPages_StereoToggle`) gets appended in Task 23, when the class itself ships — pre-registering it here would crash MixinBootstrap at mod-load (`"required": true` + missing class). Plans 2/3/4 will append further entries as they introduce new mixin classes.

- [ ] **Step 2: Commit**

```bash
git -C /c/CODE/Stereoscopic-Angelica add src/main/resources/mixins.stereoscopic.json
git -C /c/CODE/Stereoscopic-Angelica commit -m "feat(mixin): mixins.stereoscopic.json (Plan 1 entries)"
```

---

## Phase 3 — Config + core state (tests first)

### Task 11: StereoMode enum

**Files:**
- Create: `C:\CODE\Stereoscopic-Angelica\src\main\java\com\mitchellmarx\stereoscopic\core\StereoMode.java`

Reference source: `C:\CODE\Angelica-sbs2\src\main\java\com\gtnewhorizons\angelica\stereo\StereoMode.java` (30 lines).

- [ ] **Step 1: Write the file**

```java
package com.mitchellmarx.stereoscopic.core;

/**
 * Stereoscopic output layout. SBS splits left/right; OU splits top/bottom. HALF variants render
 * each eye at half the split-axis dimension (native aspect) — what SBS-3D viewers expect. FULL
 * variants render at the full split-axis dimension (squished), for legacy frame-packed displays.
 */
public enum StereoMode {
    OFF,
    SBS_HALF,
    SBS_FULL,
    OU_HALF,
    OU_FULL;

    public boolean isActive() {
        return this != OFF;
    }

    public boolean isSideBySide() {
        return this == SBS_HALF || this == SBS_FULL;
    }

    public boolean isOverUnder() {
        return this == OU_HALF || this == OU_FULL;
    }

    public boolean isHalf() {
        return this == SBS_HALF || this == OU_HALF;
    }
}
```

- [ ] **Step 2: Commit**

```bash
git -C /c/CODE/Stereoscopic-Angelica add src/main/java/com/mitchellmarx/stereoscopic/core/StereoMode.java
git -C /c/CODE/Stereoscopic-Angelica commit -m "feat(core): StereoMode enum"
```

### Task 12: StereoHudMode + StereoDebugEye enums

**Files:**
- Create: `C:\CODE\Stereoscopic-Angelica\src\main\java\com\mitchellmarx\stereoscopic\core\StereoHudMode.java`
- Create: `C:\CODE\Stereoscopic-Angelica\src\main\java\com\mitchellmarx\stereoscopic\core\StereoDebugEye.java`

- [ ] **Step 1: Write StereoHudMode.java**

```java
package com.mitchellmarx.stereoscopic.core;

/** How to render the 2D HUD when stereoscopic mode is active. */
public enum StereoHudMode {
    /** Draw the HUD twice, once into each eye's viewport. Recommended for SBS-3D viewers. */
    DUPLICATE,
    /** Draw the HUD once, full-screen. Looks stretched in SBS modes. Mainly useful for debugging. */
    STRETCH,
    /** Don't draw the HUD at all. */
    HIDE
}
```

- [ ] **Step 2: Write StereoDebugEye.java**

```java
package com.mitchellmarx.stereoscopic.core;

/** Debug-only override forcing one-eye rendering without the two-pass loop. */
public enum StereoDebugEye {
    OFF,
    LEFT,
    RIGHT
}
```

- [ ] **Step 3: Commit**

```bash
git -C /c/CODE/Stereoscopic-Angelica add src/main/java/com/mitchellmarx/stereoscopic/core/
git -C /c/CODE/Stereoscopic-Angelica commit -m "feat(core): StereoHudMode + StereoDebugEye enums"
```

### Task 13: StereoConfig — failing test first

**Files:**
- Create: `C:\CODE\Stereoscopic-Angelica\src\test\java\com\mitchellmarx\stereoscopic\config\StereoConfigTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.mitchellmarx.stereoscopic.config;

import com.mitchellmarx.stereoscopic.core.StereoDebugEye;
import com.mitchellmarx.stereoscopic.core.StereoHudMode;
import com.mitchellmarx.stereoscopic.core.StereoMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StereoConfigTest {

    @Test
    void defaults_match_spec() {
        // Resetting to defaults is a pure assignment to public statics — no Forge Configuration required.
        StereoConfig.stereoscopicMode = StereoMode.OFF;
        StereoConfig.stereoIpd = 0.064f;
        StereoConfig.stereoHudMode = StereoHudMode.DUPLICATE;
        StereoConfig.stereoDebugForceEye = StereoDebugEye.OFF;

        assertEquals(StereoMode.OFF,             StereoConfig.stereoscopicMode);
        assertEquals(0.064f,                     StereoConfig.stereoIpd, 1e-6f);
        assertEquals(StereoHudMode.DUPLICATE,    StereoConfig.stereoHudMode);
        assertEquals(StereoDebugEye.OFF,         StereoConfig.stereoDebugForceEye);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd /c/CODE/Stereoscopic-Angelica
./gradlew test
```

Expected: COMPILE FAILURE — `StereoConfig` does not exist yet.

### Task 14: StereoConfig — implementation

**Files:**
- Create: `C:\CODE\Stereoscopic-Angelica\src\main\java\com\mitchellmarx\stereoscopic\config\StereoConfig.java`

- [ ] **Step 1: Write the implementation**

```java
package com.mitchellmarx.stereoscopic.config;

import com.mitchellmarx.stereoscopic.core.StereoDebugEye;
import com.mitchellmarx.stereoscopic.core.StereoHudMode;
import com.mitchellmarx.stereoscopic.core.StereoMode;
import net.minecraftforge.common.config.Configuration;

import java.io.File;

/**
 * Replaces the AngelicaConfig fields that sbs2 reads from for stereo state.
 *
 * <p>Persisted to {@code config/stereoscopic.cfg} via Forge's {@link Configuration}:
 * <ul>
 *   <li>{@link #stereoscopicMode} — Sodium UI tick-box (OFF ↔ SBS_HALF)
 *   <li>{@link #stereoIpd} — Sodium UI integer slider in mm, persisted as meters
 * </ul>
 *
 * <p>Internal runtime state (not persisted, no UI — per spec §"Config & state"):
 * <ul>
 *   <li>{@link #stereoHudMode} — default DUPLICATE; flipped at runtime by debug code
 *   <li>{@link #stereoDebugForceEye} — default OFF; flipped at runtime by debug code
 * </ul>
 */
public final class StereoConfig {

    public static volatile StereoMode      stereoscopicMode    = StereoMode.OFF;
    public static volatile float           stereoIpd           = 0.064f;
    public static volatile StereoHudMode   stereoHudMode       = StereoHudMode.DUPLICATE;
    public static volatile StereoDebugEye  stereoDebugForceEye = StereoDebugEye.OFF;

    private static Configuration config;

    private StereoConfig() {}

    public static void load(File file) {
        config = new Configuration(file);
        try {
            config.load();
            read();
        } finally {
            if (config.hasChanged()) config.save();
        }
    }

    private static void read() {
        final String modeName = config.getString(
            "stereoscopicMode", "general", StereoMode.OFF.name(),
            "Stereo layout. Values: OFF, SBS_HALF, SBS_FULL, OU_HALF, OU_FULL. Default OFF.");
        stereoscopicMode = parseEnum(StereoMode.class, modeName, StereoMode.OFF);

        stereoIpd = (float) config.get("general", "stereoIpd", 0.064,
            "Eye separation (interpupillary distance) in meters. Default 0.064.").getDouble();
    }

    public static void save() {
        if (config == null) return;
        config.get("general", "stereoscopicMode", StereoMode.OFF.name()).set(stereoscopicMode.name());
        config.get("general", "stereoIpd", 0.064).set((double) stereoIpd);
        if (config.hasChanged()) config.save();
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> cls, String name, E fallback) {
        try { return Enum.valueOf(cls, name); }
        catch (Exception e) { return fallback; }
    }
}
```

- [ ] **Step 2: Run the test to verify it passes**

```bash
cd /c/CODE/Stereoscopic-Angelica
./gradlew test --tests com.mitchellmarx.stereoscopic.config.StereoConfigTest
```

Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git -C /c/CODE/Stereoscopic-Angelica add src/main/java/com/mitchellmarx/stereoscopic/config/ src/test/java/com/mitchellmarx/stereoscopic/config/
git -C /c/CODE/Stereoscopic-Angelica commit -m "feat(config): StereoConfig (Forge Configuration) + defaults test"
```

### Task 15: StereoMode round-trip test

**Files:**
- Create: `C:\CODE\Stereoscopic-Angelica\src\test\java\com\mitchellmarx\stereoscopic\core\StereoModeTest.java`

- [ ] **Step 1: Write the test**

```java
package com.mitchellmarx.stereoscopic.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StereoModeTest {

    @Test
    void valueOf_round_trips_for_every_constant() {
        for (StereoMode m : StereoMode.values()) {
            assertEquals(m, StereoMode.valueOf(m.name()));
        }
    }

    @Test
    void valueOf_rejects_unknown_name() {
        assertThrows(IllegalArgumentException.class, () -> StereoMode.valueOf("NOPE"));
    }

    @Test
    void isActive_only_for_non_off() {
        for (StereoMode m : StereoMode.values()) {
            assertEquals(m != StereoMode.OFF, m.isActive());
        }
    }

    @Test
    void isHalf_matches_HALF_variants() {
        assertEquals(true, StereoMode.SBS_HALF.isHalf());
        assertEquals(true, StereoMode.OU_HALF.isHalf());
        assertEquals(false, StereoMode.SBS_FULL.isHalf());
        assertEquals(false, StereoMode.OU_FULL.isHalf());
        assertEquals(false, StereoMode.OFF.isHalf());
    }
}
```

- [ ] **Step 2: Run the test**

```bash
cd /c/CODE/Stereoscopic-Angelica
./gradlew test --tests com.mitchellmarx.stereoscopic.core.StereoModeTest
```

Expected: ALL PASS.

- [ ] **Step 3: Commit**

```bash
git -C /c/CODE/Stereoscopic-Angelica add src/test/java/com/mitchellmarx/stereoscopic/core/StereoModeTest.java
git -C /c/CODE/Stereoscopic-Angelica commit -m "test(core): StereoMode round-trip + helper-method coverage"
```

### Task 16: CursorPresentThread + StereoCursor stubs (replaced in Plan 3)

**Files:**
- Create: `C:\CODE\Stereoscopic-Angelica\src\main\java\com\mitchellmarx\stereoscopic\cursor\CursorPresentThread.java`
- Create: `C:\CODE\Stereoscopic-Angelica\src\main\java\com\mitchellmarx\stereoscopic\cursor\StereoCursor.java`

`StereoState` (next task) calls `CursorPresentThread.ensureStarted()` / `.stop()` from `beginFrame()` / `endFrame()`. `StereoCursor` is read by Plan 2's GLSM remap mixin (the `stereoMouseGetX/Y` helpers) and by Plan 3's cursor work. Provide no-op stubs in Plan 1 so the core code compiles; Plan 3 replaces both files with the real Windows implementation.

- [ ] **Step 1: Write CursorPresentThread stub**

```java
package com.mitchellmarx.stereoscopic.cursor;

/**
 * Plan 1 stub. Plan 3 replaces this with the real async WGL cursor present thread.
 * Methods are no-ops; the StereoState lifecycle still calls them so Plan 3 can drop
 * its implementation in without touching StereoState.
 */
public final class CursorPresentThread {
    private CursorPresentThread() {}
    public static void ensureStarted() { /* Plan 3 */ }
    public static void stop()          { /* Plan 3 */ }
}
```

- [ ] **Step 2: Write StereoCursor stub**

```java
package com.mitchellmarx.stereoscopic.cursor;

import org.lwjgl.input.Mouse;

/**
 * Plan 1 stub. Plan 3 replaces this with the real virtual-cursor accessor.
 * Returns vanilla LWJGL mouse coords so anything that reads through this layer
 * during Plan 1 behaves as if stereo were disabled.
 */
public final class StereoCursor {
    private StereoCursor() {}
    public static int getX()      { return Mouse.getX();      }
    public static int getY()      { return Mouse.getY();      }
    public static int getEventX() { return Mouse.getEventX(); }
    public static int getEventY() { return Mouse.getEventY(); }
}
```

- [ ] **Step 3: Commit**

```bash
git -C /c/CODE/Stereoscopic-Angelica add src/main/java/com/mitchellmarx/stereoscopic/cursor/
git -C /c/CODE/Stereoscopic-Angelica commit -m "feat(cursor): Plan 1 stubs for CursorPresentThread + StereoCursor"
```

### Task 17: StereoState — failing tests first

**Files:**
- Create: `C:\CODE\Stereoscopic-Angelica\src\test\java\com\mitchellmarx\stereoscopic\core\StereoStateTest.java`

- [ ] **Step 1: Write the tests**

```java
package com.mitchellmarx.stereoscopic.core;

import com.mitchellmarx.stereoscopic.config.StereoConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StereoStateTest {

    @BeforeEach
    void resetConfig() {
        StereoConfig.stereoscopicMode    = StereoMode.OFF;
        StereoConfig.stereoIpd           = 0.064f;
        StereoConfig.stereoHudMode       = StereoHudMode.DUPLICATE;
        StereoConfig.stereoDebugForceEye = StereoDebugEye.OFF;
        // Reset transient state on the singleton too.
        StereoState.INSTANCE.beginFrame();
        StereoState.INSTANCE.endFrame();
    }

    @Test
    void isActive_false_when_mode_off() {
        StereoConfig.stereoscopicMode = StereoMode.OFF;
        assertFalse(StereoState.INSTANCE.beginFrame());
        assertFalse(StereoState.INSTANCE.isActive());
    }

    @Test
    void isActive_true_when_mode_sbs_half() {
        StereoConfig.stereoscopicMode = StereoMode.SBS_HALF;
        assertTrue(StereoState.INSTANCE.beginFrame());
        assertTrue(StereoState.INSTANCE.isActive());
    }

    @Test
    void getEyeOffset_sign_LEFT_positive_RIGHT_negative() {
        StereoConfig.stereoscopicMode = StereoMode.SBS_HALF;
        StereoConfig.stereoIpd = 0.064f;
        StereoState.INSTANCE.beginFrame();

        StereoState.INSTANCE.setEye(StereoState.Eye.LEFT);
        assertEquals(+0.032f, StereoState.INSTANCE.getEyeOffset(), 1e-6f,
            "LEFT eye must use +ipd/2 (sbs2 convention; do not flip)");

        StereoState.INSTANCE.setEye(StereoState.Eye.RIGHT);
        assertEquals(-0.032f, StereoState.INSTANCE.getEyeOffset(), 1e-6f,
            "RIGHT eye must use -ipd/2");
    }

    @Test
    void getEyeOffset_zero_when_inactive() {
        StereoConfig.stereoscopicMode = StereoMode.OFF;
        StereoState.INSTANCE.beginFrame();
        StereoState.INSTANCE.setEye(StereoState.Eye.LEFT);
        assertEquals(0f, StereoState.INSTANCE.getEyeOffset(), 1e-6f);
    }

    @Test
    void getEyeOffset_zero_when_eye_is_mono() {
        StereoConfig.stereoscopicMode = StereoMode.SBS_HALF;
        StereoState.INSTANCE.beginFrame();
        StereoState.INSTANCE.setEye(StereoState.Eye.MONO);
        assertEquals(0f, StereoState.INSTANCE.getEyeOffset(), 1e-6f);
    }

    @Test
    void beginFrame_snapshots_mode_and_ipd() {
        StereoConfig.stereoscopicMode = StereoMode.SBS_HALF;
        StereoConfig.stereoIpd = 0.080f;
        StereoState.INSTANCE.beginFrame();
        assertEquals(StereoMode.SBS_HALF, StereoState.INSTANCE.getFrameMode());
        assertEquals(0.080f, StereoState.INSTANCE.getFrameIpd(), 1e-6f);
    }

    @Test
    void stereoEyeCount_reads_config_not_cache() {
        // Pipeline-init can happen before first beginFrame() — stereoEyeCount() must read
        // config directly, not the cached active flag. Guards memory note about iris init order.
        StereoConfig.stereoscopicMode = StereoMode.SBS_HALF;
        // Deliberately do NOT call beginFrame() — eye count must already be 2.
        assertEquals(2, StereoState.INSTANCE.stereoEyeCount());

        StereoConfig.stereoscopicMode = StereoMode.OFF;
        assertEquals(1, StereoState.INSTANCE.stereoEyeCount());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd /c/CODE/Stereoscopic-Angelica
./gradlew test --tests com.mitchellmarx.stereoscopic.core.StereoStateTest
```

Expected: COMPILE FAILURE — `StereoState` does not exist yet.

### Task 18: StereoState — implementation (port from sbs2)

**Files:**
- Create: `C:\CODE\Stereoscopic-Angelica\src\main\java\com\mitchellmarx\stereoscopic\core\StereoState.java`

Reference: `C:\CODE\Angelica-sbs2\src\main\java\com\gtnewhorizons\angelica\stereo\StereoState.java` (178 lines). Port verbatim with these substitutions:
- `package com.gtnewhorizons.angelica.stereo;` → `package com.mitchellmarx.stereoscopic.core;`
- `import com.gtnewhorizons.angelica.config.AngelicaConfig;` → `import com.mitchellmarx.stereoscopic.config.StereoConfig;` (and corresponding `import` for the cursor stub)
- All `AngelicaConfig.X` → `StereoConfig.X`
- `import com.gtnewhorizons.angelica.stereo.CursorPresentThread;` is already in our `cursor` package — add `import com.mitchellmarx.stereoscopic.cursor.CursorPresentThread;`

- [ ] **Step 1: Write the file (verbatim port with substitutions)**

```java
package com.mitchellmarx.stereoscopic.core;

import com.mitchellmarx.stereoscopic.config.StereoConfig;
import com.mitchellmarx.stereoscopic.cursor.CursorPresentThread;
import lombok.Getter;

public class StereoState {

    public enum Eye { LEFT, RIGHT, MONO }

    public static final StereoState INSTANCE = new StereoState();

    private Eye currentEye = Eye.MONO;
    private boolean active = false;

    // While inGuiPass is true, GLStateManager.glScissor remaps caller scissor coords (assumed to
    // be "framebuffer pixels with GUI filling the whole screen") into the current eye viewport.
    // Set by MixinEntityRenderer_Stereo around each drawScreen / renderGameOverlay / Post-event
    // eye pass.
    private boolean inGuiPass = false;

    // While true, GLStateManager.glViewport intercepts attempts to set the viewport to the
    // full main framebuffer dimensions (which is what Iris's Pass.use() and CompositeRenderer
    // do at every shader phase change) and remaps them to the current eye viewport instead.
    // Set by MixinEntityRenderer_Stereo around each renderWorld eye pass.
    private boolean inWorldPass = false;

    private int eyeVpX = 0;
    private int eyeVpY = 0;
    private int eyeVpW = 0;
    private int eyeVpH = 0;

    public void enterGuiPass(int x, int y, int w, int h) {
        inGuiPass = true;
        eyeVpX = x;
        eyeVpY = y;
        eyeVpW = w;
        eyeVpH = h;
    }

    public void exitGuiPass() { inGuiPass = false; }
    public boolean isInGuiPass() { return inGuiPass; }

    public void enterWorldPass(int x, int y, int w, int h) {
        inWorldPass = true;
        eyeVpX = x;
        eyeVpY = y;
        eyeVpW = w;
        eyeVpH = h;
    }

    public void exitWorldPass() { inWorldPass = false; }
    public boolean isInWorldPass() { return inWorldPass; }

    public int getEyeVpX() { return eyeVpX; }
    public int getEyeVpY() { return eyeVpY; }
    public int getEyeVpW() { return eyeVpW; }
    public int getEyeVpH() { return eyeVpH; }

    public Eye getCurrentEye() {
        StereoDebugEye debug = StereoConfig.stereoDebugForceEye;
        if (debug != null && debug != StereoDebugEye.OFF) {
            return debug == StereoDebugEye.LEFT ? Eye.LEFT : Eye.RIGHT;
        }
        return currentEye;
    }

    public boolean isActive() {
        StereoDebugEye debug = StereoConfig.stereoDebugForceEye;
        if (debug != null && debug != StereoDebugEye.OFF) {
            return true;
        }
        return active;
    }

    /** Cached at frame start so config flips mid-frame don't cause inconsistency. */
    @Getter private StereoMode frameMode = StereoMode.OFF;
    @Getter private float frameIpd = 0.064f;
    @Getter private StereoHudMode frameHudMode = StereoHudMode.DUPLICATE;

    private StereoState() {}

    public boolean beginFrame() {
        StereoMode mode = StereoConfig.stereoscopicMode;
        if (mode == null || !mode.isActive()) {
            active = false;
            currentEye = Eye.MONO;
            frameMode = StereoMode.OFF;
            CursorPresentThread.stop();
            return false;
        }
        active = true;
        frameMode = mode;
        frameIpd = StereoConfig.stereoIpd;
        frameHudMode = StereoConfig.stereoHudMode != null
            ? StereoConfig.stereoHudMode
            : StereoHudMode.DUPLICATE;
        currentEye = Eye.MONO;
        CursorPresentThread.ensureStarted();
        return true;
    }

    public void endFrame() {
        active = false;
        currentEye = Eye.MONO;
        // Intentionally do NOT reset frameMode/frameIpd/frameHudMode here. RenderTickEvent.END
        // fires from FMLCommonHandler.onRenderTickEnd *after* updateCameraAndRender returns, and
        // MixinFMLCommonHandler_Stereo needs the frame's stereo config still readable so it can
        // duplicate the event per-eye. beginFrame() overwrites these on the next frame.
    }

    public void setEye(Eye eye) {
        this.currentEye = eye;
    }

    // Signs are flipped relative to vanilla's anaglyph convention. Vanilla renders each eye from
    // the OPPOSITE camera position, which works for red/cyan anaglyph because the brain doesn't
    // see real per-eye images, but is backwards for SBS where each eye directly sees its half.
    public float getEyeOffset() {
        if (!isActive()) return 0f;
        float ipd = StereoConfig.stereoIpd > 0f ? StereoConfig.stereoIpd : frameIpd;
        float half = ipd * 0.5f;
        switch (getCurrentEye()) {
            case LEFT:  return  half;
            case RIGHT: return -half;
            default:    return 0f;
        }
    }

    /** Hand-specific offset, currently not applied (HandRenderer uses getEyeOffset instead for viewer comfort). */
    public float getHandEyeOffset() {
        if (!isActive()) return 0f;
        float ipd = StereoConfig.stereoIpd > 0f ? StereoConfig.stereoIpd : frameIpd;
        float scale = ipd / 0.064f;
        float base = 0.1f * scale;
        switch (getCurrentEye()) {
            case LEFT:  return  base;
            case RIGHT: return -base;
            default:    return 0f;
        }
    }

    public boolean isLeftEye()  { return isActive() && getCurrentEye() == Eye.LEFT; }
    public boolean isRightEye() { return isActive() && getCurrentEye() == Eye.RIGHT; }

    /** Stable integer index for the current eye. LEFT/MONO map to 0, RIGHT maps to 1. */
    public int currentEyeIndex() {
        return getCurrentEye() == Eye.RIGHT ? 1 : 0;
    }

    /** 2 when stereo is enabled in config, otherwise 1. Reads config directly, not the cached
     *  {@link #active} flag, because RenderTargets is built at pipeline-init time which can fire
     *  before the first {@link #beginFrame()} — a cached value would still be false there and
     *  allocate mono targets, cross-contaminating the first stereo frame. */
    public int stereoEyeCount() {
        final StereoDebugEye debug = StereoConfig.stereoDebugForceEye;
        if (debug != null && debug != StereoDebugEye.OFF) return 2;
        final StereoMode mode = StereoConfig.stereoscopicMode;
        return (mode != null && mode.isActive()) ? 2 : 1;
    }

    // Iris-facing helpers: return the FB dimensions Iris should use for sizing render targets,
    // compute dispatch, and the viewWidth/viewHeight uniforms. ALWAYS return the full display
    // size even in SBS stereo — each eye renders at native aspect into its own per-eye FBO
    // chain, and the final per-eye blit squishes horizontally into that eye's main-FB region.
    public int irisFbWidth(int actualWidth)   { return actualWidth;  }
    public int irisFbHeight(int actualHeight) { return actualHeight; }
}
```

- [ ] **Step 2: Run the tests to verify they pass**

```bash
cd /c/CODE/Stereoscopic-Angelica
./gradlew test --tests com.mitchellmarx.stereoscopic.core.StereoStateTest
```

Expected: ALL 7 PASS.

- [ ] **Step 3: Commit**

```bash
git -C /c/CODE/Stereoscopic-Angelica add src/main/java/com/mitchellmarx/stereoscopic/core/StereoState.java src/test/java/com/mitchellmarx/stereoscopic/core/StereoStateTest.java
git -C /c/CODE/Stereoscopic-Angelica commit -m "feat(core): StereoState (port from sbs2) + sign-convention tests"
```

### Task 19 — REMOVED (was: StereoGLSMBridge port from sbs2)

> **Why removed:** The sbs2 `StereoGLSMBridge` registers a `StereoHook` against `GLSMHooks.stereoHook` — a hook surface that exists only on the abandoned `Angelica-sbs2` `stereo-sbs-2` branch and is **not present in published Angelica `[2.1, 2.2)`**. Since this mod is replacing Angelica-sbs (per spec §"Overview") and the spec mandates "external mixins only" (§"Non-Goals"), the in-Angelica hook approach is unreachable.
>
> **Where the functionality lands instead:** The four responsibilities the sbs2 bridge had — (1) scissor remap in GUI pass, (2) viewport remap in world pass, (3) viewport remap in GUI pass, (4) mouse coord swap — all serve features that Plan 1 explicitly scopes out (HUD, Iris, async cursor). They are reintroduced in the plan that first needs each one:
> - **Plan 2** adds an external mixin `MixinGLStateManager_StereoRemap` into Angelica's `GLStateManager` covering responsibilities (1), (2), (3). Plan 2's new Task 0 (or wherever it lands) introduces this before the HUD-duplication tasks that depend on it.
> - **Plan 3** handles responsibility (4) inside the async-cursor work (no GLSM mixin needed — the cursor backend reads coords directly).
>
> `StereoState`'s `isInGuiPass`/`isInWorldPass`/`enterGuiPass`/`enterWorldPass`/`eyeVp*` API surface (created in Task 18) is the consumer-side contract that the Plan 2 GLSM remap mixin will read. That surface is correct as-is and stays.

---

## Phase 4 — Render mixins

### Task 20: MixinEntityRenderer_StereoCamera (per-eye camera shift)

**Files:**
- Create: `C:\CODE\Stereoscopic-Angelica\src\mixin\java\com\mitchellmarx\stereoscopic\mixin\minecraft\MixinEntityRenderer_StereoCamera.java`

Reference: `C:\CODE\Angelica-sbs2\src\mixin\java\com\gtnewhorizons\angelica\mixins\early\angelica\stereo\MixinEntityRenderer_StereoCamera.java` (71 lines). Port verbatim with package change.

- [ ] **Step 1: Write the file (verbatim port)**

```java
package com.mitchellmarx.stereoscopic.mixin.minecraft;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.mitchellmarx.stereoscopic.core.StereoState;
import net.minecraft.client.renderer.EntityRenderer;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Per-eye camera shift on {@code EntityRenderer.setupCameraTransform}. Priority 999 so it lands
 * before Angelica's matrix-capture mixin, letting Iris shader uniforms ({@code gbufferModelView},
 * {@code gbufferProjection}) pick up the eye offset for free.
 */
@Mixin(value = EntityRenderer.class, priority = 999)
public abstract class MixinEntityRenderer_StereoCamera {

    @Inject(
        method = "setupCameraTransform",
        at = @At(
            value = "INVOKE",
            target = "Lorg/lwjgl/util/glu/Project;gluPerspective(FFFF)V",
            ordinal = 0,
            shift = At.Shift.BEFORE,
            remap = false
        )
    )
    private void stereoscopic$applyStereoProjectionOffset(float partialTicks, int pass, CallbackInfo ci) {
        // === DISABLED: parallel-axis stereo avoids the asymmetric-frustum gap between eyes ===
        // Re-enable if/when we move to proper toed-in / asymmetric-frustum stereo.
        if (true) return; // intentional disable — see fence above
        // === END DISABLED ===
        if (!StereoState.INSTANCE.isActive()) return;
        float dx = StereoState.INSTANCE.getEyeOffset();
        if (dx == 0f) return;
        GLStateManager.glTranslatef(-dx * 1.09f, 0f, 0f);
    }

    @Inject(
        method = "setupCameraTransform",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/EntityRenderer;hurtCameraEffect(F)V",
            ordinal = 0,
            shift = At.Shift.BEFORE
        )
    )
    private void stereoscopic$applyStereoModelviewOffset(float partialTicks, int pass, CallbackInfo ci) {
        if (!StereoState.INSTANCE.isActive()) return;
        float dx = StereoState.INSTANCE.getEyeOffset();
        if (dx == 0f) return;
        GLStateManager.glMatrixMode(GL11.GL_MODELVIEW);
        GLStateManager.glTranslatef(dx, 0f, 0f);
    }
}
```

- [ ] **Step 2: Compile**

```bash
cd /c/CODE/Stereoscopic-Angelica
./gradlew compileMixinJava
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git -C /c/CODE/Stereoscopic-Angelica add src/mixin/java/com/mitchellmarx/stereoscopic/mixin/minecraft/MixinEntityRenderer_StereoCamera.java
git -C /c/CODE/Stereoscopic-Angelica commit -m "feat(mixin): per-eye camera shift on setupCameraTransform"
```

### Task 21: MixinFramebuffer_Stereo (eye viewport restore)

**Files:**
- Create: `C:\CODE\Stereoscopic-Angelica\src\mixin\java\com\mitchellmarx\stereoscopic\mixin\minecraft\MixinFramebuffer_Stereo.java`

Reference: `C:\CODE\Angelica-sbs2\src\mixin\java\com\gtnewhorizons\angelica\mixins\early\angelica\stereo\MixinFramebuffer_Stereo.java` (49 lines). Port verbatim.

- [ ] **Step 1: Write the file**

```java
package com.mitchellmarx.stereoscopic.mixin.minecraft;

import com.mitchellmarx.stereoscopic.core.StereoMode;
import com.mitchellmarx.stereoscopic.core.StereoState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.shader.Framebuffer;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Iris's pipeline rebinds the main framebuffer mid-renderWorld. {@code bindFramebuffer(true)}
 * calls {@code glViewport(0,0,fbW,fbH)}, which clobbers the per-eye viewport set by
 * {@link MixinEntityRenderer_Stereo}. Without this restore, both eye passes render to the full
 * screen and you get a continuous panorama instead of SBS.
 */
@Mixin(Framebuffer.class)
public class MixinFramebuffer_Stereo {

    @Inject(method = "bindFramebuffer", at = @At("RETURN"))
    private void stereoscopic$restoreStereoViewport(boolean updateViewport, CallbackInfo ci) {
        if (!updateViewport) return;
        final StereoMode mode = StereoState.INSTANCE.getFrameMode();
        if (mode == null || !mode.isActive()) return;
        final StereoState.Eye eye = StereoState.INSTANCE.getCurrentEye();
        if (eye != StereoState.Eye.LEFT && eye != StereoState.Eye.RIGHT) return;

        final Minecraft mc = Minecraft.getMinecraft();
        final int fullW = mc.displayWidth;
        final int fullH = mc.displayHeight;
        final boolean sbs = mode.isSideBySide();
        final boolean half = mode.isHalf();
        final int eyeW = sbs ? (half ? fullW / 2 : fullW) : fullW;
        final int eyeH = sbs ? fullH               : (half ? fullH / 2 : fullH);

        final int x, y;
        if (eye == StereoState.Eye.LEFT) {
            x = 0;
            y = sbs ? 0 : fullH - eyeH;
        } else {
            x = sbs ? eyeW : 0;
            y = 0;
        }
        GL11.glViewport(x, y, eyeW, eyeH);
    }
}
```

- [ ] **Step 2: Compile**

```bash
./gradlew compileMixinJava
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git -C /c/CODE/Stereoscopic-Angelica add src/mixin/java/com/mitchellmarx/stereoscopic/mixin/minecraft/MixinFramebuffer_Stereo.java
git -C /c/CODE/Stereoscopic-Angelica commit -m "feat(mixin): restore per-eye viewport after Framebuffer.bindFramebuffer"
```

### Task 22a: ChromaticTooltipsCompat (port from sbs2) + init wiring

**Files:**
- Create: `C:\CODE\Stereoscopic-Angelica\src\main\java\com\mitchellmarx\stereoscopic\compat\chromatictooltips\ChromaticTooltipsCompat.java`
- Modify: `C:\CODE\Stereoscopic-Angelica\src\main\java\com\mitchellmarx\stereoscopic\Stereoscopic.java` — add a `FMLInitializationEvent` handler that calls `ChromaticTooltipsCompat.init()`.

Reference: `C:\CODE\Angelica-sbs2\src\main\java\com\gtnewhorizons\angelica\compat\chromatictooltips\ChromaticTooltipsCompat.java` (50 lines). The class is a small reflective shim into the optional `chromatictooltips` mod: it caches a `Field` handle to `TooltipHandler.renderLastTooltip` at init, and re-arms the flag between LEFT/RIGHT eye `DrawScreenEvent.Post` events at runtime so the tooltip draws into both eye halves. Gracefully no-ops when the `chromatictooltips` mod isn't loaded.

Port verbatim. The only substitution is the package: `com.gtnewhorizons.angelica.compat.chromatictooltips` → `com.mitchellmarx.stereoscopic.compat.chromatictooltips`.

- [ ] **Step 1: Port the class**

```java
package com.mitchellmarx.stereoscopic.compat.chromatictooltips;

import cpw.mods.fml.common.Loader;

import java.lang.reflect.Field;

/**
 * ChromaticTooltips defers tooltip rendering: its {@code GuiContainerManager.renderToolTips}
 * mixin caches the tooltip and sets {@code TooltipHandler.renderLastTooltip = true}, then a
 * {@code DrawScreenEvent.Post} subscriber calls {@code drawLastTooltip} which renders if the
 * flag is set and clears it. Stereo posts the event twice (per eye); the LEFT post clears the
 * flag, so the RIGHT post has to re-arm to draw.
 *
 * <p>The mod is optional. The field lookup runs once at mod init and surfaces API drift loudly
 * via {@link ReflectiveOperationException}; the runtime re-arm path is exception-free after
 * {@code setAccessible(true)} succeeded at init.
 */
public final class ChromaticTooltipsCompat {

    private static final String MOD_ID = "chromatictooltips";
    private static final String TOOLTIP_HANDLER_FQCN = "com.slprime.chromatictooltips.TooltipHandler";
    private static final String FIELD_NAME = "renderLastTooltip";

    private static Field renderLastTooltipField;

    private ChromaticTooltipsCompat() {}

    /**
     * Resolves and caches the reflective handle. Throws if ChromaticTooltips is loaded but its
     * API surface has drifted — caller should fail mod init rather than silently degrade.
     */
    public static void init() throws ReflectiveOperationException {
        if (!Loader.isModLoaded(MOD_ID)) return;
        final Field f = Class.forName(TOOLTIP_HANDLER_FQCN).getDeclaredField(FIELD_NAME);
        f.setAccessible(true);
        renderLastTooltipField = f;
    }

    /** Re-arm the deferred-render flag between stereo eye Post events. No-op when not loaded. */
    public static void rearm() {
        if (renderLastTooltipField == null) return;
        try {
            renderLastTooltipField.setBoolean(null, true);
        } catch (IllegalAccessException e) {
            // setAccessible(true) was called in init(); reaching this implies a JVM/security
            // policy change at runtime, which we don't support.
            throw new AssertionError(e);
        }
    }
}
```

- [ ] **Step 2: Wire `init()` from `Stereoscopic.java`**

Add a new `@Mod.EventHandler` for `FMLInitializationEvent`. Sbs2 wraps the call in a try/catch that converts the checked `ReflectiveOperationException` to a fatal `RuntimeException` ("surface API drift as fatal init failure rather than degrade silently"). Port that pattern.

Final `Stereoscopic.java`:

```java
package com.mitchellmarx.stereoscopic;

import com.mitchellmarx.stereoscopic.compat.chromatictooltips.ChromaticTooltipsCompat;
import com.mitchellmarx.stereoscopic.config.StereoConfig;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

@Mod(
    modid = Stereoscopic.MODID,
    name = "StereoCraft: Stereoscopic 3D",
    version = Tags.VERSION,
    dependencies = "required-after:angelica;required-after:gtnhlib",
    acceptableRemoteVersions = "*",
    acceptedMinecraftVersions = "[1.7.10]"
)
@SideOnly(Side.CLIENT)
public class Stereoscopic {
    public static final String MODID = "stereoscopic";

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        StereoConfig.load(event.getSuggestedConfigurationFile());
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        try {
            ChromaticTooltipsCompat.init();
        } catch (ReflectiveOperationException e) {
            // Forge's @EventHandler init signature can't propagate checked exceptions; surface
            // ChromaticTooltips API drift as a fatal mod-init failure rather than degrade silently.
            throw new RuntimeException(e);
        }
    }
}
```

- [ ] **Step 3: Compile + commit**

```bash
./gradlew compileJava
git -C /c/CODE/Stereoscopic-Angelica add src/main/java/com/mitchellmarx/stereoscopic/compat/chromatictooltips/ChromaticTooltipsCompat.java src/main/java/com/mitchellmarx/stereoscopic/Stereoscopic.java
git -C /c/CODE/Stereoscopic-Angelica commit -m "feat(compat): port ChromaticTooltipsCompat from sbs2 + wire init() from @Mod"
```

### Phase 4 Tasks 22b–22i — preface

Task 22 (the 347-line `MixinEntityRenderer_Stereo` verbatim port) is the integration point that consumes four sbs2-side capabilities not yet present in this mod:

1. A real `StereoCursor` with `update()` and a real `CursorPresentThread` with `isRunning()` (the Phase 3 stubs lack these methods).
2. `HUDCaching.renderCachedHudStereo(...)` — sbs2 *added* this method to Angelica's `HUDCaching` source. Published Angelica `[2.1, 2.2)` doesn't have it; we add it back as an external mixin.
3. `WorldRenderingPipeline.setActiveEye(int)` — sbs2 added this default method to Iris's `WorldRenderingPipeline` interface. Same story; external mixin restores it.
4. `ChromaticTooltipsCompat.rearm()` — handled in Task 22a above.

Tasks 22b–22i deliver (1), (2), and (3). Order matters: cursor scaffolding (22b→22f) lands before the HUDCaching/Iris extension mixins (22g→22h), and `mixins.stereoscopic.json` is updated last (22i) so each `compileMixinJava` along the way only validates classes that already exist.

**Reference orientation for the cursor work:**
- **Architecture** comes from spec lines 94–98 and the Fabric port at `C:\CODE\Stereoscopic` — split into `CursorBackend` interface + `WindowsCursorBackend` + `NoOpCursorBackend` + `CursorPresentThread` (uses backend) + `StereoCursor` (main-thread facade).
- **Runtime API choices** come from sbs2 (`C:\CODE\Angelica-sbs2`) — LWJGL 2 `Mouse` / `Display`, SDL3.dll via LWJGL 3 JNI for cursor hide/show (NOT GLFW — lwjgl3ify-3.0.17 ships SDL3, has no GLFW), `wglGetCurrentDC` + `WindowFromDC` for the HWND. See sbs2's `CursorPresentThread` lines 28–29, 88–89, 259–269, 383–391, 486–522 for the load-bearing SDL3/JNI rationale comments.
- **Filename layout** matches spec.

### Task 22b: CursorBackend interface

**Files:**
- Create: `C:\CODE\Stereoscopic-Angelica\src\main\java\com\mitchellmarx\stereoscopic\cursor\CursorBackend.java`

Pluggable OS-cursor backend interface. Adopt the Fabric port's shape (spec ratifies it at lines 94–98).

- [ ] **Step 1: Write the file**

```java
package com.mitchellmarx.stereoscopic.cursor;

/**
 * Pluggable OS-level cursor backend. GLFW's (or here, SDL3's) cursor presents once per
 * render frame — in stereo, render hitches translate directly into cursor stutter. A native
 * backend captures the OS arrow and lets the present thread render it independently of the
 * main render loop's framerate.
 */
public interface CursorBackend {

    /** BGRA pixels of the OS arrow + hotspot, or null if capture failed. */
    Sprite captureArrowBitmap();

    /** Clip the OS cursor to MC's client rect (true) or release (false). */
    void trapCursor(boolean clip);

    /** Free native resources. Called on mod shutdown. */
    void release();

    /** Whether this backend is usable on the current platform. */
    boolean isSupported();

    final class Sprite {
        public final int width;
        public final int height;
        public final int hotspotX;
        public final int hotspotY;
        public final byte[] bgra;

        public Sprite(int width, int height, int hotspotX, int hotspotY, byte[] bgra) {
            this.width = width;
            this.height = height;
            this.hotspotX = hotspotX;
            this.hotspotY = hotspotY;
            this.bgra = bgra;
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git -C /c/CODE/Stereoscopic-Angelica add src/main/java/com/mitchellmarx/stereoscopic/cursor/CursorBackend.java
git -C /c/CODE/Stereoscopic-Angelica commit -m "feat(cursor): CursorBackend interface (pluggable OS cursor backend)"
```

### Task 22c: NoOpCursorBackend

**Files:**
- Create: `C:\CODE\Stereoscopic-Angelica\src\main\java\com\mitchellmarx\stereoscopic\cursor\NoOpCursorBackend.java`

- [ ] **Step 1: Write the file**

```java
package com.mitchellmarx.stereoscopic.cursor;

/**
 * Fallback backend for non-Windows platforms (Mac/Linux in v0.1.0) and for the case where
 * Windows backend init fails. {@link #isSupported()} returns false so the cursor present
 * thread won't start; the rest of the stereo system continues to work (just without the
 * async cursor overlay).
 */
public final class NoOpCursorBackend implements CursorBackend {
    @Override public Sprite captureArrowBitmap() { return null; }
    @Override public void trapCursor(boolean clip) { /* no-op */ }
    @Override public void release() { /* no-op */ }
    @Override public boolean isSupported() { return false; }
}
```

- [ ] **Step 2: Commit**

```bash
git -C /c/CODE/Stereoscopic-Angelica add src/main/java/com/mitchellmarx/stereoscopic/cursor/NoOpCursorBackend.java
git -C /c/CODE/Stereoscopic-Angelica commit -m "feat(cursor): NoOpCursorBackend fallback (Mac/Linux + init-failure path)"
```

### Task 22d: WindowsCursorBackend (port from sbs2's CursorPresentThread Win32 sections)

**Files:**
- Create: `C:\CODE\Stereoscopic-Angelica\src\main\java\com\mitchellmarx\stereoscopic\cursor\WindowsCursorBackend.java`

**Source:** `C:\CODE\Angelica-sbs2\src\main\java\com\gtnewhorizons\angelica\stereo\CursorPresentThread.java` (1253 lines). sbs2 keeps the Win32-specific logic inline in `CursorPresentThread`; we factor it out into this backend class per the spec's split.

**What to lift into this file from sbs2's CursorPresentThread:**
- Native function pointer resolution: `LoadLibraryA`/`GetProcAddress` for `user32.dll` (`GetCursorPos`, `SetCursorPos`, `ClientToScreen`, `ScreenToClient`, `GetClientRect`, `GetForegroundWindow`, `ClipCursor`, `LoadCursorA`, `GetIconInfo`, `GetDC`, `ReleaseDC`, `GetSystemMetrics`), `gdi32.dll` (`GetDIBits`, `CreateCompatibleDC`, `DeleteDC`, `SelectObject`, `DeleteObject`), `opengl32.dll` (`wglGetCurrentDC`), and `kernel32.dll` (`GetLastError`).
- HWND resolution: `wglGetCurrentDC()` → `WindowFromDC(hdc)`. **Do NOT use GLFW** — lwjgl3ify-3.0.17 has no GLFW (spec goal L; sbs2 documents this in load-bearing comments at lines 28–29, 88–89, 486–522).
- Arrow capture: `LoadCursorA(NULL, IDC_ARROW)` → `GetIconInfo` → `GetDIBits` → 32-bpp BGRA pixel buffer + hotspot. Return as `CursorBackend.Sprite`.
- ClipCursor trap: cache the client rect via `GetClientRect` + `ClientToScreen`, call `ClipCursor(rect)` on trap, `ClipCursor(NULL)` on release.
- Foreground window check: `GetForegroundWindow() == ourHwnd` — used internally by ClipCursor logic to avoid clipping when MC isn't focused.

**What stays in `CursorPresentThread` (Task 22e):** the thread itself, the polling loop, SDL3 hide/show calls, the StereoCursor coupling.

**What needs the constructor:** the HWND. Either `WindowsCursorBackend(long hwnd)` taking it from the caller (Fabric port pattern — main thread resolves HWND first, then constructs the backend), or have `WindowsCursorBackend()` resolve internally on first use. Match sbs2's pattern: resolve internally; backend caches once it has it.

- [ ] **Step 1: Read sbs2's `CursorPresentThread` end-to-end**

```bash
wc -l /c/CODE/Angelica-sbs2/src/main/java/com/gtnewhorizons/angelica/stereo/CursorPresentThread.java
```

- [ ] **Step 2: Identify the Win32-specific call sites** (search for `JNI.invoke`, `GetProcAddress`, `LoadLibraryA`, `user32`, `gdi32`, `opengl32`).

- [ ] **Step 3: Write `WindowsCursorBackend.java`** implementing `CursorBackend`. Move the Win32 helpers + arrow capture + ClipCursor + HWND resolution. Use `JNI.invoke*` from `org.lwjgl.system.JNI` (LWJGL 3's JNI invoker — available via lwjgl3ify's LWJGL 3 shim layer).

  Expected size: ~300–400 lines (Fabric port's `WindowsCursorBackend` is 316 lines and covers comparable scope, though using GLFW for HWND — substitute the `wglGetCurrentDC`/`WindowFromDC` path here).

- [ ] **Step 4: Compile**

```bash
./gradlew compileJava
```

- [ ] **Step 5: Commit**

```bash
git -C /c/CODE/Stereoscopic-Angelica add src/main/java/com/mitchellmarx/stereoscopic/cursor/WindowsCursorBackend.java
git -C /c/CODE/Stereoscopic-Angelica commit -m "feat(cursor): WindowsCursorBackend (sbs2 Win32 logic isolated behind CursorBackend)"
```

### Task 22e: CursorPresentThread real impl (replaces Phase 3 stub)

**Files:**
- Modify (overwrite): `C:\CODE\Stereoscopic-Angelica\src\main\java\com\mitchellmarx\stereoscopic\cursor\CursorPresentThread.java`

**Source:** `C:\CODE\Angelica-sbs2\src\main\java\com\gtnewhorizons\angelica\stereo\CursorPresentThread.java`. After lifting the Win32 logic into `WindowsCursorBackend` (Task 22d), what's left is the thread infrastructure: lifecycle (`ensureStarted` / `stop` / `isRunning`), the polling loop, SDL3 cursor hide/show via LWJGL 3 JNI, the StereoCursor coupling (`setCursorHidden` / `setCursorMode` / `resetCursorPolling` plus the public constants `CURSOR_NORMAL` / `CURSOR_DISABLED`).

Replace the Phase 3 stub (currently no-op `ensureStarted`/`stop`) with the real implementation. Keep the public API surface compatible — `StereoState` calls `ensureStarted()` and `stop()` (Phase 3 commit `cc9ee27`), so those signatures must not change.

Substitution rules (same as Plan 1 §"File Structure"):
- Package: `com.gtnewhorizons.angelica.stereo` → `com.mitchellmarx.stereoscopic.cursor`
- `import com.gtnewhorizons.angelica.config.AngelicaConfig;` → remove
- `AngelicaConfig.<ported field>` → `StereoConfig.<field>` (for `stereoscopicMode`, `stereoIpd`, `stereoHudMode`, `stereoDebugForceEye`)
- `AngelicaConfig.<other field>` (e.g. `enableIris` if it appears) → fully-qualified `com.gtnewhorizons.angelica.config.AngelicaConfig.<field>`
- Move all Win32-specific calls to `WindowsCursorBackend` (Task 22d) — `CursorPresentThread` calls into the backend via `backend.trapCursor(boolean)`, `backend.captureArrowBitmap()`, `backend.release()`.

- [ ] **Step 1: Verify the public API surface needed** — search the current codebase for callers of `CursorPresentThread`:

```bash
grep -r 'CursorPresentThread\.' /c/CODE/Stereoscopic-Angelica/src/
```

Confirm callers expect: `ensureStarted()`, `stop()`, `isRunning()`, and (from sbs2's `StereoCursor`) `setCursorHidden(boolean)`, `setCursorMode(int)`, `resetCursorPolling()`, `CURSOR_NORMAL`, `CURSOR_DISABLED`.

- [ ] **Step 2: Port the file, refactored**, with the Win32-specific blocks delegated to a `CursorBackend` instance. Expected size: ~600–700 lines (sbs2's 1253 lines minus the ~400 lines that became `WindowsCursorBackend`).

**Backend selection.** `ensureStarted()` chooses the backend internally on first call: instantiate `WindowsCursorBackend` if `System.getProperty("os.name")` starts with `Windows`, otherwise `NoOpCursorBackend`. Cache the instance. Keep API simple — callers (`StereoState`, `StereoCursor`) never see the backend type. (Spec goal G's "pluggable CursorBackend interface with NoOpCursorBackend fallback" is satisfied by this internal selection; we avoid a separate `CursorBackendSelector` class.)

- [ ] **Step 3: Compile**

```bash
./gradlew compileJava
```

- [ ] **Step 4: Commit**

```bash
git -C /c/CODE/Stereoscopic-Angelica add src/main/java/com/mitchellmarx/stereoscopic/cursor/CursorPresentThread.java
git -C /c/CODE/Stereoscopic-Angelica commit -m "feat(cursor): real CursorPresentThread (refactored, uses CursorBackend)"
```

### Task 22f: StereoCursor real impl (replaces Phase 3 stub)

**Files:**
- Modify (overwrite): `C:\CODE\Stereoscopic-Angelica\src\main\java\com\mitchellmarx\stereoscopic\cursor\StereoCursor.java`

**Source:** `C:\CODE\Angelica-sbs2\src\main\java\com\gtnewhorizons\angelica\stereo\StereoCursor.java` (125 lines). Substitutions:
- Package: `com.gtnewhorizons.angelica.stereo` → `com.mitchellmarx.stereoscopic.cursor`
- `import com.gtnewhorizons.angelica.config.AngelicaConfig;` → `import com.mitchellmarx.stereoscopic.config.StereoConfig;`
- `AngelicaConfig.stereoscopicMode` / `AngelicaConfig.stereoHudMode` → `StereoConfig.<same>`
- `import com.gtnewhorizons.angelica.stereo.StereoMode/StereoHudMode` → `import com.mitchellmarx.stereoscopic.core.<same>`

Replace the Phase 3 stub which only exposes `getX/getY/getEventX/getEventY` returning LWJGL `Mouse.<same>`. The real port adds `update()`, `setVirtualPos(double, double)`, `virtualX()`, `virtualY()`, `isActive()`, and the SBS-half coord-doubling in the four `get*` methods.

- [ ] **Step 1: Port the file** (verbatim with the 4 substitutions above).

- [ ] **Step 2: Compile**

```bash
./gradlew compileJava
```

- [ ] **Step 3: Commit**

```bash
git -C /c/CODE/Stereoscopic-Angelica add src/main/java/com/mitchellmarx/stereoscopic/cursor/StereoCursor.java
git -C /c/CODE/Stereoscopic-Angelica commit -m "feat(cursor): real StereoCursor (port from sbs2, replaces Plan 1 stub)"
```

### Task 22g: MixinHUDCaching_Stereo — make `renderCachedHud` stereo-aware via `@Redirect` (no mod compileOnlies)

**Files:**
- Create: `C:\CODE\Stereoscopic-Angelica\src\main\java\com\mitchellmarx\stereoscopic\compat\xaero\XaeroCompat.java`
- Modify: `C:\CODE\Stereoscopic-Angelica\src\main\java\com\mitchellmarx\stereoscopic\Stereoscopic.java` — extend the existing `FMLInitializationEvent` handler (added by Task 22a for `ChromaticTooltipsCompat.init()`) to also call `XaeroCompat.init()`.
- Create: `C:\CODE\Stereoscopic-Angelica\src\mixin\java\com\mitchellmarx\stereoscopic\mixin\angelica\MixinHUDCaching_Stereo.java`

**Why this shape:** sbs2 made HUD caching stereo-aware by refactoring `HUDCaching.renderCachedHud` into helpers (`maybeUpdateCache` + `blitCachedHud`) and writing a new `renderCachedHudStereo` that calls them in a stereo-friendly order. Copying that approach into our mod would require copying `blitCachedHud`'s body — which directly references `Thaumcraft.instance.renderEventHandler` and `ThaumicHorizons.instance.renderEventHandler` (base Angelica compat code, not sbs2-added) — forcing Thaumcraft + ThaumicHorizons onto our compileOnly classpath.

Two observations make this avoidable:
1. The published `renderCachedHud` is *almost* idempotent under repeated calls — only one phase (the cache-fill `ingame.renderGameOverlay(...)` call inside the dirty branch) is viewport-sensitive in a way that breaks if the caller's viewport isn't full-screen.
2. The Xaero pre-render hook at the very top of `renderCachedHud` fires on *every* call. Sbs2 hand-coded `renderCachedHudStereo` to fire it once. Firing twice per frame might be cheap and idempotent — but might be expensive (state setup, animation tick, etc.); we don't know Xaero's internals and shouldn't gamble on it for FPS.

Both problems can be fixed by two targeted `@Redirect`s on `HUDCaching.renderCachedHud` without copying any of its body. The Xaero gate goes through a reflective `XaeroCompat` shim (mirroring the `ChromaticTooltipsCompat` pattern we already established) so we don't need Xaero on our compileOnly classpath either.

#### Step 1: Create `XaeroCompat.java`

Mirror the structure of `ChromaticTooltipsCompat`:
- `private static MethodHandle handle;`
- `private static final String MOD_ID = "XaeroMinimap";` (verify by checking sbs2's `Loader.isModLoaded` argument — search for `isXaerosMinimapLoaded` in sbs2's `ModStatus` to confirm the exact id).
- `private static final String XAERO_FQCN = "xaero.common.core.XaeroMinimapCore";`
- `public static void init() throws ReflectiveOperationException` — checks `Loader.isModLoaded(MOD_ID)`; if loaded, resolves `XaeroMinimapCore.beforeIngameGuiRender(float)` via `MethodHandles.lookup().findStatic(...)` and caches.
- `public static void beforeIngameGuiRender(float partialTicks)` — invokes the cached `MethodHandle.invokeExact(partialTicks)`; no-op if `handle == null`. Wrap with `try/catch (Throwable)` and rethrow as `AssertionError` like `ChromaticTooltipsCompat.rearm()` does.
- `public static boolean isLoaded()` — `return handle != null;`

About 50 lines, structurally identical to `ChromaticTooltipsCompat`.

#### Step 2: Wire `XaeroCompat.init()` from `Stereoscopic.java`

Extend the existing `init()` `@Mod.EventHandler` (added by Task 22a):

```java
@Mod.EventHandler
public void init(FMLInitializationEvent event) {
    try {
        ChromaticTooltipsCompat.init();
        XaeroCompat.init();
    } catch (ReflectiveOperationException e) {
        // Mod API drift on either of the optional compat shims is a fatal init failure
        // rather than a silent degrade — same pattern as sbs2's ClientProxy.init.
        throw new RuntimeException(e);
    }
}
```

#### Step 3: Write `MixinHUDCaching_Stereo.java`

Two `@Redirect`s, both targeting call sites inside `HUDCaching.renderCachedHud`.

```java
package com.mitchellmarx.stereoscopic.mixin.angelica;

import com.gtnewhorizons.angelica.hudcaching.HUDCaching;
import com.mitchellmarx.stereoscopic.compat.xaero.XaeroCompat;
import com.mitchellmarx.stereoscopic.core.StereoState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiIngame;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.nio.IntBuffer;

/**
 * Makes Angelica's {@link HUDCaching#renderCachedHud} stereo-aware via two surgical @Redirects:
 *
 * <ol>
 *   <li>Gates the Xaero pre-render hook to once-per-frame (sbs2's renderCachedHudStereo
 *       fires it once; calling Angelica's renderCachedHud twice would fire it twice).
 *   <li>Forces glViewport to full-screen around the dirty-branch's cache-fill
 *       renderGameOverlay call (ordinal 1 — the one inside `if (dirty)`, not the early-out
 *       in the `!hudCachingActive` branch). The cache framebuffer is full-display-sized; if
 *       the caller has an eye-region viewport set, the HUD renders into a half of the cache
 *       and the second eye then blits a half-filled cache. With this @Redirect, our stereo
 *       path can set the eye viewport, call renderCachedHud, and get one full-screen cache
 *       fill plus one eye-region blit per call.
 * </ol>
 *
 * <p>The captured-bits and final blit phases of renderCachedHud naturally respect the
 * caller's viewport (they use {@code setupOverlayRendering}'s ortho projection rasterized
 * to the current viewport). Those fire twice per frame in stereo, matching sbs2's
 * blitCachedHud-twice behavior.
 */
@Mixin(HUDCaching.class)
public abstract class MixinHUDCaching_Stereo {

    @Unique private static long stereoscopic$lastXaeroFrameToken = -1L;
    @Unique private static final IntBuffer stereoscopic$viewportScratch = BufferUtils.createIntBuffer(16);

    @Redirect(
        method = "renderCachedHud",
        remap = false,   // method= refers to Angelica's HUDCaching, no MCP→SRG mapping
        at = @At(
            value = "INVOKE",
            target = "Lxaero/common/core/XaeroMinimapCore;beforeIngameGuiRender(F)V",
            remap = false
        )
    )
    private static void stereoscopic$gateXaeroOncePerFrame(float partialTicks) {
        if (!StereoState.INSTANCE.isActive()) {
            // Non-stereo path: pass through unchanged.
            XaeroCompat.beforeIngameGuiRender(partialTicks);
            return;
        }
        // Frame token: a monotonic per-render value we can detect repetition with. Minecraft's
        // own timer counter works; if a simpler token exists on StereoState use that. The check
        // must distinguish "first renderCachedHud call this frame" from "second/Nth call same frame".
        final long token = Minecraft.getMinecraft().getSystemTime();
        if (token != stereoscopic$lastXaeroFrameToken) {
            stereoscopic$lastXaeroFrameToken = token;
            XaeroCompat.beforeIngameGuiRender(partialTicks);
        }
    }

    @Redirect(
        method = "renderCachedHud",
        remap = false,   // method= refers to Angelica's HUDCaching, no MCP→SRG mapping
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiIngame;renderGameOverlay(FZII)V",
            ordinal = 1   // 0 is the !hudCachingActive bail-out; 1 is the cache-fill call
                          // @At target IS vanilla MC; keeps default remap = true so MCP→SRG applies
        )
    )
    private static void stereoscopic$cacheFillAtFullScreen(GuiIngame ingame, float partialTicks,
                                                           boolean hasScreen, int mouseX, int mouseY) {
        if (!StereoState.INSTANCE.isActive()) {
            // Non-stereo path: pass through unchanged.
            ingame.renderGameOverlay(partialTicks, hasScreen, mouseX, mouseY);
            return;
        }
        // Save current viewport, force full-screen for the cache render, restore on return.
        stereoscopic$viewportScratch.clear();
        GL11.glGetInteger(GL11.GL_VIEWPORT, stereoscopic$viewportScratch);
        final int x = stereoscopic$viewportScratch.get(0);
        final int y = stereoscopic$viewportScratch.get(1);
        final int w = stereoscopic$viewportScratch.get(2);
        final int h = stereoscopic$viewportScratch.get(3);
        final Minecraft mc = Minecraft.getMinecraft();
        GL11.glViewport(0, 0, mc.displayWidth, mc.displayHeight);
        try {
            ingame.renderGameOverlay(partialTicks, hasScreen, mouseX, mouseY);
        } finally {
            GL11.glViewport(x, y, w, h);
        }
    }
}
```

**Notes for the implementer:**
- Verify the Xaero `target=` descriptor by checking `xaero.common.core.XaeroMinimapCore.beforeIngameGuiRender`'s actual signature in published Angelica's classpath (likely `(F)V` but confirm). `remap = false` because the class isn't in vanilla Minecraft's namespace.
- Verify `ordinal = 1` for the second `renderGameOverlay` call by re-reading base Angelica's `renderCachedHud` (the version at `git -C /c/CODE/Angelica-sbs2 show e4345194~1:src/main/java/com/gtnewhorizons/angelica/hudcaching/HUDCaching.java`). The first `renderGameOverlay` is in the `if (!AngelicaConfig.hudCachingActive)` early-out branch; the second is in the `if (dirty)` cache-fill branch.
- The frame-token approach using `getSystemTime()` is good enough — two renderCachedHud calls within the same frame happen within a millisecond, sharing the same systime value. If Plan 2's per-eye HUD work needs sub-millisecond precision, switch to `StereoState.INSTANCE.getFrameMode()` transitions or a `@Unique` per-frame counter incremented by `MixinEntityRenderer_Stereo` at `beginFrame`.

#### Step 4: Compile

```bash
/c/CODE/Stereoscopic-Angelica/gradlew -p /c/CODE/Stereoscopic-Angelica compileJava compileMixinJava
```

Should succeed cleanly — no new compileOnly deps needed. If the Xaero target descriptor is wrong, mixin annotation processing fails with a clear "target not found" message; fix the descriptor and re-compile.

#### Step 5: Commit

```bash
git -C /c/CODE/Stereoscopic-Angelica add \
    src/main/java/com/mitchellmarx/stereoscopic/compat/xaero/XaeroCompat.java \
    src/main/java/com/mitchellmarx/stereoscopic/Stereoscopic.java \
    src/mixin/java/com/mitchellmarx/stereoscopic/mixin/angelica/MixinHUDCaching_Stereo.java
git -C /c/CODE/Stereoscopic-Angelica commit -m "feat(mixin): make HUDCaching stereo-aware via @Redirect (Xaero gate + cache-fill viewport)"
```

### Task 22h: MixinWorldRenderingPipeline_SetActiveEye + IStereoPipeline duck interface

**Files:**
- Create: `C:\CODE\Stereoscopic-Angelica\src\mixin\java\com\mitchellmarx\stereoscopic\mixin\iris\IStereoPipeline.java`
- Create: `C:\CODE\Stereoscopic-Angelica\src\mixin\java\com\mitchellmarx\stereoscopic\mixin\iris\MixinWorldRenderingPipeline_SetActiveEye.java`

**Source:** the `default void setActiveEye(int eye)` declaration in `C:\CODE\Angelica-sbs2\src\main\java\net\coderbot\iris\pipeline\WorldRenderingPipeline.java` line 51. Sbs2 added a no-op default method to the Iris interface. Published Angelica's bundled Iris doesn't have it.

**Mechanism:** Mixin's `@Unique default` on an interface mixin adds the method at runtime, but the Java compiler doesn't see it on the original interface — so call sites like `pipeline.setActiveEye(eyeIndex)` (Task 22) won't compile if we route them through `WorldRenderingPipeline`. To bridge that gap, we define a small "duck interface" `IStereoPipeline` in our mod with the method as a default; the mixin extends it. At runtime, mixin AP attaches `IStereoPipeline` (and its default method) to `WorldRenderingPipeline`. At compile time, stereo-aware code casts the pipeline to `IStereoPipeline` and calls `setActiveEye` against that known type.

Plan 1 ships the no-op default. Plan 2 Task 9 (`MixinWorldRenderingPipeline_PerEye`) mixins into concrete implementers (e.g. `DeferredWorldRenderingPipeline`) to override with the real per-eye RenderTargets-swap logic.

- [ ] **Step 1: Write `IStereoPipeline.java`**

```java
package com.mitchellmarx.stereoscopic.mixin.iris;

/**
 * Duck interface that {@link MixinWorldRenderingPipeline_SetActiveEye} attaches to Iris's
 * {@code WorldRenderingPipeline} at runtime, so stereo-aware code can cast and call
 * {@code setActiveEye(int)} against a known type at compile time. Mixin's `@Unique default`
 * adds the method at runtime but the Java compiler can't see it directly on
 * {@code WorldRenderingPipeline}; routing the call through this duck interface bridges the gap.
 *
 * <p>Plan 1 ships a no-op default. Plan 2 mixins override {@code setActiveEye} on concrete
 * pipeline implementations to do per-eye RenderTargets swap.
 */
public interface IStereoPipeline {
    default void setActiveEye(int eye) {
        // No-op default. Concrete pipelines override via Plan 2 mixins.
    }
}
```

- [ ] **Step 2: Write the mixin**

```java
package com.mitchellmarx.stereoscopic.mixin.iris;

import net.coderbot.iris.pipeline.WorldRenderingPipeline;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Adds {@link IStereoPipeline} as a parent of Iris's {@code WorldRenderingPipeline} at runtime,
 * so the {@code setActiveEye(int)} no-op default becomes part of every pipeline instance. The
 * stereo-aware code in {@code MixinEntityRenderer_Stereo} casts the pipeline to
 * {@code IStereoPipeline} and calls {@code setActiveEye} between LEFT and RIGHT passes.
 *
 * <p>Plan 2 mixins override {@code setActiveEye} on concrete pipeline implementations
 * (e.g. {@code DeferredWorldRenderingPipeline}) to do the per-eye RenderTargets swap; non-stereo
 * pipelines keep the no-op default.
 */
@Mixin(WorldRenderingPipeline.class)
public interface MixinWorldRenderingPipeline_SetActiveEye extends IStereoPipeline {
    // Inherits the no-op default from IStereoPipeline; no body needed.
}
```

- [ ] **Step 3: Compile**

```bash
./gradlew compileMixinJava
```

- [ ] **Step 4: Commit**

```bash
git -C /c/CODE/Stereoscopic-Angelica add \
    src/mixin/java/com/mitchellmarx/stereoscopic/mixin/iris/IStereoPipeline.java \
    src/mixin/java/com/mitchellmarx/stereoscopic/mixin/iris/MixinWorldRenderingPipeline_SetActiveEye.java
git -C /c/CODE/Stereoscopic-Angelica commit -m "feat(mixin): add setActiveEye default to Iris WorldRenderingPipeline (port from sbs2)"
```

**Note for Task 22:** the `MixinEntityRenderer_Stereo` port's `stereoscopic$setIrisActiveEye(int)` helper must cast the pipeline through `IStereoPipeline`:

```java
final WorldRenderingPipeline pipeline = Iris.getPipelineManager().getPipelineNullable();
if (pipeline instanceof com.mitchellmarx.stereoscopic.mixin.iris.IStereoPipeline) {
    ((com.mitchellmarx.stereoscopic.mixin.iris.IStereoPipeline) pipeline).setActiveEye(eyeIndex);
}
```

(Sbs2's verbatim code does `pipeline.setActiveEye(eyeIndex)` directly — that compiles in sbs2 because sbs2 added the method to the interface source. Our path needs the duck-interface cast.)

### Task 22i: Register the two new mixins in mixins.stereoscopic.json

**Files:**
- Modify: `C:\CODE\Stereoscopic-Angelica\src\main\resources\mixins.stereoscopic.json`

Add the two new entries to the `client` array (note: actual class is `MixinHUDCaching_Stereo`, see Task 22g):

- `"angelica.MixinHUDCaching_Stereo"`
- `"iris.MixinWorldRenderingPipeline_SetActiveEye"`

The Sodium toggle entry (`sodium.MixinSodiumGameOptionPages_StereoToggle`) is **not** added here — its class doesn't exist yet, and with `"required": true` MixinBootstrap would fatal at mod-load. It gets added in Task 23.

- [ ] **Step 1: Update the JSON**

```json
{
  "required": true,
  "minVersion": "0.8",
  "package": "com.mitchellmarx.stereoscopic.mixin",
  "compatibilityLevel": "JAVA_8",
  "refmap": "mixins.stereoscopic.refmap.json",
  "mixins": [],
  "client": [
    "angelica.MixinHUDCaching_Stereo",
    "iris.MixinWorldRenderingPipeline_SetActiveEye",
    "minecraft.MixinEntityRenderer_Stereo",
    "minecraft.MixinEntityRenderer_StereoCamera",
    "minecraft.MixinFramebuffer_Stereo"
  ]
}
```

- [ ] **Step 2: Compile (full mixin pass)**

```bash
./gradlew compileMixinJava
```

- [ ] **Step 3: Commit**

```bash
git -C /c/CODE/Stereoscopic-Angelica add src/main/resources/mixins.stereoscopic.json
git -C /c/CODE/Stereoscopic-Angelica commit -m "feat(mixin): register MixinHUDCaching + MixinWorldRenderingPipeline (Plan 1)"
```

### Task 22: MixinEntityRenderer_Stereo (two-pass world loop) — Plan 1 subset

**Files:**
- Create: `C:\CODE\Stereoscopic-Angelica\src\mixin\java\com\mitchellmarx\stereoscopic\mixin\minecraft\MixinEntityRenderer_Stereo.java`

Reference: `C:\CODE\Angelica-sbs2\src\mixin\java\com\gtnewhorizons\angelica\mixins\early\angelica\stereo\MixinEntityRenderer_Stereo.java` (347 lines). This is the largest mixin. **Port verbatim with these substitutions:**
- Package: `com.gtnewhorizons.angelica.mixins.early.angelica.stereo` → `com.mitchellmarx.stereoscopic.mixin.minecraft`
- `import com.gtnewhorizons.angelica.stereo.{StereoState, StereoMode, StereoHudMode, StereoDebugEye};` → `import com.mitchellmarx.stereoscopic.core.<Same>;`
- `import com.gtnewhorizons.angelica.stereo.{StereoCursor, CursorPresentThread};` → `import com.mitchellmarx.stereoscopic.cursor.<Same>;` (cursor package, not core — these two classes live in our `cursor/` directory, not `core/`)
- `import com.gtnewhorizons.angelica.compat.chromatictooltips.ChromaticTooltipsCompat;` → `import com.mitchellmarx.stereoscopic.compat.chromatictooltips.ChromaticTooltipsCompat;` (the stub from Task 22a, since this class was sbs2-only)
- Method-prefix rename: `angelica$` → `stereoscopic$` (cosmetic, avoids collision with Angelica's own mixin methods)
- `AngelicaConfig` references split by field — the rule depends on whether the field was ported into our `StereoConfig`:
    - **Ported fields** (`stereoscopicMode`, `stereoIpd`, `stereoHudMode`, `stereoDebugForceEye`): replace `AngelicaConfig.<field>` → `StereoConfig.<field>`. If `import com.gtnewhorizons.angelica.config.AngelicaConfig;` is *only* used for ported fields, remove the import.
    - **Non-ported fields** (e.g. `AngelicaConfig.enableIris`, Angelica's master Iris-enabled toggle): **keep as a classpath reference**, fully-qualified, no `AngelicaConfig` import. Use `com.gtnewhorizons.angelica.config.AngelicaConfig.<field>`. This matches the pattern already established in Plan 1 Task 23 (line ~1520) and the spec §"Toggle path" code block (line ~198).
- All other `com.gtnewhorizons.angelica.*` imports (e.g. `glsm.GLStateManager`, `hudcaching.HUDCaching`, `compat.chromatictooltips.ChromaticTooltipsCompat`) — **keep as-is**; they're resolved from Angelica's published jar.
- All `net.coderbot.iris.*` imports — **keep as-is**; Iris is shaded into Angelica's jar unrelocated (see spec §"Source tree").
- **HUD DUPLICATE-branch substitution.** The sbs2 file's redirect of `renderGameOverlay` has a branch for stereo + DUPLICATE that calls `HUDCaching.renderCachedHudStereo(mc.entityRenderer, ingame, partialTicks, hasScreen, mouseX, mouseY, leftX, leftY, rightX, rightY, eyeW, eyeH)`. **Substitute that one call** with the two-call pattern below — Task 22g's `MixinHUDCaching_Stereo` makes published `HUDCaching.renderCachedHud` stereo-safe, so we drive it twice with eye-specific viewport instead of porting sbs2's `renderCachedHudStereo` method:
    ```java
    // Original sbs2 line:
    //     HUDCaching.renderCachedHudStereo(mc.entityRenderer, ingame, partialTicks,
    //         hasScreen, mouseX, mouseY, leftX, leftY, rightX, rightY, eyeW, eyeH);
    // Substituted:
    GL11.glViewport(leftX, leftY, eyeW, eyeH);
    StereoState.INSTANCE.enterGuiPass(leftX, leftY, eyeW, eyeH);
    HUDCaching.renderCachedHud(mc.entityRenderer, ingame, partialTicks, hasScreen, mouseX, mouseY);
    StereoState.INSTANCE.exitGuiPass();

    GL11.glViewport(rightX, rightY, eyeW, eyeH);
    StereoState.INSTANCE.enterGuiPass(rightX, rightY, eyeW, eyeH);
    HUDCaching.renderCachedHud(mc.entityRenderer, ingame, partialTicks, hasScreen, mouseX, mouseY);
    StereoState.INSTANCE.exitGuiPass();

    GL11.glViewport(0, 0, fullW, fullH);
    ```
    The two-call pattern relies on Task 22g's `@Redirect` mixin to handle full-screen viewport for the cache fill on the first call and the once-per-frame Xaero gate. Without Task 22g landing first, this pattern is broken.

The 347 lines include: two-pass world loop wrapping `renderWorld`, eye-viewport setup, HUD/GUI duplication scaffolding (HUD details land in Plan 2), and the `RenderTickEvent.END` per-eye fanout (also Plan 2). (Note: TIMER/tickDelta freeze lives in Angelica's `shaders/MixinEntityRenderer.java`, not this file — handled by Plan 2 Task 13c.)

**Plan 1 scope for this mixin:** port the file complete. Plan 2's tasks will *edit* specific injection methods, not re-create the file.

- [ ] **Step 1: Read the sbs2 source file end-to-end**

```bash
cat /c/CODE/Angelica-sbs2/src/mixin/java/com/gtnewhorizons/angelica/mixins/early/angelica/stereo/MixinEntityRenderer_Stereo.java
```

The expected import set is:

- **Mod-internal (substitute via rule 2/3 below):** `com.gtnewhorizons.angelica.stereo.{StereoState, StereoMode, StereoHudMode, StereoDebugEye, StereoCursor, CursorPresentThread}`, `com.gtnewhorizons.angelica.config.AngelicaConfig`.
- **Angelica classpath (keep as-is — these come from the `Angelica:[2.1,2.2)` dependency):** `com.gtnewhorizons.angelica.glsm.GLStateManager`, `com.gtnewhorizons.angelica.hudcaching.HUDCaching`. (Verified by `unzip -l` against the resolved Angelica 2.1.23 jar.)
- **Mod-internal compat shim (substitute):** `com.gtnewhorizons.angelica.compat.chromatictooltips.ChromaticTooltipsCompat` → `com.mitchellmarx.stereoscopic.compat.chromatictooltips.ChromaticTooltipsCompat`. This class was added in `Angelica-sbs2` alongside the stereo work and is **not** in published Angelica; since we're extracting the sbs2 stereo modifications into this mod, the class moves here too. See **Task 22a** (below) for the verbatim port.
- **Iris classpath (keep as-is — these are shaded into Angelica's jar; see spec §"Source tree" lines 113–119 and the Plan 2 preflight that verifies the FQNs are unrelocated):** `net.coderbot.iris.Iris`, `net.coderbot.iris.pipeline.WorldRenderingPipeline`. Importing them is fine because they live on the classpath via Angelica's bundling — we are not copying any Iris source into this mod.
- **Standard:** `net.minecraft.*`, `cpw.mods.fml.*`, `net.minecraftforge.*`, `org.lwjgl.*`, `org.spongepowered.asm.*`, `java.*`.

If the source imports anything *outside* this expected set (a different mod, an unfamiliar package), STOP and reconsider — the sbs2 file should not depend on anything we haven't already accounted for here.

- [ ] **Step 2: Write the destination file applying the substitutions**

```bash
# Conceptually:
sed -e 's|package com.gtnewhorizons.angelica.mixins.early.angelica.stereo;|package com.mitchellmarx.stereoscopic.mixin.minecraft;|' \
    -e 's|import com.gtnewhorizons.angelica.stereo\.|import com.mitchellmarx.stereoscopic.core.|g' \
    -e 's|import com.gtnewhorizons.angelica.config\.|import com.mitchellmarx.stereoscopic.config.|g' \
    -e 's|AngelicaConfig\.|StereoConfig.|g' \
    -e 's|angelica\$|stereoscopic\$|g' \
    /c/CODE/Angelica-sbs2/src/mixin/java/com/gtnewhorizons/angelica/mixins/early/angelica/stereo/MixinEntityRenderer_Stereo.java \
    > /c/CODE/Stereoscopic-Angelica/src/mixin/java/com/mitchellmarx/stereoscopic/mixin/minecraft/MixinEntityRenderer_Stereo.java
```

Run this transformation explicitly with the Edit/Write tools instead of `sed` if you prefer — the substitutions are mechanical.

After the substitution, verify the import block:

```bash
grep '^import' /c/CODE/Stereoscopic-Angelica/src/mixin/java/com/mitchellmarx/stereoscopic/mixin/minecraft/MixinEntityRenderer_Stereo.java
```

Expected imports are all from `com.mitchellmarx.stereoscopic.*`, `net.minecraft.*`, `cpw.mods.fml.*`, `net.minecraftforge.*`, `org.lwjgl.*`, or `org.spongepowered.asm.*`. Zero `com.gtnewhorizons.angelica.stereo.*` or `AngelicaConfig` references remain.

- [ ] **Step 3: Compile**

```bash
./gradlew compileMixinJava
```

Expected: BUILD SUCCESSFUL. Any missing-symbol errors usually mean an `AngelicaConfig.foo` reference was missed — grep and replace.

- [ ] **Step 4: Commit**

```bash
git -C /c/CODE/Stereoscopic-Angelica add src/mixin/java/com/mitchellmarx/stereoscopic/mixin/minecraft/MixinEntityRenderer_Stereo.java
git -C /c/CODE/Stereoscopic-Angelica commit -m "feat(mixin): two-pass world loop (port MixinEntityRenderer_Stereo from sbs2)"
```

---

## Phase 5 — Sodium toggle UI

### Task 23: MixinSodiumGameOptionPages_StereoToggle

**Files:**
- Create: `C:\CODE\Stereoscopic-Angelica\src\mixin\java\com\mitchellmarx\stereoscopic\mixin\sodium\MixinSodiumGameOptionPages_StereoToggle.java`

Reference (sbs2 toggle, in-tree edit): commit `60b2f3f9` in `C:\CODE\Angelica-sbs2`, modifies `src/main/java/me/jellysquid/mods/sodium/client/gui/SodiumGameOptionPages.java`. We replicate the same `OptionGroup` insertion as an *external* `@Inject` mixin and add a second `OptionImpl` for the IPD slider.

- [ ] **Step 1: Read the sbs2 commit to confirm the exact target method**

```bash
git -C /c/CODE/Angelica-sbs2 show 60b2f3f9 -- '*SodiumGameOptionPages*'
```

Find the method into which the new `OptionGroup` was appended. In sbs2 it's the method that builds the "general" `OptionPage` and returns it. Note the exact method signature — needed for `@Inject(method=...)`.

- [ ] **Step 2: Write the file**

```java
package com.mitchellmarx.stereoscopic.mixin.sodium;

import com.google.common.collect.ImmutableList;
import com.mitchellmarx.stereoscopic.config.StereoConfig;
import com.mitchellmarx.stereoscopic.core.StereoMode;
import com.mitchellmarx.stereoscopic.core.StereoState;
import me.jellysquid.mods.sodium.client.gui.SodiumGameOptionPages;
import me.jellysquid.mods.sodium.client.gui.options.OptionGroup;
import me.jellysquid.mods.sodium.client.gui.options.OptionImpact;
import me.jellysquid.mods.sodium.client.gui.options.OptionImpl;
import me.jellysquid.mods.sodium.client.gui.options.OptionPage;
import me.jellysquid.mods.sodium.client.gui.options.control.SliderControl;
import me.jellysquid.mods.sodium.client.gui.options.control.TickBoxControl;
import net.coderbot.iris.Iris;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * External mixin into Angelica-bundled Sodium's options-page builder. Appends a "Stereoscopic"
 * OptionGroup with two entries:
 *   1. Tick-box — SBS on/off (port of sbs2 commit 60b2f3f9)
 *   2. Integer slider — eye separation in mm (new in this mod; matches the existing Fabric port's UX)
 *
 * The exact target method signature is verified against the sbs2 commit before writing this file.
 * If Angelica's bundled Sodium ever renames or restructures that method, the @Inject target string
 * below is the single point of breakage.
 */
@Mixin(SodiumGameOptionPages.class)
public abstract class MixinSodiumGameOptionPages_StereoToggle {

    // Replace <BUILDER_METHOD> with the actual method name from sbs2 (see Step 1 above).
    // The sbs2 patch injects right before "return new OptionPage(...)"; we do the same via @At RETURN
    // with a CallbackInfoReturnable<OptionPage> so we can mutate the returned page's groups.
    @Inject(method = "<BUILDER_METHOD>", at = @At("RETURN"), cancellable = true, remap = false)
    private static void stereoscopic$appendStereoGroup(CallbackInfoReturnable<OptionPage> cir) {
        final OptionPage original = cir.getReturnValue();
        if (original == null) return;

        // Pull existing groups, append our group, return a new OptionPage with the merged list.
        // (OptionPage.getGroups() returns ImmutableList; we use a fresh ArrayList to mutate.)
        final List<OptionGroup> merged = new ArrayList<>(original.getGroups());

        final Object angelicaOpts = null; // sbs2 used 'angelicaOpts'; passing null is fine — the
                                          // binding lambdas don't read the storage parameter.

        merged.add(OptionGroup.createBuilder()
            // 1. Tick-box (sbs2 60b2f3f9, verbatim toggle semantics)
            .add(OptionImpl.createBuilder(boolean.class, angelicaOpts)
                .setName("Stereoscopic SBS (Side-by-Side)")
                .setTooltip("Splits the screen into per-eye images for 3D TVs, 3D monitors, and SBS-compatible displays. Roughly halves framerate.")
                .setControl(TickBoxControl::new)
                .setBinding(
                    (opts, value) -> {
                        final StereoMode newMode = value ? StereoMode.SBS_HALF : StereoMode.OFF;
                        if (StereoConfig.stereoscopicMode == newMode) return;
                        StereoConfig.stereoscopicMode = newMode;
                        StereoConfig.save();
                        StereoState.INSTANCE.beginFrame();
                        if (com.gtnewhorizons.angelica.config.AngelicaConfig.enableIris && Minecraft.getMinecraft().theWorld != null) {
                            Iris.getPipelineManager().destroyPipeline();
                            Iris.getPipelineManager().preparePipeline(Iris.getCurrentDimensionName());
                            Minecraft.getMinecraft().renderGlobal.loadRenderers();
                        }
                    },
                    opts -> StereoConfig.stereoscopicMode != null
                        && StereoConfig.stereoscopicMode != StereoMode.OFF)
                .setImpact(OptionImpact.HIGH)
                .build())
            // 2. IPD slider (new — matches existing Fabric mod's UX)
            .add(OptionImpl.createBuilder(int.class, angelicaOpts)
                .setName("Eye separation (IPD)")
                .setTooltip("Distance between the two eye cameras. 64 mm matches average human IPD.")
                .setControl(opt -> new SliderControl(opt, 0, 500, 1, mm -> String.format("%.3f m", mm / 1000.0)))
                .setBinding(
                    (opts, mm) -> {
                        StereoConfig.stereoIpd = Math.max(0f, Math.min(0.5f, mm / 1000f));
                        StereoConfig.save();
                    },
                    opts -> Math.round(StereoConfig.stereoIpd * 1000f))
                .setImpact(OptionImpact.LOW)
                .build())
            .build());

        cir.setReturnValue(new OptionPage(original.getName(), ImmutableList.copyOf(merged)));
    }
}
```

- [ ] **Step 3: Resolve `<BUILDER_METHOD>` to the real name**

The placeholder `<BUILDER_METHOD>` must be replaced with the actual method that builds Sodium's "general" `OptionPage`. From `60b2f3f9` and the sbs2 source, this is the method that returns `new OptionPage(I18n.format("stat.generalButton"), ...)`. Inspect:

```bash
grep -n "OptionPage" /c/CODE/Angelica-sbs2/src/main/java/me/jellysquid/mods/sodium/client/gui/SodiumGameOptionPages.java | head -20
```

Identify the method enclosing the `return new OptionPage(I18n.format("stat.generalButton"), ...)` and substitute its name. If the file uses `static OptionPage general()` as the signature, the injection target is `method = "general"`.

- [ ] **Step 4: Verify SliderControl constructor signature**

The `SliderControl(opt, min, max, step, formatter)` arity used above matches sbs2's bundled Sodium. If `compileMixinJava` reports a mismatch:

```bash
javap -classpath build/.../angelica.jar me.jellysquid.mods.sodium.client.gui.options.control.SliderControl
```

Adapt the constructor call to the actual signature. **Do not invent a wrapper** — just match the real signature.

- [ ] **Step 5: Append the mixin entry to the JSON config**

Edit `src/main/resources/mixins.stereoscopic.json` and add `"sodium.MixinSodiumGameOptionPages_StereoToggle"` to the `client` array (keep the existing entries; preserve alphabetical order). This is the registration that was intentionally deferred from Task 10 — see Task 10 and Task 22i for the rationale (`"required": true` + missing class = fatal at mod-load).

The final JSON should look like:

```json
{
  "required": true,
  "minVersion": "0.8",
  "package": "com.mitchellmarx.stereoscopic.mixin",
  "compatibilityLevel": "JAVA_8",
  "refmap": "mixins.stereoscopic.refmap.json",
  "mixins": [],
  "client": [
    "angelica.MixinHUDCaching_Stereo",
    "iris.MixinWorldRenderingPipeline_SetActiveEye",
    "minecraft.MixinEntityRenderer_Stereo",
    "minecraft.MixinEntityRenderer_StereoCamera",
    "minecraft.MixinFramebuffer_Stereo",
    "sodium.MixinSodiumGameOptionPages_StereoToggle"
  ]
}
```

- [ ] **Step 6: Compile**

```bash
./gradlew compileMixinJava
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git -C /c/CODE/Stereoscopic-Angelica add src/mixin/java/com/mitchellmarx/stereoscopic/mixin/sodium/MixinSodiumGameOptionPages_StereoToggle.java src/main/resources/mixins.stereoscopic.json
git -C /c/CODE/Stereoscopic-Angelica commit -m "feat(mixin): Sodium options — SBS tick-box + IPD slider; pipeline rebuild on toggle"
```

---

## Phase 5b — ISBRH Tessellator-abuse stripping

**Why this phase exists:** Plans 1–4 originally missed enumerating sbs2's `loading/fml/transformers/IsbrhTessellatorAbuseClassTransformer.java` (+93 NEW lines in sbs2) and `loading/rfb/transformers/RFBIsbrhTessellatorAbuseTransformer.java` (+54 NEW lines in sbs2). Mod block-renderers (`cpw.mods.fml.client.registry.ISimpleBlockRenderingHandler` impls — vines, leaves, etc.) that call `Tessellator.startDrawing()` / `startDrawingQuads()` / `draw()` inside `renderWorldBlock(...)` flush the chunk renderer's batch mid-frame at the wrong GL state. Sbs2 strips those calls; we port the same pair.

This phase belongs in Plan 1 because the transformer pair is part of Plan 1's scope (NEW files in the sbs2 diff under `loading/`). It is **necessary but NOT sufficient** to make the world visibly split per-eye — the chunk render path also routes through Iris's `FixedFunctionWorldRenderingPipeline` (loaded even with no shaderpack) and Embeddium/Celeritas, which is what **Plan 2 Phase 3** (per-eye Iris RenderTargets) handles. Do not interpret a shipped Phase 5b as "Plan 1's Phase 6 smoke test should now pass" — see Phase 6 Task 26 for the corrected exit criteria.

**Build-config prerequisite (shipped at commit `cd7ae71`):** `build.gradle.kts` must expand `${version}` in `META-INF/rfb-plugin/*.properties` (gtnhconvention's default `processResources` substitutes `mcmod.info` but not rfb-plugin descriptors). Without this, RFB rejects `StereoscopicRfbPlugin` at load time and the RFB-side transformer (Task 23d) never registers — Phase 5b is incomplete without this fix.

### Task 23b: Become a coremod (IFMLLoadingPlugin) — **ALREADY SHIPPED** at commit `8321f8c`

- Create: `C:\CODE\Stereoscopic-Angelica\src\main\java\com\mitchellmarx\stereoscopic\loading\StereoscopicCoreMod.java` ✅
- Add: `coreModClass = loading.StereoscopicCoreMod` to `gradle.properties` so gtnhconvention emits `FMLCorePlugin:` manifest entry ✅
- Register: `IsbrhTessellatorAbuseClassTransformer` (Task 23c) via `getASMTransformerClass()` ✅

Plugin captures `runtimeDeobfuscationEnabled` in `injectData(...)` and exposes it as `public static boolean OBF_ENV` for the transformer's no-arg constructor.

### Task 23c: Port IsbrhTessellatorAbuseClassTransformer (FML half) — **ALREADY SHIPPED** at commit `8321f8c`

**Reference source:** `C:\CODE\Angelica-sbs2\src\main\java\com\gtnewhorizons\angelica\loading\fml\transformers\IsbrhTessellatorAbuseClassTransformer.java` (93 lines, NEW).

- Create: `C:\CODE\Stereoscopic-Angelica\src\main\java\com\mitchellmarx\stereoscopic\transformer\IsbrhTessellatorAbuseClassTransformer.java` ✅
- Verbatim port with package change + drop `AngelicaClientTweaker.isObfEnv()` (replaced by `StereoscopicCoreMod.OBF_ENV`) + drop `AngelicaClassDump.dumpClass(...)` call.
- Scans every class implementing `cpw/mods/fml/client/registry/ISimpleBlockRenderingHandler`; strips `Tessellator.startDrawing(I)V`, `startDrawingQuads()V`, `draw()I` calls inside `renderWorldBlock(...)` with stack-balanced replacements (POP / POP2 / POP+ICONST_0).
- Logs each strip at INFO via logger `StereoscopicIsbrhTransformer`.

### Task 23d: Port RFBIsbrhTessellatorAbuseTransformer (RFB half) — **TO DO**

**Reference source:** `C:\CODE\Angelica-sbs2\src\main\java\com\gtnewhorizons\angelica\loading\rfb\transformers\RFBIsbrhTessellatorAbuseTransformer.java` (54 lines, NEW).

**Why needed in addition to the FML transformer:** sbs2 ships BOTH because RFB (RetroFuturaBootstrap) loads classes through its OWN classloader path (used in the lwjgl3ify + modern-JVM environment GTNH-daily uses), separately from FML's legacy `LaunchClassLoader`. Different classes load via different paths depending on when in the boot sequence they're first referenced. The FML transformer only catches LaunchClassLoader-loaded classes; the RFB transformer covers RFB's loader.

**Files (after investigation completes):**
- Create: `C:\CODE\Stereoscopic-Angelica\src\main\java\com\mitchellmarx\stereoscopic\transformer\RFBIsbrhTessellatorAbuseTransformer.java`
- Likely modify: `C:\CODE\Stereoscopic-Angelica\src\main\resources\META-INF\services\com.gtnewhorizons.retrofuturabootstrap.api.IRfbTransformerPlugin` (or equivalent registration point — verify against sbs2's `loading/rfb/AngelicaRfbPlugin.java`)
- Modify: jar manifest to declare the RFB plugin if required.

**Steps (placeholders pending investigator findings — see in-flight investigation `a6a18a42fcf95297e`):**
- [ ] Read sbs2's `AngelicaRfbPlugin.java` and `RFBIsbrhTessellatorAbuseTransformer.java` in full.
- [ ] Determine the registration mechanism (likely `IRfbTransformerPlugin` via ServiceLoader META-INF/services file).
- [ ] Port both files into our mod with package rename.
- [ ] Wire the registration.
- [ ] Build, verify the deployed jar contains the new class + registration, confirm in-game log shows the transformer firing on ISBRH classes (look for `Stripped Tessellator.` lines).

### Task 23e: Verify the transformer pair actually fires

After Task 23d ships:
- Launch GTNH profile, search `fml-client-latest.log` for `Stripped Tessellator.` — expect dozens of hits across mod blocks.
- If zero hits, the transformer pair isn't applying. Debug before claiming Phase 5b complete.

---

## Phase 5c — Code-quality fixes from earlier reviews (moved back into Plan 1)

**Why this phase exists:** items below were flagged during Plan 1 Phase 3 + Phase 4 code reviews and previously deferred to "Plan 2 follow-ups". They touch Plan 1 files (HUDCaching mixin, StereoState, EntityRenderer_Stereo, etc.) so they belong in Plan 1 regardless of Task 26's gating. Six tasks, ordered by severity.

### Task 23f: Frame-counter token for Xaero gate in MixinHUDCaching_Stereo (**real correctness bug**)

**File:** `src/mixin/java/com/mitchellmarx/stereoscopic/mixin/angelica/MixinHUDCaching_Stereo.java` line 61, and `src/main/java/com/mitchellmarx/stereoscopic/core/StereoState.java`

The Xaero pre-render hook is gated to once-per-frame using `Minecraft.getMinecraft().getSystemTime()` (milliseconds) as the token. At >1 KHz frame rate two `renderCachedHud` calls can land in the same millisecond (correct skip) or 1ms+ apart in the same frame (incorrect fire-twice). Use a frame counter that's guaranteed monotonic per frame.

- [ ] Step 1: Add to `StereoState.java`:
  - `private long frameSequence = 0;`
  - increment in `beginFrame()` (after the `active = false` early-return and after `active = true`, both paths — every `beginFrame` call advances the counter)
  - `public long getFrameSequence() { return frameSequence; }`
- [ ] Step 2: In `MixinHUDCaching_Stereo`, replace `Minecraft.getMinecraft().getSystemTime()` with `StereoState.INSTANCE.getFrameSequence()` and update the comparison logic accordingly. The token field type changes from `long` (ms) to `long` (frame seq) — no API break.
- [ ] Step 3: `./gradlew compileMixinJava && ./gradlew test` — expect green.
- [ ] Step 4: Commit `fix(mixin): frame-counter token for Xaero gate (was getSystemTime, races at high fps)`.

### Task 23g: `StereoConfig.save()` pre-load warning

**File:** `src/main/java/com/mitchellmarx/stereoscopic/config/StereoConfig.java` line 56–57

Currently `save()` returns silently if `config == null` (i.e., called before `load()`). When the Sodium UI binding lambdas call `save()`, a race during mod init could drop a user setting silently. Log a warning instead.

- [ ] Step 1: Add `import com.mitchellmarx.stereoscopic.Stereoscopic;` (or whatever the project's logger is — check existing imports).
- [ ] Step 2: Change `if (config == null) return;` to `if (config == null) { Stereoscopic.LOG.warn("StereoConfig.save() called before load() — setting not persisted"); return; }`.
- [ ] Step 3: Test + commit.

### Task 23h: `StereoConfig.load()` corruption robustness

**File:** `src/main/java/com/mitchellmarx/stereoscopic/config/StereoConfig.java` lines 36–44

Current `try`/`finally` will run `config.hasChanged()` against a half-initialized `Configuration` if `config.load()` throws on a corrupted file. Wrap the inner `config.load()`/`read()` calls in a try/catch that logs and falls back to in-memory defaults.

- [ ] Step 1: Restructure:
  ```java
  public static void load(File file) {
      config = new Configuration(file);
      try {
          config.load();
          read();
      } catch (Throwable t) {
          Stereoscopic.LOG.warn("Failed to load stereoscopic config; using in-memory defaults", t);
      }
      try {
          if (config.hasChanged()) config.save();
      } catch (Throwable t) {
          Stereoscopic.LOG.warn("Failed to write stereoscopic config", t);
      }
  }
  ```
- [ ] Step 2: Test + commit.

### Task 23i: `StereoConfigTest` persistence round-trip coverage

**File:** `src/test/java/com/mitchellmarx/stereoscopic/config/StereoConfigTest.java`

The current test only asserts defaults on in-memory statics — it would pass even if `load()`/`save()`/`read()`/`parseEnum()` were deleted. Add a `@TempDir`-based test that writes a file, calls `load()`, mutates `stereoscopicMode` + `stereoIpd`, calls `save()`, then re-`load()`s and asserts the values survived.

- [ ] Step 1: Write the test using JUnit 5 `@TempDir`.
- [ ] Step 2: `./gradlew test` — expect 13/13 pass.
- [ ] Step 3: Commit.

### Task 23j: Restore inline rationale comments in MixinEntityRenderer_StereoCamera

**File:** `src/mixin/java/com/mitchellmarx/stereoscopic/mixin/minecraft/MixinEntityRenderer_StereoCamera.java`

sbs2's source has inline rationale comments — the 0.07/0.064 ratio explainer (where `1.09f` comes from in the disabled projection-offset code) and the anchor-on-`hurtCameraEffect` Javadoc explaining why that's the injection point. Restore them.

- [ ] Step 1: `git -C /c/CODE/Angelica-sbs2 show stereo-sbs-2:src/mixin/java/com/gtnewhorizons/angelica/mixins/early/angelica/stereo/MixinEntityRenderer_StereoCamera.java` — copy the comments verbatim.
- [ ] Step 2: Apply to our file with package adjustments.
- [ ] Step 3: Commit.

### Task 23k: Verify `CursorPresentThread.invokeBoolUnused` dead-code status

**File:** `src/main/java/com/mitchellmarx/stereoscopic/cursor/CursorPresentThread.java` line 1042

The helper looks unused. Verify against sbs2.

- [ ] Step 1: `grep -nE 'invokeBoolUnused' /c/CODE/Angelica-sbs2/src/main/java/com/gtnewhorizons/angelica/stereo/CursorPresentThread.java` — count call sites.
- [ ] Step 2: If sbs2 also has it as orphaned dead code: leave ours alone (verbatim port rule). If sbs2 USES it but our port lost the call site: re-port the missing usage. If sbs2 doesn't have it at all: delete ours.
- [ ] Step 3: Commit only if a change was made.

---

## Phase 6 — Full build + manual smoke test

### Task 24: Build the jar end-to-end

**Files:** none (verification)

- [ ] **Step 1: Run full build**

```bash
cd /c/CODE/Stereoscopic-Angelica
./gradlew clean build
```

Expected: BUILD SUCCESSFUL. Output at `build/libs/stereoscopic-<version>.jar`.

If `copyToTestInstance` runs and reports "Skipping" (Prism path missing), that's fine on CI/non-dev machines. On Mitchell's box it should copy and pre-delete stale jars.

- [ ] **Step 2: Verify the jar shape**

```bash
unzip -l /c/CODE/Stereoscopic-Angelica/build/libs/stereoscopic-*.jar | head -40
```

Expected entries:
- `mcmod.info`
- `pack.mcmeta`
- `mixins.stereoscopic.json`
- `mixins.stereoscopic.refmap.json` (generated by RFG mixin AP)
- `com/mitchellmarx/stereoscopic/Stereoscopic.class`
- `com/mitchellmarx/stereoscopic/config/StereoConfig.class`
- `com/mitchellmarx/stereoscopic/core/StereoMode.class`, `StereoState.class`, etc.
- `com/mitchellmarx/stereoscopic/mixin/minecraft/MixinEntityRenderer_Stereo.class`, etc.

### Task 25: Pre-flight verification of Angelica jar shape

**Files:** none (verification — guards against the relocation risk flagged in spec)

- [ ] **Step 1: Locate the resolved Angelica jar in Gradle cache**

```bash
find ~/.gradle/caches/modules-2/files-2.1/com.github.GTNewHorizons/Angelica -name "*.jar" -not -name "*-sources*" | head -5
```

- [ ] **Step 2: Grep for unrelocated FQNs**

```bash
ANGELICA_JAR=$(find ~/.gradle/caches/modules-2/files-2.1/com.github.GTNewHorizons/Angelica -name "*.jar" -not -name "*-sources*" | head -1)
unzip -l "$ANGELICA_JAR" | grep -E '(net/coderbot/iris/pipeline/HandRenderer|me/jellysquid/mods/sodium/client/gui/SodiumGameOptionPages)'
```

Expected: BOTH classes listed in the jar.
- If found → continue.
- If missing → STOP. Angelica's release pipeline has relocated the shaded classes; mixin targets must be updated to the relocated FQNs, and the spec's Angelica-relocation risk has materialized.

### Task 26: Deploy to Prism profile and manually smoke-test

**Files:** none — this is the Prism handoff.

**Scope note:** Task 26 verifies **only what Plan 1 actually delivers**. The chunk-render-path world split was originally listed here but is gated by Plan 2 Phase 3 (per-eye Iris RenderTargets) — see "What is deliberately NOT verified here" below.

- [ ] **Step 1: Deploy**

```bash
./gradlew build
```

The `copyToTestInstance` task runs as part of `build` and lands the jar in the Prism mods dir.

- [ ] **Step 2: Launch the GTNH profile and verify**

In Prism: launch the GTNH-daily profile. The mod should:
1. Load without crash (check logs for `[StereoCraft: Stereoscopic 3D]` mod-init line).
2. Discover the RFB plugin: grep `logs/fml-junk-earlystartup.log` for `Constructed RFB plugin stereoscopic@<version>` — must be a parseable version string, not the literal `${version}`.
3. Add a "Stereoscopic SBS" group to Sodium's video options page (Pause → Options → Video Settings → scroll to bottom).
4. With the tick-box OFF, gameplay is identical to a no-mod baseline.
5. Ticking the box ON: **sky and HUD split L/R** (each half shows the sky and a duplicated HUD overlay). The world geometry will NOT visibly split — that's gated by Plan 2 (see below).
6. Adjusting the IPD slider visibly changes per-eye geometry of whatever IS rendering per-eye (sky / HUD anchor positions).
7. Ticking the box OFF → returns to full-screen mono.

Log the results to `docs/superpowers/notes/v0.1.0-manual-test.md` (create if missing).

**What is deliberately NOT verified here (gated by Plan 2 Phase 3):**

- **World chunk geometry splitting per-eye.** With no shaderpack loaded, Iris still loads and runs `FixedFunctionWorldRenderingPipeline.beginLevelRendering`, which rebinds the main framebuffer and sets viewport to full-screen mid-`renderWorld`. Embeddium/Celeritas and Distant Horizons also set viewport to full-screen during chunk rendering. The Plan 1 mechanism (`MixinEntityRenderer_Stereo` two-pass loop + eye-region scissor + `MixinFramebuffer_Stereo` viewport-restore) cannot keep chunks clipped to the eye's half — sbs2's design routes chunk geometry through Iris's per-eye RenderTargets FBO chain (see `RenderTargets.java` +525 lines, `DeferredWorldRenderingPipeline.java` +76 lines, plus the uniform/sampler/composite/final-pass mixins in commit `fa3a63f4`). Plan 2 Phase 3 Tasks 4–11 port that machinery. Until Plan 2 Phase 3 lands, this sub-rubric is not closeable.
- **Per-eye hand depth.** Plan 2 Phase 2.
- **Achievement popup per-eye.** Plan 2 Phase 4.
- **Iris shaderpack stereo rendering.** Plan 2 Phase 3 (separate from the no-shaderpack chunk path but same mixins).

---

## Self-Review

Before declaring Plan 1 done, scan for:

**Coverage vs spec section "Goals":**
- [x] Goal A — core two-pass world render — Tasks 20, 22.
- [x] Goal F (toggle part only) — Sodium tick-box, pipeline rebuild — Task 23.
- Goal F (perf part) — deferred to Plan 4.
- Goals B, C, D, E, G, H, I, J, K, L, M — deferred to Plans 2/3/4.

**Type/method consistency:** `StereoState.beginFrame()` returns `boolean` (Task 18). `StereoConfig.save()` returns `void` (Task 14). `StereoMode.isActive()` returns `boolean` (Task 11). The toggle path in Task 23 calls all three with these signatures — consistent.

**Placeholders:** `<BUILDER_METHOD>` in Task 23 is the only intentional placeholder, because the actual method name is verified at execution time from the sbs2 source. Task 23 Step 3 documents how to resolve it. This is not a "TBD" — it's an exact instruction for the executor.

**Out-of-scope creep:** `StereoState` mentions Iris-facing helpers (`irisFbWidth/Height`). They're ported but inert in Plan 1; Plan 2 wires them through. Acceptable — porting the full class verbatim is the agreed approach.

---

**End of Plan 1.** Plan 2 picks up with HUD/GUI duplication and Iris integration.

**Carry-forward to Plan 2 exit criteria:** the world-chunk-render-splits-per-eye smoke-test rubric (originally Task 26 Step 2 item 4) moves to Plan 2's Phase 3 / Phase 6 verification. Plan 2 isn't closeable until that visibly works in Prism with no shaderpack AND with a shaderpack loaded.
