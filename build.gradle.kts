plugins {
    id("fabric-loom") version "1.16.2"
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
    maven("https://api.modrinth.com/maven") { name = "Modrinth" }
}

dependencies {
    minecraft("com.mojang:minecraft:${project.property("minecraft_version")}")
    mappings("net.fabricmc:yarn:${project.property("yarn_mappings")}:v2")
    modImplementation("net.fabricmc:fabric-loader:${project.property("loader_version")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${project.property("fabric_version")}")

    modImplementation("maven.modrinth:sodium:${project.property("sodium_version")}")
    modCompileOnly("maven.modrinth:iris:${project.property("iris_version")}")
    modRuntimeOnly("maven.modrinth:iris:${project.property("iris_version")}")
    // Iris's jar-in-jar libraries aren't put on the remapping dev runtime
    // classpath; without them Iris can't load a shader pack in runClient /
    // the gametest's shader pass (ClassNotFoundException org.anarres.cpp.*).
    runtimeOnly("org.anarres:jcpp:1.4.14")
    runtimeOnly("io.github.douira:glsl-transformer:3.0.0-pre3")
    runtimeOnly("org.antlr:antlr4-runtime:4.13.1")

    modCompileOnly("maven.modrinth:voxy:${project.property("voxy_version")}")

    modImplementation("io.github.llamalad7:mixinextras-fabric:0.5.4")

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
    // Gametest's LOD passes (Distant Horizons / Voxy terrain beyond vanilla
    // render distance); -Pstereoscopic.noDh / -Pstereoscopic.noVoxy run without.
    "modGametestCompileOnly"("maven.modrinth:distanthorizons:${project.property("dh_version")}")
    "modGametestCompileOnly"("maven.modrinth:voxy:${project.property("voxy_test_version")}")
    if (!project.hasProperty("stereoscopic.noDh")) {
        "modGametestRuntimeOnly"("maven.modrinth:distanthorizons:${project.property("dh_version")}")
    }
    if (!project.hasProperty("stereoscopic.noVoxy")) {
        "modGametestRuntimeOnly"("maven.modrinth:voxy:${project.property("voxy_test_version")}")
        // Voxy's jar-in-jar libraries, missing from the remapping dev runtime
        // like Iris's above (NoClassDefFoundError org.rocksdb.RocksDBException).
        "gametestRuntimeOnly"("org.rocksdb:rocksdbjni:10.2.1")
        "gametestRuntimeOnly"("org.apache.commons:commons-pool2:2.12.0")
        "gametestRuntimeOnly"("redis.clients:jedis:5.1.0")
        "gametestRuntimeOnly"("org.lz4:lz4-java:1.8.0")
        "gametestRuntimeOnly"("org.tukaani:xz:1.10")
        for (lib in listOf("lwjgl-lmdb", "lwjgl-zstd")) {
            "gametestRuntimeOnly"("org.lwjgl:$lib:3.3.3")
            "gametestRuntimeOnly"("org.lwjgl:$lib:3.3.3:natives-windows")
        }
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
    // Bounded heap: the default (1/4 of RAM) plus LOD mods' native memory has
    // exhausted the commit limit (native OOM in Sodium's chunk meshing).
    jvmArgs("-Xmx6G")
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
// Full stereo suite: `./gradlew stereoTest`. DH and Voxy each get their own
// client (both in one JVM runs out of memory); the Voxy client runs only the
// LOD passes, the DH client everything else too.
val gradlew = if (System.getProperty("os.name").startsWith("Windows")) "gradlew.bat" else "./gradlew"
val stereoTestDh by tasks.registering(Exec::class) {
    commandLine(file(gradlew).path, "runClientGameTest", "-Pstereoscopic.noVoxy")
}
val stereoTestVoxy by tasks.registering(Exec::class) {
    commandLine(file(gradlew).path, "runClientGameTest", "-Pstereoscopic.noDh", "-Pstereoscopic.lodOnly")
    mustRunAfter(stereoTestDh)
}
tasks.register("stereoTest") {
    group = "verification"
    description = "Stereo gametest: vanilla, shaders, Distant Horizons, Voxy"
    dependsOn(stereoTestDh, stereoTestVoxy)
}

loom {
    runs {
        named("client") {
            programArgs("--username", "Dev")
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
    options.release.set(21)
}

tasks.test { useJUnitPlatform() }

// --- Deploy hook ---
val testInstanceMods = file(
    (project.findProperty("stereoscopic.deployDir") as String?)
        ?: "C:/Users/felix/AppData/Roaming/ModrinthApp/profiles/Fall 2025 Let_s Play/mods"
)

val copyToTestInstance by tasks.registering(Copy::class) {
    group = "stereoscopic"
    description = "Deploys remapped jar into the Modrinth test instance"
    dependsOn(tasks.remapJar)
    onlyIf {
        val ok = testInstanceMods.isDirectory
        if (!ok) logger.lifecycle("Skipping copyToTestInstance: $testInstanceMods not found")
        ok
    }
    from(tasks.remapJar.flatMap { it.archiveFile })
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
