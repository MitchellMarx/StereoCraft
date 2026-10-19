plugins {
    // MC 26.x is unobfuscated: use the non-remapping Loom line, matching
    // FabricMC/fabric-example-mod @ 26.3.
    id("net.fabricmc.fabric-loom") version "1.18-SNAPSHOT"
    `java-library`
    `maven-publish`
}

version = project.property("mod_version") as String
group = project.property("maven_group") as String
base.archivesName.set(project.property("archives_base_name") as String)

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
    withSourcesJar()
}

repositories {
    mavenCentral()
    maven("https://api.modrinth.com/maven") { name = "Modrinth" }
}

dependencies {
    // No mappings() and no remapping: MC 26.1 ships unobfuscated with parameter
    // names, so mod deps are plain implementation/compileOnly/runtimeOnly.
    minecraft("com.mojang:minecraft:${project.property("minecraft_version")}")

    implementation("net.fabricmc:fabric-loader:${project.property("loader_version")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${project.property("fabric_version")}")

    implementation("maven.modrinth:sodium:${project.property("sodium_version")}")
    compileOnly("maven.modrinth:iris:${project.property("iris_version")}")
    runtimeOnly("maven.modrinth:iris:${project.property("iris_version")}")

    compileOnly("maven.modrinth:voxy:${project.property("voxy_version")}")
    compileOnly("maven.modrinth:distanthorizons:${project.property("dh_version")}")

    // MixinExtras is bundled inside Fabric Loader 0.19 at runtime; compileOnly
    // for the annotations (@WrapOperation, @ModifyReturnValue, ...).
    compileOnly("io.github.llamalad7:mixinextras-fabric:0.5.4")

    testImplementation("org.junit.jupiter:junit-jupiter:6.0.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Visual stereo harness (src/gametest): `./gradlew runClientGameTest`.
// Separate source set + mod id; never part of the shipped jar.
fabricApi {
    configureTests {
        createSourceSet = true
        modId = "stereoscopic-test"
        enableGameTests = false
        enableClientGameTests = true
        eula = true
    }
}
dependencies {
    // Gametest's LOD pass (Distant Horizons terrain beyond vanilla render
    // distance); -Pstereoscopic.noDh runs without. No Voxy build exists for 26.3.
    "gametestCompileOnly"("maven.modrinth:distanthorizons:${project.property("dh_version")}")
    if (!project.hasProperty("stereoscopic.noDh")) {
        "gametestRuntimeOnly"("maven.modrinth:distanthorizons:${project.property("dh_version")}")
    }
}
tasks.matching { it.name == "runClientGameTest" }.configureEach {
    // Shader pass of the stereo gametest (skipped if the pack is absent).
    (this as JavaExec).systemProperty("stereoscopic.test.shaderpack",
        (project.findProperty("stereoscopic.testShaderpack") as String?)
            ?: "C:/Users/felix/AppData/Roaming/ModrinthApp/profiles/Fabric 26.1.2/shaderpacks/ComplementaryReimagined_r5.9.3.zip")
    (project.findProperty("stereoscopic.testBisect") as String?)?.let { systemProperty("stereoscopic.test.bisect", it) }
    // StereoMixinAudit reads the transformed classes to prove every injector injected.
    systemProperty("mixin.debug.export", "true")
    // A loader error (e.g. incompatible test mods) exits instead of waiting on an error window.
    systemProperty("fabric.noGui", "true")
    // Verdict comes from the test's result file, not the exit status (the
    // test halts the JVM when DH is loaded; a forced halt exits 0xC0000409).
    val result = file("build/run/clientGameTest/stereo-test-result.txt")
    isIgnoreExitValue = true
    doFirst { result.delete() }
    doLast {
        val text = if (result.isFile) result.readText() else "no result file (client crashed before finishing)"
        if (!text.startsWith("PASS")) throw GradleException("Stereo test failed:\n$text")
        logger.lifecycle("Stereo test: PASS")
    }
    if (project.hasProperty("stereoscopic.probeTaa")) systemProperty("stereoscopic.test.probeTaa", "true")
    if (project.hasProperty("stereoscopic.lodOnly")) systemProperty("stereoscopic.test.lodOnly", "true")
    systemProperty("stereoscopic.test.knownBroken", (project.findProperty("test_known_broken") as String?) ?: "")
}
// Full stereo suite: `./gradlew stereoTest` — vanilla, shaders, Distant
// Horizons (one client; no Voxy build exists for 26.3).
tasks.register("stereoTest") {
    group = "verification"
    description = "Stereo gametest: vanilla, shaders, Distant Horizons"
    dependsOn("runClientGameTest")
}

loom {
    mods {
        register("stereoscopic") {
            sourceSet(sourceSets.main.get())
        }
    }
}

tasks.processResources {
    inputs.property("version", version)
    inputs.property("minecraft_version", project.property("minecraft_version") as String)
    inputs.property("loader_version", project.property("loader_version") as String)
    filteringCharset = "UTF-8"
    filesMatching("fabric.mod.json") {
        expand(
            "version" to version,
            "minecraft_version" to project.property("minecraft_version") as String,
            "loader_version" to project.property("loader_version") as String,
        )
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.test { useJUnitPlatform() }

// --- Deploy hook ---
// No remapJar in the unobfuscated build: the plain `jar` is the final mod jar.
val testInstanceMods = file(
    (project.findProperty("stereoscopic.deployDir") as String?)
        // Dedicated 26.3 profile; copy is skipped if it does not exist, so this
        // never clobbers the 26.2 jar in the in-place-upgraded "Fabric 26.1.2" profile.
        ?: "C:/Users/felix/AppData/Roaming/ModrinthApp/profiles/Fabric 26.3/mods"
)

val copyToTestInstance by tasks.registering(Copy::class) {
    group = "stereoscopic"
    description = "Deploys the built jar into the Modrinth test instance"
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
            name.startsWith("stereoscopic-") && name.endsWith(".jar")
        }?.forEach { f ->
            if (!f.delete()) logger.warn("Could not delete stale jar $f (locked? close Modrinth launcher)")
        }
    }
}

tasks.build { dependsOn(copyToTestInstance) }
