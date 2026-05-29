# Plan 1 — Scaffold + Core Stereo + Sodium Toggle

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce a working Forge 1.7.10 mod that depends on Angelica, splits the screen into LEFT/RIGHT eye halves when the Sodium video-options tick-box is enabled, and shifts the world camera per eye. No Iris support, no per-eye HUD work, no async cursor, no Sodium perf skips — those land in Plans 2/3/4.

**Architecture:** RetroFuturaGradle via `gtnhsettingsconvention`. Forge 1.7.10. Depends on Angelica `[2.1,2.2)`. Stereo state in a singleton, per-frame snapshot of `StereoConfig`. Mixins into Minecraft's `EntityRenderer` (camera + two-pass world loop) and into Angelica-bundled Sodium's `SodiumGameOptionPages` (tick-box + IPD slider). External mixin only — Angelica's source is not touched.

**Tech Stack:** Java with `jvmDowngrader` (J21 source → J8/J17/J21/J25 multi-release jar), MixinBootstrap via UniMixins, Forge `Configuration` for persistence, JUnit 5 for unit tests.

**Reference port source:** `C:\CODE\Angelica-sbs2` branch `stereo-sbs-2`. The plan instructs verbatim file ports with package + `AngelicaConfig` → `StereoConfig` substitutions. Read the sbs2 source file referenced in each task before writing the target.

**Conventions used by this plan:**
- "Run: `<cmd>`" — execute exactly. `Expected: <text>` describes successful output.
- "Source:" and "Destination:" paths are absolute (Windows + Unix-style both acceptable to the file tools).
- "Verbatim body" = copy line-for-line except for the substitutions called out in the task.

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
| `C:\CODE\Stereoscopic-Angelica\src\main\java\com\mitchellmarx\stereoscopic\Stereoscopic.java` | `@Mod` entrypoint — registers config, registers GLSM bridge stub |
| `C:\CODE\Stereoscopic-Angelica\src\main\java\com\mitchellmarx\stereoscopic\config\StereoConfig.java` | Forge `Configuration` — 4 fields (mode, ipd, hud, debug) |
| `C:\CODE\Stereoscopic-Angelica\src\main\java\com\mitchellmarx\stereoscopic\core\StereoMode.java` | Enum: OFF, SBS_HALF, SBS_FULL, OU_HALF, OU_FULL |
| `C:\CODE\Stereoscopic-Angelica\src\main\java\com\mitchellmarx\stereoscopic\core\StereoHudMode.java` | Enum: DUPLICATE, STRETCH, HIDE |
| `C:\CODE\Stereoscopic-Angelica\src\main\java\com\mitchellmarx\stereoscopic\core\StereoDebugEye.java` | Enum: OFF, LEFT, RIGHT |
| `C:\CODE\Stereoscopic-Angelica\src\main\java\com\mitchellmarx\stereoscopic\core\StereoState.java` | Singleton — frame snapshot + per-eye state machine |
| `C:\CODE\Stereoscopic-Angelica\src\main\java\com\mitchellmarx\stereoscopic\core\StereoGLSMBridge.java` | Wires scissor + viewport remap into Angelica's `GLSMHooks.stereoHook` |
| `C:\CODE\Stereoscopic-Angelica\src\main\java\com\mitchellmarx\stereoscopic\cursor\CursorPresentThread.java` | **Plan 1 stub** — `start()`/`stop()`/`ensureStarted()` no-ops. Plan 3 replaces with real impl. |
| `C:\CODE\Stereoscopic-Angelica\src\main\java\com\mitchellmarx\stereoscopic\cursor\StereoCursor.java` | **Plan 1 stub** — returns vanilla `Mouse.getX()` / `getY()`. Plan 3 replaces with real impl. |
| `C:\CODE\Stereoscopic-Angelica\src\main\resources\mcmod.info` | Forge mod metadata |
| `C:\CODE\Stereoscopic-Angelica\src\main\resources\mixins.stereoscopic.json` | Mixin config — Plan 1 entries only |
| `C:\CODE\Stereoscopic-Angelica\src\main\resources\pack.mcmeta` | Resource pack metadata |
| `C:\CODE\Stereoscopic-Angelica\src\mixin\java\com\mitchellmarx\stereoscopic\mixin\minecraft\MixinEntityRenderer_Stereo.java` | Two-pass world render loop |
| `C:\CODE\Stereoscopic-Angelica\src\mixin\java\com\mitchellmarx\stereoscopic\mixin\minecraft\MixinEntityRenderer_StereoCamera.java` | Per-eye projection/modelview offset |
| `C:\CODE\Stereoscopic-Angelica\src\mixin\java\com\mitchellmarx\stereoscopic\mixin\minecraft\MixinFramebuffer_Stereo.java` | Restore eye viewport after Iris framebuffer rebind |
| `C:\CODE\Stereoscopic-Angelica\src\mixin\java\com\mitchellmarx\stereoscopic\mixin\sodium\MixinSodiumGameOptionPages_StereoToggle.java` | Tick-box + IPD slider, pipeline rebuild on toggle |
| `C:\CODE\Stereoscopic-Angelica\src\test\java\com\mitchellmarx\stereoscopic\core\StereoStateTest.java` | Sign convention, isActive, beginFrame snapshot |
| `C:\CODE\Stereoscopic-Angelica\src\test\java\com\mitchellmarx\stereoscopic\core\StereoModeTest.java` | `valueOf()` round-trip |

**Modified in this plan:** none. The repo starts with only the design spec.

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
// All the real configuration lives in gradle.properties + dependencies.gradle.
// gtnhsettingsconvention applies RetroFuturaGradle and wires everything from there.
```

That single comment is the entire file — `gtnhsettingsconvention` requires `build.gradle.kts` to exist but configures itself from `gradle.properties`. A deploy task is added in Task 7.

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
    "version": "${version}",
    "mcversion": "${mcversion}",
    "url": "",
    "authorList": ["Mitchell Samora"],
    "credits": "Ported from the stereo SBS feature on Angelica's stereo-sbs-2 branch.",
    "dependencies": ["angelica", "gtnhlib"]
  }
]
```

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
    "minecraft.MixinFramebuffer_Stereo",
    "sodium.MixinSodiumGameOptionPages_StereoToggle"
  ]
}
```

Plans 2/3/4 will append additional entries here as they introduce new mixin classes.

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
 * Replaces the AngelicaConfig fields that sbs2 reads from for stereo state. Persisted to
 * {@code config/stereoscopic.cfg} via Forge's {@link Configuration} class.
 *
 * <p>User-facing fields (exposed in the Sodium video-options page):
 * <ul>
 *   <li>{@link #stereoscopicMode} — tick-box (OFF ↔ SBS_HALF)
 *   <li>{@link #stereoIpd} — integer slider in mm, persisted as meters
 * </ul>
 *
 * <p>Advanced / debug fields (hand-edit only; no UI):
 * <ul>
 *   <li>{@link #stereoHudMode} — default DUPLICATE
 *   <li>{@link #stereoDebugForceEye} — default OFF
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

        final String hudName = config.getString(
            "stereoHudMode", "general", StereoHudMode.DUPLICATE.name(),
            "HUD render mode. Values: DUPLICATE, STRETCH, HIDE. Default DUPLICATE.");
        stereoHudMode = parseEnum(StereoHudMode.class, hudName, StereoHudMode.DUPLICATE);

        final String debugName = config.getString(
            "stereoDebugForceEye", "debug", StereoDebugEye.OFF.name(),
            "Debug-only override to force one-eye rendering. Values: OFF, LEFT, RIGHT.");
        stereoDebugForceEye = parseEnum(StereoDebugEye.class, debugName, StereoDebugEye.OFF);
    }

    public static void save() {
        if (config == null) return;
        config.get("general", "stereoscopicMode", StereoMode.OFF.name()).set(stereoscopicMode.name());
        config.get("general", "stereoIpd", 0.064).set((double) stereoIpd);
        config.get("general", "stereoHudMode", StereoHudMode.DUPLICATE.name()).set(stereoHudMode.name());
        config.get("debug",   "stereoDebugForceEye", StereoDebugEye.OFF.name()).set(stereoDebugForceEye.name());
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

`StereoState` (next task) calls `CursorPresentThread.ensureStarted()` / `.stop()` and `StereoGLSMBridge` reads from `StereoCursor.getX()` etc. Provide no-op stubs in Plan 1 so the core code compiles. Plan 3 replaces both files with the real Windows implementation.

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

### Task 19: StereoGLSMBridge (port from sbs2)

**Files:**
- Create: `C:\CODE\Stereoscopic-Angelica\src\main\java\com\mitchellmarx\stereoscopic\core\StereoGLSMBridge.java`

Reference: `C:\CODE\Angelica-sbs2\src\main\java\com\gtnewhorizons\angelica\stereo\StereoGLSMBridge.java` (67 lines). Port verbatim with package + import substitutions. Imports of Angelica's GLSM stay (`com.gtnewhorizons.angelica.glsm.hooks.GLSMHooks` / `StereoHook`) — we depend on Angelica.

- [ ] **Step 1: Write the file**

```java
package com.mitchellmarx.stereoscopic.core;

import com.gtnewhorizons.angelica.glsm.hooks.GLSMHooks;
import com.gtnewhorizons.angelica.glsm.hooks.StereoHook;
import com.mitchellmarx.stereoscopic.cursor.StereoCursor;
import net.minecraft.client.Minecraft;
import org.lwjgl.input.Mouse;

public final class StereoGLSMBridge {

    private StereoGLSMBridge() {}

    public static void register() {
        GLSMHooks.stereoHook = new StereoHook() {
            @Override
            public boolean remapScissor(int x, int y, int width, int height, int[] out) {
                final StereoState state = StereoState.INSTANCE;
                if (!state.isInGuiPass()) return false;
                final Minecraft mc = Minecraft.getMinecraft();
                final int displayW = mc.displayWidth;
                final int displayH = mc.displayHeight;
                if (displayW <= 0 || displayH <= 0) return false;
                final int vpX = state.getEyeVpX();
                final int vpY = state.getEyeVpY();
                final int vpW = state.getEyeVpW();
                final int vpH = state.getEyeVpH();
                out[0] = vpX + (int)((long) x * vpW / displayW);
                out[1] = vpY + (int)((long) y * vpH / displayH);
                out[2] = (int)((long) width  * vpW / displayW);
                out[3] = (int)((long) height * vpH / displayH);
                return true;
            }

            @Override
            public boolean remapWorldPassViewport(int x, int y, int width, int height, int[] out) {
                final StereoState state = StereoState.INSTANCE;
                if (!state.isInWorldPass()) return false;
                final Minecraft mc = Minecraft.getMinecraft();
                if (mc == null) return false;
                if (x != 0 || y != 0 || width != mc.displayWidth || height != mc.displayHeight) return false;
                out[0] = 0;
                out[1] = 0;
                out[2] = state.irisFbWidth(mc.displayWidth);
                out[3] = state.irisFbHeight(mc.displayHeight);
                return true;
            }

            @Override
            public boolean remapGuiPassViewport(int x, int y, int width, int height, int[] out) {
                final StereoState state = StereoState.INSTANCE;
                if (!state.isInGuiPass()) return false;
                final Minecraft mc = Minecraft.getMinecraft();
                if (mc == null) return false;
                if (x != 0 || y != 0 || width != mc.displayWidth || height != mc.displayHeight) return false;
                out[0] = state.getEyeVpX();
                out[1] = state.getEyeVpY();
                out[2] = state.getEyeVpW();
                out[3] = state.getEyeVpH();
                return true;
            }

            @Override public int stereoMouseGetX()      { return StereoState.INSTANCE.isActive() ? StereoCursor.getX()      : Mouse.getX(); }
            @Override public int stereoMouseGetY()      { return StereoState.INSTANCE.isActive() ? StereoCursor.getY()      : Mouse.getY(); }
            @Override public int stereoMouseGetEventX() { return StereoState.INSTANCE.isActive() ? StereoCursor.getEventX() : Mouse.getEventX(); }
            @Override public int stereoMouseGetEventY() { return StereoState.INSTANCE.isActive() ? StereoCursor.getEventY() : Mouse.getEventY(); }
        };
    }
}
```

- [ ] **Step 2: Wire `register()` from the @Mod entrypoint**

Modify `Stereoscopic.java`'s `preInit`:

```java
@Mod.EventHandler
public void preInit(FMLPreInitializationEvent event) {
    StereoConfig.load(event.getSuggestedConfigurationFile());
    com.mitchellmarx.stereoscopic.core.StereoGLSMBridge.register();
}
```

- [ ] **Step 3: Commit**

```bash
git -C /c/CODE/Stereoscopic-Angelica add src/main/java/com/mitchellmarx/stereoscopic/core/StereoGLSMBridge.java src/main/java/com/mitchellmarx/stereoscopic/Stereoscopic.java
git -C /c/CODE/Stereoscopic-Angelica commit -m "feat(core): StereoGLSMBridge — wire scissor + viewport remaps into Angelica GLSM"
```

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

### Task 22: MixinEntityRenderer_Stereo (two-pass world loop) — Plan 1 subset

**Files:**
- Create: `C:\CODE\Stereoscopic-Angelica\src\mixin\java\com\mitchellmarx\stereoscopic\mixin\minecraft\MixinEntityRenderer_Stereo.java`

Reference: `C:\CODE\Angelica-sbs2\src\mixin\java\com\gtnewhorizons\angelica\mixins\early\angelica\stereo\MixinEntityRenderer_Stereo.java` (347 lines). This is the largest mixin. **Port verbatim with these substitutions:**
- Package: `com.gtnewhorizons.angelica.mixins.early.angelica.stereo` → `com.mitchellmarx.stereoscopic.mixin.minecraft`
- All `import com.gtnewhorizons.angelica.stereo.*;` → `import com.mitchellmarx.stereoscopic.core.*;`
- Method-prefix rename: `angelica$` → `stereoscopic$` (cosmetic, avoids collision with Angelica's own mixin methods)
- Imports of `AngelicaConfig` → remove; references to `AngelicaConfig.X` → `StereoConfig.X`

The 347 lines include: two-pass world loop wrapping `renderWorld`, eye-viewport setup, HUD/GUI duplication scaffolding (HUD details land in Plan 2), TIMER freeze hook (Plan 2 will revisit), and the `RenderTickEvent.END` per-eye fanout (also Plan 2).

**Plan 1 scope for this mixin:** port the file complete. Plan 2's tasks will *edit* specific injection methods, not re-create the file.

- [ ] **Step 1: Read the sbs2 source file end-to-end**

```bash
cat /c/CODE/Angelica-sbs2/src/mixin/java/com/gtnewhorizons/angelica/mixins/early/angelica/stereo/MixinEntityRenderer_Stereo.java
```

Confirm it imports only `StereoState`, `StereoMode`, `StereoHudMode`, `StereoCursor`, `AngelicaConfig`, and Minecraft/LWJGL/Mixin types. If it imports anything Iris-specific (`net.coderbot.*`), STOP and reconsider — that import belongs in Plan 2, not Plan 1.

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
                .setTooltip("Splits the screen into two eye views for VR headset use via Virtual Desktop / Steam Link. Roughly halves framerate.")
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

Edit `src/main/resources/mixins.stereoscopic.json` so `"sodium.MixinSodiumGameOptionPages_StereoToggle"` already in the list resolves correctly (it was added in Task 10).

- [ ] **Step 6: Compile**

```bash
./gradlew compileMixinJava
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git -C /c/CODE/Stereoscopic-Angelica add src/mixin/java/com/mitchellmarx/stereoscopic/mixin/sodium/MixinSodiumGameOptionPages_StereoToggle.java
git -C /c/CODE/Stereoscopic-Angelica commit -m "feat(mixin): Sodium options — SBS tick-box + IPD slider; pipeline rebuild on toggle"
```

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

- [ ] **Step 1: Deploy**

```bash
./gradlew build
```

The `copyToTestInstance` task runs as part of `build` and lands the jar in the Prism mods dir.

- [ ] **Step 2: Launch the GTNH profile and verify**

In Prism: launch the GTNH-daily profile. The mod should:
1. Load without crash (check logs for `[StereoCraft: Stereoscopic 3D]` mod-init line).
2. Add a "Stereoscopic SBS" group to Sodium's video options page (Pause → Options → Video Settings → scroll to bottom).
3. With the tick-box OFF, gameplay is identical to a no-mod baseline.
4. Ticking the box ON → screen splits L/R, each half showing the world from a slightly different camera position. The hand looks broken (no per-eye depth — Plan 2). Iris will fail to load or look corrupt (Plan 2). That's expected.
5. Adjusting the IPD slider visibly changes eye separation.
6. Ticking the box OFF → returns to full-screen mono.

Log the results to `docs/superpowers/notes/v0.1.0-manual-test.md` (create if missing) — see the spec's "Manual test plan" for the rubric.

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
