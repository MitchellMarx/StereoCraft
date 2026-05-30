# Stereoscopic-NeoForge Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port the `Stereoscopic` Fabric mod (`C:\CODE\Stereoscopic`) to NeoForge so it runs in the *All of Create — Aeronautics* modpack.

**Architecture:** Strict 1:1 port. Mirror the Fabric repo's package tree under `C:\CODE\Stereoscopic-Neoforge`. Pure-Java packages (`core`, `render`, `cursor`) port near-trivially. Mixin packages target older Sodium 0.6.13 / Iris 1.8.12 internals and get rewritten against those jars. New `compat/sable` module handles sub-level rendering coordination.

**Tech Stack:** Java 21, NeoForge 21.1.228, Sodium-NeoForge 0.6.13, Iris-NeoForge 1.8.12 (optional), Mixin (via NeoForge's bundled MixinExtras), Gradle (Kotlin DSL) with NeoForge's ModDevGradle plugin, JUnit 5.

**Reference repos:**
- `C:\CODE\Stereoscopic` — canonical Fabric source (port from this)
- `C:\CODE\Stereoscopic-Angelica` — Forge 1.7.10 sibling (look here when stuck on Forge-side patterns)
- `C:\CODE\Stereoscopic-Neoforge` — destination (already has `docs/`, `.gitignore`, initial commit)

**Pinned versions** — never modify without spec change:

| Component | Version |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.228 |
| Java | 21 |
| Sodium | sodium-neoforge 0.6.13 |
| Iris | iris-neoforge 1.8.12 (compileOnly + runtime soft-load) |

**Test target:** `C:\Users\felix\curseforge\minecraft\Instances\All of Create - Aeronautics (3)\mods` — deploy hook copies built jar there.

**Mod identity:**
- Mod ID: `stereoscopic`
- Java package: `com.mitchellmarx.stereoscopic`
- Maven coords: `com.mitchellmarx:stereoscopic-neoforge:0.1.0`
- License: LGPL-3.0-only

---

## Phase 1 — Foundation (Tasks 1–3)

The mod skeleton, the pure-Java core, and the (pure-Java) render orchestration. All tasks in this phase are testable without launching Minecraft.

---

### Task 1: Project scaffold and Gradle build

**Files:**
- Create: `C:\CODE\Stereoscopic-Neoforge\settings.gradle.kts`
- Create: `C:\CODE\Stereoscopic-Neoforge\build.gradle.kts`
- Create: `C:\CODE\Stereoscopic-Neoforge\gradle.properties`
- Create: `C:\CODE\Stereoscopic-Neoforge\gradle\wrapper\gradle-wrapper.properties`
- Create: `C:\CODE\Stereoscopic-Neoforge\gradlew` (copy from Fabric repo)
- Create: `C:\CODE\Stereoscopic-Neoforge\gradlew.bat` (copy from Fabric repo)
- Create: `C:\CODE\Stereoscopic-Neoforge\gradle\wrapper\gradle-wrapper.jar` (copy from Fabric repo)
- Create: `C:\CODE\Stereoscopic-Neoforge\LICENSE` (copy from `C:\CODE\Stereoscopic\LICENSE`)
- Create: `C:\CODE\Stereoscopic-Neoforge\src\main\resources\META-INF\neoforge.mods.toml`

- [ ] **Step 1: Copy LICENSE and Gradle wrapper**

```bash
cp /c/CODE/Stereoscopic/LICENSE /c/CODE/Stereoscopic-Neoforge/LICENSE
cp /c/CODE/Stereoscopic/gradlew /c/CODE/Stereoscopic-Neoforge/gradlew
cp /c/CODE/Stereoscopic/gradlew.bat /c/CODE/Stereoscopic-Neoforge/gradlew.bat
mkdir -p /c/CODE/Stereoscopic-Neoforge/gradle/wrapper
cp /c/CODE/Stereoscopic/gradle/wrapper/gradle-wrapper.jar /c/CODE/Stereoscopic-Neoforge/gradle/wrapper/
cp /c/CODE/Stereoscopic/gradle/wrapper/gradle-wrapper.properties /c/CODE/Stereoscopic-Neoforge/gradle/wrapper/
```

- [ ] **Step 2: Write `settings.gradle.kts`**

```kotlin
pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://maven.neoforged.net/releases") { name = "NeoForged" }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "stereoscopic-neoforge"
```

- [ ] **Step 3: Write `gradle.properties`**

```properties
org.gradle.jvmargs=-Xmx4G
org.gradle.parallel=true

minecraft_version=1.21.1
neoforge_version=21.1.228
parchment_minecraft=1.21.1
parchment_mappings=2024.11.17
sodium_version=0.6.13
iris_version=1.8.12

# Test instance deploy target (override with -Pstereoscopic.deployDir=...)
# stereoscopic.deployDir=C:/Users/felix/curseforge/minecraft/Instances/All of Create - Aeronautics (3)/mods

# Mod
mod_version=0.1.0
maven_group=com.mitchellmarx
archives_base_name=stereoscopic-neoforge
mod_id=stereoscopic
```

- [ ] **Step 4: Write `build.gradle.kts`**

```kotlin
plugins {
    id("net.neoforged.moddev") version "2.0.78"
    `java-library`
    `maven-publish`
}

version = project.property("mod_version") as String
group = project.property("maven_group") as String
base.archivesName.set(project.property("archives_base_name") as String)

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    withSourcesJar()
}

repositories {
    mavenCentral()
    maven("https://maven.neoforged.net/releases") { name = "NeoForged" }
    maven("https://maven.parchmentmc.org") { name = "ParchmentMC" }
    maven("https://api.modrinth.com/maven") { name = "Modrinth" }
}

neoForge {
    version = project.property("neoforge_version") as String

    parchment {
        minecraftVersion = project.property("parchment_minecraft") as String
        mappingsVersion = project.property("parchment_mappings") as String
    }

    runs {
        register("client") {
            client()
            gameDirectory = file("run")
            programArguments.addAll("--username", "Dev")
        }
    }

    mods {
        register(project.property("mod_id") as String) {
            sourceSet(sourceSets.main.get())
        }
    }
}

dependencies {
    // Sodium NeoForge: required hard dep
    implementation("maven.modrinth:sodium:mc${project.property("minecraft_version")}-${project.property("sodium_version")}-neoforge")

    // Iris NeoForge: optional soft dep
    compileOnly("maven.modrinth:iris:${project.property("iris_version")}+${project.property("minecraft_version")}-neoforge")
    runtimeOnly("maven.modrinth:iris:${project.property("iris_version")}+${project.property("minecraft_version")}-neoforge")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.processResources {
    inputs.property("version", version)
    inputs.property("minecraft_version", project.property("minecraft_version") as String)
    inputs.property("neoforge_version", project.property("neoforge_version") as String)
    inputs.property("sodium_version", project.property("sodium_version") as String)
    inputs.property("iris_version", project.property("iris_version") as String)
    filteringCharset = "UTF-8"
    filesMatching("META-INF/neoforge.mods.toml") {
        expand(
            "version" to version,
            "minecraft_version" to project.property("minecraft_version") as String,
            "neoforge_version" to project.property("neoforge_version") as String,
            "sodium_version" to project.property("sodium_version") as String,
            "iris_version" to project.property("iris_version") as String,
        )
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.test { useJUnitPlatform() }

// --- Deploy hook (mirrors Fabric mod's pattern) ---
val testInstanceMods = file(
    (project.findProperty("stereoscopic.deployDir") as String?)
        ?: "C:/Users/felix/curseforge/minecraft/Instances/All of Create - Aeronautics (3)/mods"
)

val copyToTestInstance by tasks.registering(Copy::class) {
    group = "stereoscopic"
    description = "Deploys built jar into the Aeronautics test instance"
    dependsOn(tasks.jar)
    onlyIf {
        val ok = testInstanceMods.isDirectory
        if (!ok) logger.lifecycle("Skipping copyToTestInstance: $testInstanceMods not found")
        ok
    }
    from(tasks.jar.flatMap { it.archiveFile })
    into(testInstanceMods)
    doFirst {
        testInstanceMods.listFiles { _, name ->
            name.startsWith("stereoscopic-neoforge-") && name.endsWith(".jar")
        }?.forEach { f ->
            if (!f.delete()) logger.warn("Could not delete stale jar $f (locked? close launcher)")
        }
    }
}

tasks.build { dependsOn(copyToTestInstance) }
```

- [ ] **Step 5: Write `src/main/resources/META-INF/neoforge.mods.toml`**

```toml
modLoader = "javafml"
loaderVersion = "[4,)"
license = "LGPL-3.0-only"
issueTrackerURL = "https://github.com/MitchellMarx/Stereoscopic-Neoforge/issues"

[[mods]]
modId = "stereoscopic"
version = "${version}"
displayName = "StereoCraft: Stereoscopic 3D (NeoForge)"
authors = "Mitchell Marx"
description = '''
Side-by-side (SBS) stereoscopic 3D rendering for 3D TVs, 3D monitors, and SBS-compatible displays. NeoForge 1.21.1 port.
'''
displayURL = "https://github.com/MitchellMarx/Stereoscopic-Neoforge"
logoFile = "icon.png"

[[mixins]]
config = "stereoscopic.mixins.json"

[[mixins]]
config = "stereoscopic-iris.mixins.json"

[[dependencies.stereoscopic]]
modId = "neoforge"
type = "required"
versionRange = "[${neoforge_version},22)"
ordering = "NONE"
side = "CLIENT"

[[dependencies.stereoscopic]]
modId = "minecraft"
type = "required"
versionRange = "[${minecraft_version},1.21.2)"
ordering = "NONE"
side = "CLIENT"

[[dependencies.stereoscopic]]
modId = "sodium"
type = "required"
versionRange = "[${sodium_version},0.7)"
ordering = "AFTER"
side = "CLIENT"

[[dependencies.stereoscopic]]
modId = "iris"
type = "optional"
versionRange = "[${iris_version},1.9)"
ordering = "AFTER"
side = "CLIENT"
```

- [ ] **Step 6: Verify build succeeds (no source code yet)**

Run: `cd /c/CODE/Stereoscopic-Neoforge && ./gradlew build --no-daemon`
Expected: BUILD SUCCESSFUL. The jar will be empty of code but Gradle should resolve all dependencies.

If `./gradlew build` fails because of missing Sodium/Iris jars from Modrinth: verify the exact version strings match the Modrinth slugs (`sodium`, `iris`). Sodium NeoForge's Modrinth file naming has historically been `sodium-neoforge-<version>+mc<mcver>` — adjust the version literal accordingly. Read the actual jar names in the test instance's `mods/` folder to cross-check.

- [ ] **Step 7: Commit the scaffold**

```bash
git -C /c/CODE/Stereoscopic-Neoforge add settings.gradle.kts build.gradle.kts gradle.properties \
    gradle/wrapper/ gradlew gradlew.bat LICENSE \
    src/main/resources/META-INF/neoforge.mods.toml
git -C /c/CODE/Stereoscopic-Neoforge commit -m "build: NeoForge ModDevGradle scaffold + deploy hook"
```

---

### Task 2: Mod entry point and mixin configs

**Files:**
- Create: `src/main/java/com/mitchellmarx/stereoscopic/Stereoscopic.java`
- Create: `src/main/resources/stereoscopic.mixins.json` (initially empty mixin list)
- Create: `src/main/resources/stereoscopic-iris.mixins.json` (initially empty mixin list)
- Create: `src/main/resources/assets/stereoscopic/icon.png` (copy from Fabric repo)
- Create: `src/main/resources/assets/stereoscopic/lang/en_us.json` (copy & merge from Fabric repo)

**Reference for porting:** `C:\CODE\Stereoscopic\src\main\java\com\mitchellmarx\stereoscopic\Stereoscopic.java` is the Fabric entry point. Read it first to see what gets initialized on client startup.

- [ ] **Step 1: Read the Fabric entry point**

Open `C:\CODE\Stereoscopic\src\main\java\com\mitchellmarx\stereoscopic\Stereoscopic.java` and note:
- What `StereoOptions.INSTANCE.load()` does
- Whether the Fabric entry point starts the cursor thread
- Whether it pre-warms Iris detection

You'll mirror these steps in the NeoForge `@Mod` class's `FMLClientSetupEvent` handler.

- [ ] **Step 2: Write the NeoForge entry point**

`src/main/java/com/mitchellmarx/stereoscopic/Stereoscopic.java`:

```java
package com.mitchellmarx.stereoscopic;

import com.mitchellmarx.stereoscopic.core.StereoOptions;
import com.mitchellmarx.stereoscopic.cursor.StereoCursor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.api.distmarker.Dist;

@Mod(value = Stereoscopic.MOD_ID, dist = Dist.CLIENT)
public final class Stereoscopic {
    public static final String MOD_ID = "stereoscopic";

    public Stereoscopic(IEventBus modBus) {
        modBus.addListener(this::onClientSetup);
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            StereoOptions.INSTANCE.load();
            StereoCursor.start();
        });
    }
}
```

(`StereoOptions` and `StereoCursor` don't exist yet — that's fine; this won't compile until Task 4 and Task 13. We'll add a stub block that the IDE accepts for now; the next step provides minimal stubs to keep the build green.)

- [ ] **Step 3: Add minimum stubs for `StereoOptions` and `StereoCursor`**

These stubs make the entry point compile. Real implementations come in later tasks.

`src/main/java/com/mitchellmarx/stereoscopic/core/StereoOptions.java`:

```java
package com.mitchellmarx.stereoscopic.core;

public final class StereoOptions {
    public static final StereoOptions INSTANCE = new StereoOptions();
    StereoOptions() {}
    public void load() {}
    public void save() {}
}
```

`src/main/java/com/mitchellmarx/stereoscopic/cursor/StereoCursor.java`:

```java
package com.mitchellmarx.stereoscopic.cursor;

public final class StereoCursor {
    private StereoCursor() {}
    public static void start() {}
}
```

- [ ] **Step 4: Write empty mixin configs**

`src/main/resources/stereoscopic.mixins.json`:

```json
{
    "required": true,
    "minVersion": "0.8",
    "package": "com.mitchellmarx.stereoscopic.mixin",
    "compatibilityLevel": "JAVA_21",
    "client": [],
    "mixins": [],
    "injectors": { "defaultRequire": 1 }
}
```

`src/main/resources/stereoscopic-iris.mixins.json`:

```json
{
    "required": false,
    "minVersion": "0.8",
    "package": "com.mitchellmarx.stereoscopic.mixin.iris",
    "compatibilityLevel": "JAVA_21",
    "client": [],
    "mixins": [],
    "injectors": { "defaultRequire": 0 }
}
```

- [ ] **Step 5: Copy assets**

```bash
mkdir -p /c/CODE/Stereoscopic-Neoforge/src/main/resources/assets/stereoscopic/lang
cp /c/CODE/Stereoscopic/src/main/resources/assets/stereoscopic/icon.png \
   /c/CODE/Stereoscopic-Neoforge/src/main/resources/assets/stereoscopic/icon.png
cp /c/CODE/Stereoscopic/src/main/resources/assets/stereoscopic/lang/en_us.json \
   /c/CODE/Stereoscopic-Neoforge/src/main/resources/assets/stereoscopic/lang/en_us.json 2>/dev/null || \
   echo '{}' > /c/CODE/Stereoscopic-Neoforge/src/main/resources/assets/stereoscopic/lang/en_us.json
```

Also copy `icon.png` to `src/main/resources/icon.png` (NeoForge's `logoFile` looks at the root of the jar):

```bash
cp /c/CODE/Stereoscopic/src/main/resources/assets/stereoscopic/icon.png \
   /c/CODE/Stereoscopic-Neoforge/src/main/resources/icon.png
```

- [ ] **Step 6: Build + launch sanity check**

Run: `cd /c/CODE/Stereoscopic-Neoforge && ./gradlew runClient --no-daemon`
Expected: Game launches into title screen. Click *Mods* — `stereoscopic` should appear in the list with version `0.1.0`. Quit.

If the game crashes at the title screen complaining about Sodium / Iris versions, check the resolved versions in `mods/` after `./gradlew build` and adjust `gradle.properties` to match what Modrinth actually serves.

- [ ] **Step 7: Commit**

```bash
git -C /c/CODE/Stereoscopic-Neoforge add src/
git -C /c/CODE/Stereoscopic-Neoforge commit -m "feat: @Mod entry point + empty mixin configs + stubs for StereoOptions/StereoCursor"
```

---

### Task 3: Core package — `StereoMath`, `StereoMode`

These are pure-math types — port verbatim from Fabric. Tests port verbatim too.

**Files:**
- Modify: `src/main/java/com/mitchellmarx/stereoscopic/core/StereoMode.java` (replace stub)
- Create: `src/main/java/com/mitchellmarx/stereoscopic/core/StereoMath.java`
- Create: `src/test/java/com/mitchellmarx/stereoscopic/core/StereoModeTest.java`
- Create: `src/test/java/com/mitchellmarx/stereoscopic/core/StereoMathTest.java`

**Reference:**
- `C:\CODE\Stereoscopic\src\main\java\com\mitchellmarx\stereoscopic\core\StereoMode.java`
- `C:\CODE\Stereoscopic\src\main\java\com\mitchellmarx\stereoscopic\core\StereoMath.java`
- `C:\CODE\Stereoscopic\src\test\java\com\mitchellmarx\stereoscopic\core\StereoModeTest.java`
- `C:\CODE\Stereoscopic\src\test\java\com\mitchellmarx\stereoscopic\core\StereoMathTest.java`

- [ ] **Step 1: Port `StereoMode.java` verbatim**

The Fabric file already lives at `C:\CODE\Stereoscopic\src\main\java\com\mitchellmarx\stereoscopic\core\StereoMode.java`. Read it, then copy its contents into the NeoForge file at the same relative path. Package declaration stays `com.mitchellmarx.stereoscopic.core`. No code changes needed.

- [ ] **Step 2: Port `StereoMath.java` verbatim**

Same pattern. Read the Fabric file, copy contents, keep package declaration. No code changes needed (it's pure math, no Fabric APIs).

- [ ] **Step 3: Port the tests verbatim**

Copy `StereoModeTest.java` and `StereoMathTest.java` from the Fabric `src/test/java/com/mitchellmarx/stereoscopic/core/` into the corresponding NeoForge path.

- [ ] **Step 4: Run the tests**

```bash
cd /c/CODE/Stereoscopic-Neoforge && ./gradlew test --no-daemon
```

Expected: All tests pass. If a test fails, the cause is either a JUnit version mismatch or a missing import — fix at the source, do not modify the test.

**Note on test classpath:** NeoForge's ModDevGradle does not propagate
Minecraft's transitive JOML to the test classpath. If test compilation
fails with `org.joml.Matrix4f` (or similar) not found, add to gradle.properties:

    joml_version=1.10.8

and to build.gradle.kts dependencies block:

    testImplementation("org.joml:joml:${project.property("joml_version")}")

Version 1.10.8 matches what NeoForge 21.1.228 ships at runtime; do not
pin a different version.

- [ ] **Step 5: Commit**

```bash
git -C /c/CODE/Stereoscopic-Neoforge add src/main/java/com/mitchellmarx/stereoscopic/core/StereoMode.java \
    src/main/java/com/mitchellmarx/stereoscopic/core/StereoMath.java \
    src/test/java/com/mitchellmarx/stereoscopic/core/StereoModeTest.java \
    src/test/java/com/mitchellmarx/stereoscopic/core/StereoMathTest.java
git -C /c/CODE/Stereoscopic-Neoforge commit -m "feat(core): port StereoMode, StereoMath + tests from Fabric mod"
```

---

### Task 4: Core package — `StereoOptions`, `StereoOptionsIO`, `StereoState`

These ports differ from Task 3 in exactly one place: the config-file path. Fabric uses `FabricLoader.getInstance().getConfigDir()`; NeoForge uses `FMLPaths.CONFIGDIR.get()`. Everything else is identical.

**Files:**
- Modify: `src/main/java/com/mitchellmarx/stereoscopic/core/StereoOptions.java` (replace stub)
- Create: `src/main/java/com/mitchellmarx/stereoscopic/core/StereoOptionsIO.java`
- Create: `src/main/java/com/mitchellmarx/stereoscopic/core/StereoState.java`
- Create: `src/test/java/com/mitchellmarx/stereoscopic/core/StereoOptionsIOTest.java`
- Create: `src/test/java/com/mitchellmarx/stereoscopic/core/StereoStateTest.java`

- [ ] **Step 1: Port `StereoOptionsIO.java` verbatim**

`StereoOptionsIO` is pure JSON read/write — no loader-specific code. Copy contents from `C:\CODE\Stereoscopic\src\main\java\com\mitchellmarx\stereoscopic\core\StereoOptionsIO.java`.

- [ ] **Step 2: Port `StereoState.java` verbatim**

Same — pure state holder. Copy from Fabric.

- [ ] **Step 3: Port `StereoOptions.java` with loader swap**

Read `C:\CODE\Stereoscopic\src\main\java\com\mitchellmarx\stereoscopic\core\StereoOptions.java`. Translate the one Fabric-specific call:

```java
// Fabric:
import net.fabricmc.loader.api.FabricLoader;
//   ...
public Path configFile() {
    return FabricLoader.getInstance().getConfigDir().resolve("stereoscopic-options.json");
}
```

→

```java
// NeoForge:
import net.neoforged.fml.loading.FMLPaths;
//   ...
public Path configFile() {
    return FMLPaths.CONFIGDIR.get().resolve("stereoscopic-options.json");
}
```

Everything else (fields `mode`, `ipd`, `convergence`, `swapEyes`, constructor, `save()`/`load()` methods) ports verbatim.

- [ ] **Step 4: Port the option-IO and state tests**

Copy `StereoOptionsIOTest.java` and `StereoStateTest.java` from `C:\CODE\Stereoscopic\src\test\java\com\mitchellmarx\stereoscopic\core\` to the NeoForge path. These tests use `@TempDir` and don't touch `configFile()`, so they port without modification.

- [ ] **Step 5: Run tests**

```bash
cd /c/CODE/Stereoscopic-Neoforge && ./gradlew test --no-daemon
```

Expected: all 4 test classes pass (math, mode, options-IO, state).

**Note on test classpath:** NeoForge's ModDevGradle does not propagate
Minecraft's transitive Gson to the test classpath. If StereoOptionsIOTest
fails with `NoClassDefFoundError: com/google/gson/JsonElement`, add to
gradle.properties:

    gson_version=2.11.0

and to build.gradle.kts dependencies block:

    testImplementation("com.google.code.gson:gson:${project.property("gson_version")}")

Version 2.11.0 matches what NeoForge 21.1.228 ships at runtime; do not
pin a different version.

**Note on test classpath (SLF4J):** Similar to Gson, NeoForge's
ModDevGradle does not propagate Minecraft's transitive SLF4J to the test
classpath. If `StereoOptionsIOTest.ipdIsClampedOnRead` (or any other test
that triggers a `Stereoscopic.LOG` call site) fails with
`NoClassDefFoundError: org/slf4j/LoggerFactory`, add to gradle.properties:

    slf4j_version=2.0.9

and to build.gradle.kts dependencies block:

    testImplementation("org.slf4j:slf4j-api:${project.property("slf4j_version")}")

Version 2.0.9 matches Mojang's launcher manifest for MC 1.21.x; do not
pin a different version.

- [ ] **Step 6: Commit**

```bash
git -C /c/CODE/Stereoscopic-Neoforge add src/main/java/com/mitchellmarx/stereoscopic/core/ src/test/java/com/mitchellmarx/stereoscopic/core/
git -C /c/CODE/Stereoscopic-Neoforge commit -m "feat(core): port StereoOptions/IO/State + tests; FabricLoader → FMLPaths config dir"
```

---

### Task 5: Render package — `ViewportMath`, `PerEyeRenderer` (StereoBlur dropped)

`ViewportMath` is pure math (verbatim port + test). `PerEyeRenderer` orchestrates the two-pass world render with several MC-1.21.1-specific API adaptations. **`StereoBlur` is dropped** — it's a workaround for an MC 1.21.6+ regression that doesn't exist in 1.21.1.

**Why `StereoBlur` is dropped:** The Fabric mod's `StereoBlur` exists because `GameRenderer.renderBlur()` in MC 1.21.6+ runs through `CommandEncoder.createRenderPass`, which rasterizes to the full color-attachment view ignoring classic glScissor. In MC 1.21.1, `renderBlur` still uses the older `PostChain` path that respects glScissor, so per-eye blur Just Works through the existing scissor state set up by `PerEyeRenderer.scissorRaw()`.

**Files:**
- Create: `src/main/java/com/mitchellmarx/stereoscopic/render/ViewportMath.java`
- Create: `src/main/java/com/mitchellmarx/stereoscopic/render/PerEyeRenderer.java`
- Create: `src/main/java/com/mitchellmarx/stereoscopic/compat/iris/PerEyeRenderTargetHooks.java` (minimal stub — full impl in Task 12)
- Create: `src/test/java/com/mitchellmarx/stereoscopic/render/ViewportMathTest.java`

- [ ] **Step 1: Port `ViewportMath.java` and its test verbatim**

Pure math. Copy both files from Fabric paths to NeoForge paths.

- [ ] **Step 2: Port `PerEyeRenderer.java` with three targeted MC 1.21.1 adaptations**

Read `C:\CODE\Stereoscopic\src\main\java\com\mitchellmarx\stereoscopic\render\PerEyeRenderer.java`. Then apply these MC 1.21.1 adaptations:

1. **`SimpleFramebuffer` → `TextureTarget`**. Yarn `net.minecraft.client.gl.SimpleFramebuffer` is `com.mojang.blaze3d.pipeline.TextureTarget` in MC 1.21.1 (NOT `net.minecraft.client.renderer.TextureTarget` — that path is post-1.21.6). Constructor changes from `new SimpleFramebuffer("name", w, h, true)` to `new TextureTarget(w, h, true, true)` (no name parameter, fourth arg is `clearOnLoad`). `.delete()` → `.destroyBuffers()`.

2. **`GlStateManager` path**. Yarn 1.21.11 moves it to `com.mojang.blaze3d.opengl`; MC 1.21.1 has it at `com.mojang.blaze3d.platform.GlStateManager`. Use the `platform` path.

3. **Remove `RenderSystem.enableScissorForRenderTypeDraws` / `disableScissorForRenderTypeDraws` calls**. These were added with the MC 1.21.6 CommandEncoder pipeline; they don't exist in 1.21.1. In 1.21.1 the GuiRenderer flush still goes through the older render path that respects classic glScissor, so `scissorRaw()` alone is sufficient — drop both `*ForRenderTypeDraws` calls and replace with explanatory comments.

- [ ] **Step 3: Add minimal stub for `PerEyeRenderTargetHooks`**

`PerEyeRenderer` calls `PerEyeRenderTargetHooks.setActiveEye(int)` and `PerEyeRenderTargetHooks.irisStartFrameBetweenEyes()`. Create a minimal stub at `src/main/java/com/mitchellmarx/stereoscopic/compat/iris/PerEyeRenderTargetHooks.java` with just those two no-op methods. The full implementation lands in Task 12 (which replaces this file wholesale).

- [ ] **Step 4: Build + test**

```bash
cd /c/CODE/Stereoscopic-Neoforge && ./gradlew build --no-daemon
```

Expected: BUILD SUCCESSFUL. `ViewportMathTest` passes. `PerEyeRenderer` is not unit-tested (integration-only); build must compile.

- [ ] **Step 5: Commit**

```bash
git -C /c/CODE/Stereoscopic-Neoforge add \
    src/main/java/com/mitchellmarx/stereoscopic/render/ \
    src/main/java/com/mitchellmarx/stereoscopic/compat/iris/ \
    src/test/java/com/mitchellmarx/stereoscopic/render/ \
    docs/superpowers/plans/2026-05-29-stereoscopic-neoforge-implementation.md

git -C /c/CODE/Stereoscopic-Neoforge commit -m "feat(render): ViewportMath, PerEyeRenderer + PerEyeRenderTargetHooks stub; drop StereoBlur (1.21.1 has no CommandEncoder)"
```

---

## Phase 2 — Investigation (Task 6)

Before writing any mixin, find the actual target classes in the pinned Sodium and Iris jars. This task produces a reference document, no code.

---

### Task 6: Decompile and document Sodium 0.6.13 + Iris 1.8.12 internals

**Goal:** For each of the 10 Sodium/Iris mixins the Fabric mod has, locate the equivalent class in the NeoForge jars and record its fully qualified name and target method signatures. This document is consulted in Tasks 9 and 14–17.

**Files:**
- Create: `docs/superpowers/notes/2026-05-29-sodium-iris-class-map.md`

- [ ] **Step 1: Extract the jars**

```bash
mkdir -p /c/tmp/stereo-jar-decomp/sodium /c/tmp/stereo-jar-decomp/iris
cd /c/tmp/stereo-jar-decomp/sodium && unzip -o "/c/Users/felix/curseforge/minecraft/Instances/All of Create - Aeronautics (3)/mods/sodium-neoforge-0.6.13+mc1.21.1.jar"
cd /c/tmp/stereo-jar-decomp/iris && unzip -o "/c/Users/felix/curseforge/minecraft/Instances/All of Create - Aeronautics (3)/mods/iris-neoforge-1.8.12+mc1.21.1.jar"
```

- [ ] **Step 2: For each Fabric Sodium/Iris mixin, find its target in the NeoForge jars**

Each Fabric mixin has an `@Mixin(TargetClass.class)` annotation telling you what to look for. For each of the 10 mixins below, open the Fabric file and read the `@Mixin` target, then search the extracted jar for the class:

| Fabric mixin | Fabric `@Mixin` target (read in source) | NeoForge search command |
|---|---|---|
| `mixin/sodium/MixinSodiumWorldRenderer` | `net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer` | `find /c/tmp/stereo-jar-decomp/sodium -name "SodiumWorldRenderer*"` |
| `mixin/iris/MixinRenderTargets` | `net.irisshaders.iris...RenderTargets` | `find /c/tmp/stereo-jar-decomp/iris -name "RenderTargets*"` |
| `mixin/iris/MixinGlFramebuffer` | `net.irisshaders.iris.gl.framebuffer.GlFramebuffer` | `find /c/tmp/stereo-jar-decomp/iris -name "GlFramebuffer*"` |
| `mixin/iris/MixinCameraUniforms` | `net.irisshaders.iris.uniforms.CameraUniforms` | `find /c/tmp/stereo-jar-decomp/iris -name "CameraUniforms*"` |
| `mixin/iris/MixinMatrixUniforms` | `net.irisshaders.iris.uniforms.MatrixUniforms` | `find /c/tmp/stereo-jar-decomp/iris -name "MatrixUniforms*"` |
| `mixin/iris/MixinIrisRenderingPipeline` | `net.irisshaders.iris...IrisRenderingPipeline` | `find /c/tmp/stereo-jar-decomp/iris -name "IrisRenderingPipeline*"` |
| `mixin/iris/MixinFinalPassRenderer` | `net.irisshaders.iris...FinalPassRenderer` | `find /c/tmp/stereo-jar-decomp/iris -name "FinalPassRenderer*"` |
| `mixin/iris/MixinShadowRenderer` | `net.irisshaders.iris.shadows.ShadowRenderer` | `find /c/tmp/stereo-jar-decomp/iris -name "ShadowRenderer*"` |
| `mixin/iris/MixinIrisLodRenderProgram` | `net.irisshaders.iris.compat.dh...LodRenderProgram` | `find /c/tmp/stereo-jar-decomp/iris -name "*LodRenderProgram*"` |
| `mixin/iris/MixinDHCompat` | (DH compat entry point — likely `DistantHorizonsCompat` or similar) | `find /c/tmp/stereo-jar-decomp/iris -name "*DH*" -o -name "*DistantHorizon*"` |

- [ ] **Step 3: For each target, record the method signatures the Fabric mixin hooks**

For each Fabric mixin's `@Inject`, `@Redirect`, `@ModifyArgs`, `@ModifyVariable`, etc. annotation, you need to confirm the target method exists with the right signature in the NeoForge class. Use `javap`:

```bash
javap -p -s "/c/tmp/stereo-jar-decomp/sodium/net/caffeinemc/mods/sodium/client/render/SodiumWorldRenderer.class" \
  | grep -E "<method-name-from-fabric-mixin>"
```

Record any class-rename or method-signature deltas in the notes file.

- [ ] **Step 4: Write the class map document**

`docs/superpowers/notes/2026-05-29-sodium-iris-class-map.md`:

For each of the 10 mixins, record:
- Fabric `@Mixin` target FQN
- NeoForge equivalent FQN (may be identical, may have moved package)
- Which `@Inject`/`@Redirect` target methods exist with same signature, which need adaptation
- For Iris: which methods Iris 1.8 exposes that Iris 1.10 doesn't, or vice versa

- [ ] **Step 5: Commit the notes**

```bash
git -C /c/CODE/Stereoscopic-Neoforge add docs/superpowers/notes/2026-05-29-sodium-iris-class-map.md
git -C /c/CODE/Stereoscopic-Neoforge commit -m "docs: Sodium 0.6.13 + Iris 1.8.12 class & method map for mixin port"
```

---

## Phase 3 — Minecraft mixins (Tasks 7–9)

The 11 vanilla-MC mixins. These are the foundation: frame lifecycle, state management, world render entry/exit, per-eye HUD, mouse coords. None depend on Sodium or Iris internals.

---

### Task 7: Mixin foundation — `MixinMinecraftClient`, `MixinGameRenderer`, `MixinRenderSystem`, `MixinGlStateManager`

These are the four "frame lifecycle" mixins — they tell the rest of the codebase when each frame starts/ends and when render state changes.

**Files:**
- Create: `src/main/java/com/mitchellmarx/stereoscopic/mixin/minecraft/MixinMinecraftClient.java`
- Create: `src/main/java/com/mitchellmarx/stereoscopic/mixin/minecraft/MixinGameRenderer.java`
- Create: `src/main/java/com/mitchellmarx/stereoscopic/mixin/minecraft/MixinRenderSystem.java`
- Create: `src/main/java/com/mitchellmarx/stereoscopic/mixin/minecraft/MixinGlStateManager.java`
- Modify: `src/main/resources/stereoscopic.mixins.json` (add the 4 mixin entries)

**Reference:** `C:\CODE\Stereoscopic\src\main\java\com\mitchellmarx\stereoscopic\mixin\minecraft\` (4 files of same name).

- [ ] **Step 1: Read each Fabric mixin and record its target class + injection points**

For each of the 4 mixins, write down: target class FQN, list of `@Inject`/`@Redirect`/`@ModifyArgs` annotations and their `method=` / `at=` values.

- [ ] **Step 2: Verify each target method exists in MC 1.21.1**

Open the resolved Minecraft sources in the IDE. For each target method recorded in Step 1, confirm it exists with the same signature in 1.21.1. If a signature has changed, update the mixin's `method=` string accordingly.

- [ ] **Step 3: Port each of the 4 mixins**

Copy each Fabric mixin into the NeoForge path with package declaration unchanged. Adjust any 1.21.1 signature mismatches found in Step 2.

- [ ] **Step 4: Register the mixins in `stereoscopic.mixins.json`**

Update the `client` array to include:

```json
"client": [
    "minecraft.MixinMinecraftClient",
    "minecraft.MixinGameRenderer",
    "minecraft.MixinRenderSystem",
    "minecraft.MixinGlStateManager"
]
```

- [ ] **Step 5: Build + launch sanity check**

```bash
cd /c/CODE/Stereoscopic-Neoforge && ./gradlew runClient --no-daemon
```

Expected: Game launches. Mode is still `OFF` so no per-eye rendering yet. The point of this step is to confirm the mixins **apply** without crashing (you'll see them in `logs/latest.log`'s Mixin output if you grep for `MixinMinecraftClient` etc.).

- [ ] **Step 6: Commit**

```bash
git -C /c/CODE/Stereoscopic-Neoforge add src/main/java/com/mitchellmarx/stereoscopic/mixin/minecraft/Mixin{MinecraftClient,GameRenderer,RenderSystem,GlStateManager}.java src/main/resources/stereoscopic.mixins.json
git -C /c/CODE/Stereoscopic-Neoforge commit -m "feat(mixin): MC frame-lifecycle mixins (MinecraftClient, GameRenderer, RenderSystem, GlStateManager)"
```

---

### Task 8: World-render mixins — `MixinWorldRenderer`, `MixinFramebuffer`, `MixinGuiRenderer`, `MixinGuiRendererScissor`, `MixinGlCommandEncoder`

The actual two-pass render path. After this task, `OFF → SBS_HALF` should produce *some* visible per-eye output (probably broken under shaders — that's Phase 5's job).

**Files:**
- Create: `src/main/java/com/mitchellmarx/stereoscopic/mixin/minecraft/MixinWorldRenderer.java`
- Create: `src/main/java/com/mitchellmarx/stereoscopic/mixin/minecraft/MixinFramebuffer.java`
- Create: `src/main/java/com/mitchellmarx/stereoscopic/mixin/minecraft/MixinGuiRenderer.java`
- Create: `src/main/java/com/mitchellmarx/stereoscopic/mixin/minecraft/MixinGuiRendererScissor.java`
- Create: `src/main/java/com/mitchellmarx/stereoscopic/mixin/minecraft/MixinGlCommandEncoder.java`
- Modify: `src/main/resources/stereoscopic.mixins.json` (add 5 entries)

- [ ] **Step 1: Read each Fabric mixin**

Same routine as Task 7 — note target class, target methods, injection types.

- [ ] **Step 2: Verify against MC 1.21.1**

Highest-risk targets between 1.21.11 → 1.21.1:
- `WorldRenderer.renderLevel(...)` — parameter list shape; verify the `DeltaTracker` / matrix / camera / lightTexture / projection parameters
- `GuiRenderer` and the new render-pipeline 1.21.1 vs 1.21.11 layer interface

If any target method has materially shifted, update mixin annotations accordingly. The *injection points* (HEAD / RETURN / specific INVOKE targets) should preserve the same effect.

- [ ] **Step 3: Port each of the 5 mixins**

Copy from Fabric, fix any signature mismatches.

- [ ] **Step 4: Register all 5 in `stereoscopic.mixins.json`**

```json
"client": [
    "minecraft.MixinMinecraftClient",
    "minecraft.MixinGameRenderer",
    "minecraft.MixinRenderSystem",
    "minecraft.MixinGlStateManager",
    "minecraft.MixinWorldRenderer",
    "minecraft.MixinFramebuffer",
    "minecraft.MixinGuiRenderer",
    "minecraft.MixinGuiRendererScissor",
    "minecraft.MixinGlCommandEncoder"
]
```

- [ ] **Step 5: Build + launch + functional check**

```bash
./gradlew runClient --no-daemon
```

Expected: launch into a world (singleplayer, no shaders, no Sodium-specific compat yet). The mod is still `OFF` by default. No visual change yet — Phase 5 wires up the options UI; until then, manually set mode by editing `run/config/stereoscopic-options.json` to `"mode": "SBS_HALF"` and relaunch.

Acceptance: after editing the config to `SBS_HALF` and relaunching, the world view splits into LEFT and RIGHT halves with offset cameras. HUD and chat may misbehave under shaders — that's expected at this checkpoint.

- [ ] **Step 6: Commit**

```bash
git -C /c/CODE/Stereoscopic-Neoforge add src/main/java/com/mitchellmarx/stereoscopic/mixin/minecraft/Mixin{WorldRenderer,Framebuffer,GuiRenderer,GuiRendererScissor,GlCommandEncoder}.java src/main/resources/stereoscopic.mixins.json
git -C /c/CODE/Stereoscopic-Neoforge commit -m "feat(mixin): two-pass world render + per-eye GUI/framebuffer mixins"
```

---

### Task 9: Mouse + accessors — `MixinMouse`, `MouseAccessor`, `CameraAccessor`

Accessor mixins expose private fields/methods; `MixinMouse` translates cursor coordinates so the cursor appears in the eye-half the mouse is over.

**Files:**
- Create: `src/main/java/com/mitchellmarx/stereoscopic/mixin/minecraft/MixinMouse.java`
- Create: `src/main/java/com/mitchellmarx/stereoscopic/mixin/minecraft/MouseAccessor.java`
- Create: `src/main/java/com/mitchellmarx/stereoscopic/mixin/minecraft/CameraAccessor.java`
- Modify: `src/main/resources/stereoscopic.mixins.json` (add 3 entries)

- [ ] **Step 1: Port each file from Fabric**

Accessors port verbatim (they just expose fields by name — field name must match). `MixinMouse` ports verbatim if `MouseHandler` (1.21.1's class name) hasn't moved.

- [ ] **Step 2: Register in `stereoscopic.mixins.json`**

Add `"minecraft.MixinMouse"`, `"minecraft.MouseAccessor"`, `"minecraft.CameraAccessor"` to the `client` array.

- [ ] **Step 3: Build**

```bash
./gradlew build --no-daemon
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git -C /c/CODE/Stereoscopic-Neoforge add src/main/java/com/mitchellmarx/stereoscopic/mixin/minecraft/{MixinMouse,MouseAccessor,CameraAccessor}.java src/main/resources/stereoscopic.mixins.json
git -C /c/CODE/Stereoscopic-Neoforge commit -m "feat(mixin): mouse coord translation + Camera/Mouse accessors"
```

---

## Phase 4 — Sodium integration (Tasks 10–11)

`MixinSodiumWorldRenderer` is the single biggest rewrite of the port. Sodium 0.6.13's `SodiumWorldRenderer` has a different internal layout from Sodium 0.8.11.

---

### Task 10: Sodium compat helper — `SecondEyeSkipHooks`

Pure-Java helper; ports verbatim. Lives in `compat/sodium/`. The Fabric mod uses it from `MixinSodiumWorldRenderer` to track whether the second-eye BFS should be skipped.

**Files:**
- Create: `src/main/java/com/mitchellmarx/stereoscopic/compat/sodium/SecondEyeSkipHooks.java`
- Create: `src/test/java/com/mitchellmarx/stereoscopic/compat/sodium/SecondEyeSkipHooksTest.java`

- [ ] **Step 1: Port the file verbatim from Fabric**

`SecondEyeSkipHooks` is a small state-machine that flips on `LEFT` eye and flips off on `RIGHT` eye. Port directly.

- [ ] **Step 2: Port the test**

Copy `SecondEyeSkipHooksTest.java` from the Fabric test path. Run:

```bash
./gradlew test --no-daemon
```

Expected: pass.

- [ ] **Step 3: Commit**

```bash
git -C /c/CODE/Stereoscopic-Neoforge add src/main/java/com/mitchellmarx/stereoscopic/compat/sodium/ src/test/java/com/mitchellmarx/stereoscopic/compat/sodium/
git -C /c/CODE/Stereoscopic-Neoforge commit -m "feat(compat/sodium): SecondEyeSkipHooks helper + test"
```

---

### Task 11: `MixinSodiumWorldRenderer` — rewrite against Sodium 0.6.13 internals

**Files:**
- Create: `src/main/java/com/mitchellmarx/stereoscopic/mixin/sodium/MixinSodiumWorldRenderer.java`
- Modify: `src/main/resources/stereoscopic.mixins.json` (add 1 entry)

**Reference:**
- `C:\CODE\Stereoscopic\src\main\java\com\mitchellmarx\stereoscopic\mixin\sodium\MixinSodiumWorldRenderer.java` — Fabric/Sodium-0.8 version, the *effect* spec
- `docs/superpowers/notes/2026-05-29-sodium-iris-class-map.md` from Task 6 — the class/method delta sheet

- [ ] **Step 1: Identify the Fabric mixin's intended effects**

Read the Fabric `MixinSodiumWorldRenderer`. List each `@Inject`/`@Redirect` and the runtime effect it achieves. Likely effects (verify from source):
- On `setupTerrain` (or equivalent), if `StereoState.getActiveEye() == RIGHT && SecondEyeSkipHooks.shouldSkipBFS()`, skip the visibility BFS and reuse the LEFT pass's render list.
- On chunk graph build, mark the second-eye pass as a no-op for graph mutation.

- [ ] **Step 2: Map each effect to a Sodium 0.6.13 hook point**

Using Task 6's class map, identify where in Sodium 0.6.13's `SodiumWorldRenderer` the same effect can be achieved. Sodium 0.6's chunk renderer entry methods (e.g. `updateChunks`, `drawChunkLayer`, `prepareTerrain`) are the candidates. Each effect from Step 1 needs a chosen target method + injection mode in Sodium 0.6.

- [ ] **Step 3: Write the mixin**

Author the new `MixinSodiumWorldRenderer` against Sodium 0.6's class and methods. Keep the Fabric mixin's public effect (skip second-eye BFS gated by `SecondEyeSkipHooks` + `StereoState`), but with Sodium-0.6-correct target signatures.

- [ ] **Step 4: Register**

Add `"sodium.MixinSodiumWorldRenderer"` to `stereoscopic.mixins.json`.

- [ ] **Step 5: Manual verification — launch and look for missing chunks**

```bash
./gradlew runClient --no-daemon
```

Set mode to `SBS_HALF` (config or, once Task 19 lands, the Sodium options page). Verify:
- Both eye halves render terrain
- No missing-chunks artifact at typical IPD (64mm) and convergence (4 blocks)
- At extreme IPD (75mm) you may see a single missing chunk for one frame at the screen edge — this is the documented Sodium chunk-skip caveat, not a failure

- [ ] **Step 6: Commit**

```bash
git -C /c/CODE/Stereoscopic-Neoforge add src/main/java/com/mitchellmarx/stereoscopic/mixin/sodium/ src/main/resources/stereoscopic.mixins.json
git -C /c/CODE/Stereoscopic-Neoforge commit -m "feat(mixin/sodium): MixinSodiumWorldRenderer rewritten against Sodium 0.6.13 internals"
```

---

## Phase 5 — Iris integration (Tasks 12–15)

The 9 Iris mixins + the iris compat helper. Iris 1.8.12 → 1.10.7 has churned class layouts but the strategies (per-eye colortex banking, DH-shear restoration, shared shadow pass) are unchanged.

---

### Task 12: Iris compat helper — `PerEyeRenderTargetHooks`

**Files:**
- Create: `src/main/java/com/mitchellmarx/stereoscopic/compat/iris/PerEyeRenderTargetHooks.java`

- [ ] **Step 1: Port the file from Fabric**

Read `C:\CODE\Stereoscopic\src\main\java\com\mitchellmarx\stereoscopic\compat\iris\PerEyeRenderTargetHooks.java`. This is a state holder + bank-index resolver. If it references Iris-specific types, swap to the Iris 1.8 equivalents per Task 6's class map.

- [ ] **Step 2: Build**

```bash
./gradlew build --no-daemon
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git -C /c/CODE/Stereoscopic-Neoforge add src/main/java/com/mitchellmarx/stereoscopic/compat/iris/
git -C /c/CODE/Stereoscopic-Neoforge commit -m "feat(compat/iris): PerEyeRenderTargetHooks ported"
```

---

### Task 13: Iris colortex mixins — `MixinRenderTargets`, `MixinGlFramebuffer`

Per-eye colortex banking. Each eye gets its own bank of TAA/reflection/SSCB textures so eyes don't cross-contaminate.

**Files:**
- Create: `src/main/java/com/mitchellmarx/stereoscopic/mixin/iris/MixinRenderTargets.java`
- Create: `src/main/java/com/mitchellmarx/stereoscopic/mixin/iris/MixinGlFramebuffer.java`
- Modify: `src/main/resources/stereoscopic-iris.mixins.json` (add 2 entries)

- [ ] **Step 1: Port both files**

Use Task 6's notes for target class FQNs in Iris 1.8.12 (likely identical or near-identical to Iris 1.10). Translate any method-signature deltas.

- [ ] **Step 2: Register**

Update `stereoscopic-iris.mixins.json`'s `client` array:

```json
"client": [
    "MixinRenderTargets",
    "MixinGlFramebuffer"
]
```

- [ ] **Step 3: Build + launch w/ shaders**

```bash
./gradlew runClient --no-daemon
```

In game, set EuphoriaPatcher/BSL shader pack via Iris's UI. Set mode to `SBS_HALF`. Verify: per-eye render still works under shaders. TAA + reflections per-eye (no flickering across the seam).

- [ ] **Step 4: Commit**

```bash
git -C /c/CODE/Stereoscopic-Neoforge add src/main/java/com/mitchellmarx/stereoscopic/mixin/iris/Mixin{RenderTargets,GlFramebuffer}.java src/main/resources/stereoscopic-iris.mixins.json
git -C /c/CODE/Stereoscopic-Neoforge commit -m "feat(mixin/iris): per-eye colortex banking (RenderTargets + GlFramebuffer)"
```

---

### Task 14: Iris uniform mixins — `MixinCameraUniforms`, `MixinMatrixUniforms`

Per-eye camera/projection uniforms fed to shader programs.

**Files:**
- Create: `src/main/java/com/mitchellmarx/stereoscopic/mixin/iris/MixinCameraUniforms.java`
- Create: `src/main/java/com/mitchellmarx/stereoscopic/mixin/iris/MixinMatrixUniforms.java`
- Modify: `src/main/resources/stereoscopic-iris.mixins.json` (add 2 entries)

- [ ] **Step 1: Port both**

Same approach as Task 13.

- [ ] **Step 2: Register**

Add `"MixinCameraUniforms"`, `"MixinMatrixUniforms"` to the `client` array.

- [ ] **Step 3: Build + launch**

Run with shaders. Verify shader-side camera-relative effects (clouds, sky, fog, reflections) align per-eye.

- [ ] **Step 4: Commit**

```bash
git -C /c/CODE/Stereoscopic-Neoforge add src/main/java/com/mitchellmarx/stereoscopic/mixin/iris/Mixin{CameraUniforms,MatrixUniforms}.java src/main/resources/stereoscopic-iris.mixins.json
git -C /c/CODE/Stereoscopic-Neoforge commit -m "feat(mixin/iris): per-eye camera and matrix uniforms"
```

---

### Task 15: Iris pipeline mixins — `MixinIrisRenderingPipeline`, `MixinFinalPassRenderer`, `MixinShadowRenderer`, `MixinIrisLodRenderProgram`, `MixinDHCompat`

Pipeline lifecycle: rebuild on stereo mode change, single shadow pass, DH-shear restoration, LOD program coordination.

**Files:**
- Create: 5 new files in `src/main/java/com/mitchellmarx/stereoscopic/mixin/iris/`
- Modify: `src/main/resources/stereoscopic-iris.mixins.json` (add 5 entries)

- [ ] **Step 1: Port each of the 5 mixins**

Highest-risk re-rewrites: `MixinIrisRenderingPipeline` (the pipeline lifecycle root), `MixinDHCompat` (DH integration may live in a different module in Iris 1.8). Refer to Task 6's notes.

- [ ] **Step 2: Register all 5**

Update `stereoscopic-iris.mixins.json` to include `MixinIrisRenderingPipeline`, `MixinFinalPassRenderer`, `MixinShadowRenderer`, `MixinIrisLodRenderProgram`, `MixinDHCompat`.

- [ ] **Step 3: Build + launch with shaders**

Verify:
- Stereo mode toggle (`OFF` → `SBS_HALF`) triggers a one-frame pipeline rebuild (screen flashes once)
- Shadow rendering looks correct in both eyes (no doubled shadows, no halfway broken shadow map)
- No per-eye corruption in the final composite

DH testing happens in Phase 7 after the user installs DH NeoForge.

- [ ] **Step 4: Commit**

```bash
git -C /c/CODE/Stereoscopic-Neoforge add src/main/java/com/mitchellmarx/stereoscopic/mixin/iris/Mixin{IrisRenderingPipeline,FinalPassRenderer,ShadowRenderer,IrisLodRenderProgram,DHCompat}.java src/main/resources/stereoscopic-iris.mixins.json
git -C /c/CODE/Stereoscopic-Neoforge commit -m "feat(mixin/iris): pipeline rebuild, single shadow pass, DH shear, LOD prog"
```

---

## Phase 6 — Cursor + GUI (Tasks 16–17)

---

### Task 16: Cursor package — `CursorBackend`, `NoOpCursorBackend`, `WindowsCursorBackend`, `CursorPresentThread`, `StereoCursor`

The Win32 cursor-mirror backend uses LWJGL 3 + JNI; identical across Fabric and NeoForge.

**Files:**
- Create: `src/main/java/com/mitchellmarx/stereoscopic/cursor/CursorBackend.java`
- Create: `src/main/java/com/mitchellmarx/stereoscopic/cursor/NoOpCursorBackend.java`
- Create: `src/main/java/com/mitchellmarx/stereoscopic/cursor/WindowsCursorBackend.java`
- Create: `src/main/java/com/mitchellmarx/stereoscopic/cursor/CursorPresentThread.java`
- Modify: `src/main/java/com/mitchellmarx/stereoscopic/cursor/StereoCursor.java` (replace stub)

- [ ] **Step 1: Port each cursor file verbatim from Fabric**

Same package, same code. LWJGL 3 imports unchanged.

- [ ] **Step 2: Replace the `StereoCursor` stub with the real implementation**

The stub from Task 2 (`public static void start() {}`) gets replaced by the real Fabric file's contents (OS detection + backend instantiation + thread start).

- [ ] **Step 3: Launch + verify cursor mirror on Windows**

```bash
./gradlew runClient --no-daemon
```

In stereo mode, open an inventory or pause menu. Verify the cursor appears in BOTH eye-halves (this is what `WindowsCursorBackend`'s mirror thread achieves).

- [ ] **Step 4: Commit**

```bash
git -C /c/CODE/Stereoscopic-Neoforge add src/main/java/com/mitchellmarx/stereoscopic/cursor/
git -C /c/CODE/Stereoscopic-Neoforge commit -m "feat(cursor): port Windows cursor mirror + cross-platform backends"
```

---

### Task 17: Options UI — `StereoOptionsScreen` + keybinding

Sodium 0.6 has no third-party options-page API (that arrived in 0.7+). Instead of coupling to the modpack-specific `sodiumoptionsapi` add-on, ship a standalone Stereoscopic options screen accessed via a keybinding (`key.stereoscopic.options`, default unbound; user binds via Options → Controls). Implementation landed in commit `1f41baf`.


The in-game options UI. Registered through Sodium 0.6's options-API entry point.

**Files:**
- Create: `src/main/java/com/mitchellmarx/stereoscopic/gui/StereoOptionsPage.java`
- Create: `src/main/resources/META-INF/services/<sodium-config-entrypoint-fqn>` (file name determined in Step 1)

- [ ] **Step 1: Discover Sodium 0.6's options-API entry point mechanism**

Open the extracted Sodium 0.6.13 jar's `fabric.mod.json` (or `neoforge.mods.toml`) and search for entry-point hooks. Likely candidates:
- A service file `META-INF/services/net.caffeinemc.mods.sodium.client.gui.options.OptionGroup` or similar
- An annotation like `@SodiumOptionsExtension`
- A registered class enumerated through Sodium's mixin plugin

Document the exact mechanism in `docs/superpowers/notes/2026-05-29-sodium-iris-class-map.md` (append).

- [ ] **Step 2: Port `StereoOptionsPage`**

Read the Fabric `gui/StereoOptionsPage`. It uses Sodium's option-builder API (`OptionGroup.createBuilder()`, `OptionImpl`, etc.). Translate the same options (mode dropdown, IPD slider, convergence slider, swap-eyes checkbox) to the Sodium 0.6 API surface discovered in Step 1.

- [ ] **Step 3: Wire up the entry point**

Whichever mechanism Sodium 0.6 uses, register `StereoOptionsPage` so Sodium calls it during options-page assembly.

- [ ] **Step 4: Launch + verify**

```bash
./gradlew runClient --no-daemon
```

In-game: **Video Settings → Sodium → Stereoscopic** tab. Confirm:
- Mode dropdown shows `OFF` and `SBS_HALF`
- IPD slider range 55–75 mm, default 64 mm
- Convergence slider 0–32 blocks, default 4
- Swap-eyes checkbox

Changing values + applying writes to `run/config/stereoscopic-options.json`.

- [ ] **Step 5: Commit**

```bash
git -C /c/CODE/Stereoscopic-Neoforge add src/main/java/com/mitchellmarx/stereoscopic/gui/ src/main/resources/META-INF/services/
git -C /c/CODE/Stereoscopic-Neoforge commit -m "feat(gui): Sodium options page (Mode/IPD/Convergence/Swap)"
```

---

## Phase 7 — Third-party compat (Tasks 18–19)

User installs Voxy NeoForge and DH NeoForge into the test instance before these tasks. Sable compat goes in regardless.

---

### Task 18: Voxy compat — `VoxyEyeRebindHooks` — **DEFERRED to v0.1.x**

**Why deferred (2026-05-29):** No usable Voxy build with Iris support exists for NeoForge 1.21.1. The community port `j-shelfwood/voxy-neoforge` excludes the iris module from its jar (see `voxy-neoforge/build.gradle` source-set excludes). The Fabric upstream Voxy ships full iris support but would require Sinytra Connector at runtime, which is a larger user-side dependency. Re-enable this task when one of the following is sourced: (1) a voxy-neoforge build with iris module re-enabled, (2) a community NeoForge-native Voxy fork with iris, or (3) the user commits to Sinytra Connector and the Fabric Voxy + `IGetIrisVoxyPipelineData` namespace is verifiably preserved at runtime.

**Files:**
- Create: `src/main/java/com/mitchellmarx/stereoscopic/compat/voxy/VoxyEyeRebindHooks.java`

- [ ] **Step 1: Read the Fabric file**

`C:\CODE\Stereoscopic\src\main\java\com\mitchellmarx\stereoscopic\compat\voxy\VoxyEyeRebindHooks.java` is the spec.

- [ ] **Step 2: Identify the Voxy NeoForge build the user installed**

Look at the test instance `mods/` folder for `voxy-neoforge-*.jar`. Extract; find the rebind target class.

- [ ] **Step 3: Port the hook**

Same effect (rebind LOD render target per eye), Voxy-NeoForge-correct target class.

- [ ] **Step 4: Add a `ModList.isLoaded("voxy")` guard at the call site**

If Voxy isn't loaded, the hook is a no-op. Wrap the body or short-circuit at the entry.

- [ ] **Step 5: Build + launch + verify with Voxy enabled**

If the user has installed Voxy, fly above the world to where LOD chunks render. Verify both eye halves show LOD geometry aligned with full-detail terrain.

- [ ] **Step 6: Commit**

```bash
git -C /c/CODE/Stereoscopic-Neoforge add src/main/java/com/mitchellmarx/stereoscopic/compat/voxy/
git -C /c/CODE/Stereoscopic-Neoforge commit -m "feat(compat/voxy): per-eye LOD rebind hook"
```

---

### Task 19: Sable compat — `compat/sable/SableSubLevelEyeHooks`

Sable's `clip_overwrite/GameRendererMixin.sable$renderLevel` is also a `@WrapOperation` on `GameRenderer.render`'s `renderLevel` INVOKE — a real conflict with our `stereoscopic$twoPassRenderWorld`. Resolved by declaring `@Mixin(value = GameRenderer.class, priority = 2000)` on our `MixinGameRenderer` (Mixin default is 1000) so our wrap applies OUTSIDE Sable's. Result: each eye iteration runs the full Sable clip-overwrite logic; setup and teardown happen per eye (correct), not once across both. The `SableSubLevelEyeHooks` class is a Sable-presence flag for any future compat work to gate on.


**Files:**
- Create: `src/main/java/com/mitchellmarx/stereoscopic/compat/sable/SableSubLevelEyeHooks.java`

- [ ] **Step 1: Read Sable's sub-level render mixin**

Extract `sable-neoforge-1.21.1-1.2.2.jar` (already done if Task 6's extraction was kept). Open `dev/ryanhcode/sable/mixin/sublevel_render/LevelRendererMixin.class` and `BlockEntityRenderDispatcherMixin.class` via `javap -p -c` or by decompiling with CFR/Procyon. Document:
- Where in `LevelRenderer.renderLevel` the sub-level draw is injected
- Whether Sable rebinds any framebuffer or changes the active GL state during its draw

- [ ] **Step 2: Identify mixin-ordering conflicts**

For each of Sable's three `GameRendererMixin`s (`camera/new_camera_types/`, `entity/entities_turn_with_sub_levels/`, `clip_overwrite/`), check whether our `MixinGameRenderer` (from Task 7) targets the same method with overlapping `@At` slices. If yes, declare explicit `@Mixin(priority = ...)` on ours to control order.

- [ ] **Step 3: Implement `SableSubLevelEyeHooks`**

A small helper that:
- Captures the active per-eye colortex bank index just before Sable's sub-level draw begins (called from a hook in our existing world-render mixin or a new `LevelRendererMixin` priority-ordered after Sable's)
- Restores it after Sable's sub-level draw finishes
- This prevents Sable's sub-level reflections / TAA from binding the wrong eye's colortex bank

- [ ] **Step 4: Add a `ModList.isLoaded("sable")` guard**

Hook body short-circuits when Sable absent.

- [ ] **Step 5: Build + launch + verify in Aeronautics**

Stand near a flying Aeronautics ship (or build one). Set `SBS_HALF`. Under shaders + without shaders, verify:
- The sub-level ship has correct parallax (closer parts pop forward, farther parts recede) — both eyes see the ship at the same world position
- No mixin-ordering crash on world load
- No black-screen / colortex-corruption artifact when looking at the ship

- [ ] **Step 6: Commit**

```bash
git -C /c/CODE/Stereoscopic-Neoforge add src/main/java/com/mitchellmarx/stereoscopic/compat/sable/
git -C /c/CODE/Stereoscopic-Neoforge commit -m "feat(compat/sable): per-eye colortex bank around sub-level draws"
```

---

## Phase 8 — Polish + release (Tasks 20–21)

---

### Task 20: README

**Files:**
- Create: `C:\CODE\Stereoscopic-Neoforge\README.md`
- Create: `C:\CODE\Stereoscopic-Neoforge\docs\screenshot.png` (copy from Fabric repo when available)

- [ ] **Step 1: Mirror the Fabric mod's README structure with NeoForge-specific facts**

`README.md`:

```markdown
# StereoCraft: Stereoscopic 3D (NeoForge edition)

Side-by-side (SBS) stereoscopic 3D rendering for Minecraft **1.21.1** on **NeoForge**, with full per-eye support under **Sodium** and **Iris** shaders. Splits the Minecraft window into left and right eye views with a configurable interpupillary distance (IPD), producing a stereoscopic image for 3D TVs, 3D monitors, SBS-capable HMDs, and VR passthrough virtual monitors.

This is the NeoForge sibling of [StereoCraft Fabric](https://github.com/MitchellMarx/Stereoscopic) and [StereoCraft Angelica](https://github.com/MitchellMarx/Stereoscopic-Angelica) — same feature surface, ported to the NeoForge ecosystem.

## Requirements

- Minecraft **1.21.1**
- NeoForge **21.1.228** or compatible
- Java **21**
- [Sodium](https://modrinth.com/mod/sodium) **0.6.13** (hard dependency)

## Optional

- [Iris](https://modrinth.com/mod/iris) **1.8.12** for shader support — tested under EuphoriaPatcher (BSL / Complementary derivatives).
- [Distant Horizons](https://modrinth.com/mod/distanthorizons) — DH terrain alignment is supported per-eye under shaders.
- [Voxy](https://modrinth.com/mod/voxy) — LOD chunks per-eye.
- Reverse-SBS displays / VR passthrough virtual monitors — toggle **Swap eyes** in Sodium options.

## Installation

1. Install NeoForge 21.1.228+ for Minecraft 1.21.1.
2. Install Sodium 0.6.13 and (optionally) Iris 1.8.12 into your `mods/` folder.
3. Drop `stereoscopic-neoforge-vX.Y.Z.jar` into `mods/`.
4. Launch the game.

## Configuration

Open the in-game options menu and navigate to **Video Settings → Sodium → Stereoscopic**.

- **Mode** — `OFF`, `SBS_HALF`.
- **IPD (mm)** — 55–75 mm, default 64.
- **Convergence (blocks)** — 0–32, default 4. 0 = parallel-axis.
- **Swap eyes** — flips the per-eye assignment for reverse-SBS displays.

Settings persist at `config/stereoscopic-options.json` (same JSON format as the Fabric sibling).

## Compatibility

- **Iris shaderpacks**: EuphoriaPatcher and other BSL / Complementary derivatives tested.
- **Distant Horizons**: per-eye DH-projection shear restoration under shaders.
- **Voxy**: per-eye LOD rebind.
- **Sable** (Create Aeronautics sub-level physics): sub-levels render per-eye with correct parallax.
- **Create**: contraptions render per-eye through the normal `LevelRenderer` path.

## Known limitations

- **Async cursor presents Windows-only**: cursor mirror uses Win32 cursor APIs. Linux/macOS shows the cursor in only one eye while a GUI is open.
- **Iris pipeline rebuild on mode change**: one-frame flash when toggling `OFF ↔ SBS_HALF` while a shaderpack is loaded.
- **Sodium chunk-skip caveat**: at extreme IPD / convergence on a chunk boundary you may see a missing chunk in one eye for one frame. Sub-pixel at typical settings.

## License

LGPL-3.0-only. See [LICENSE](LICENSE).
```

- [ ] **Step 2: Commit**

```bash
git -C /c/CODE/Stereoscopic-Neoforge add README.md
git -C /c/CODE/Stereoscopic-Neoforge commit -m "docs: README with install / config / compat for v0.1.0"
```

---

### Task 21: Manual acceptance test pass + v0.1.0 tag

**Files:** none — this is a verification + tag step.

- [ ] **Step 1: Build the release jar**

```bash
cd /c/CODE/Stereoscopic-Neoforge && ./gradlew build --no-daemon
ls build/libs/
```

Expected: `stereoscopic-neoforge-0.1.0.jar`. Build hook should have copied it into the Aeronautics test instance's `mods/`.

- [ ] **Step 2: Run the full manual checklist in the Aeronautics instance**

Launch Aeronautics directly via CurseForge launcher. Test:

- [ ] `OFF` → `SBS_HALF` toggle (verify pipeline rebuild flash with shaders on)
- [ ] IPD slider: 55 → 64 → 75 mm
- [ ] Convergence: 0 (parallel) and 4 (off-axis) compared
- [ ] Swap eyes toggle
- [ ] HUD positioning in both halves
- [ ] Cursor in chat / inventory / pause menu (Windows: both eyes; non-Windows: one eye, documented)
- [ ] EuphoriaPatcher shaders on, then off
- [ ] DH on (after installing): terrain aligned per-eye under shaders
- [ ] Voxy on (after installing): LOD geometry per-eye
- [ ] Aeronautics ship / sub-level in view: parallax correct, no mixin-order crash, no eye-half misalignment (under and without shaders)
- [ ] Create contraption in motion (mechanical bearing rotation, train carriage, deployer chain) under stereo

Any failure here logs a follow-up bug task; do not tag v0.1.0 until the list is clean.

- [ ] **Step 3: Tag v0.1.0**

```bash
git -C /c/CODE/Stereoscopic-Neoforge tag -a v0.1.0 -m "Stereoscopic-NeoForge v0.1.0 — Aeronautics-ready"
git -C /c/CODE/Stereoscopic-Neoforge log --oneline -1
```

- [ ] **Step 4: Final commit summary**

If the checklist surfaced minor doc fixes, commit them and re-tag (`git tag -d v0.1.0 && git tag -a v0.1.0`).

---

## Self-review

**Spec coverage:**
- Target platform → Tasks 1, 2 (build + mods.toml pinning)
- Feature set (OFF/SBS_HALF, IPD, convergence, swap, HUD per-eye, cursor mirror) → Tasks 3–4 (core), 8 (HUD/world), 16 (cursor), 17 (options page)
- Iris shader compat → Tasks 12–15
- Voxy → Task 18
- DH → Task 15 (`MixinDHCompat`)
- Sable → Task 19
- Create (implicit) → Task 21 (manual check)
- EuphoriaPatcher → Tasks 13–15 (build under it), Task 21 (manual verify)
- Sodium options page (native API) → Task 17
- Error handling (Iris absent, Voxy/Sable absent, version mismatch) → Tasks 1 (mods.toml hard pins), 12/15/18/19 (`ModList.isLoaded` guards), Task 2 (`required: false` on Iris mixin config)
- Testing (JUnit + manual checklist) → Tasks 3–5, 10 (unit); Task 21 (manual)

**Placeholder scan:** No "TBD", "TODO", or unimplemented references. Each step has either exact code, exact commands, or a precise investigation instruction with named outputs.

**Type consistency:**
- `StereoOptions` stub in Task 2 has `load()` and `save()` methods; the real `StereoOptions` in Task 4 keeps the same method signatures (verified against Fabric source). ✓
- `StereoCursor` stub in Task 2 exposes `start()` (static, void); the real `StereoCursor` in Task 16 keeps the same. ✓
- `SecondEyeSkipHooks` introduced in Task 10 is referenced by `MixinSodiumWorldRenderer` in Task 11. ✓
- `PerEyeRenderTargetHooks` introduced in Task 12 is referenced by Iris colortex mixins in Tasks 13–15. ✓
- `StereoState.getActiveEye()` is referenced from mixins; the method is part of the verbatim port in Task 4. ✓

**Scope check:** The plan covers the entire spec in 21 tasks, each producing a self-contained, committable change. Each task either compiles + tests (Phases 1, 3, 4) or compiles + runs the game with a specific verifiable behavior (Phases 5, 6, 7).
