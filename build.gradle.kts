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

// Stereo self-test (src/selftest): dev-only, never in the shipped jar.
// `./gradlew runSelftest` exits non-zero if any stereo check fails.
val selftest: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
configurations[selftest.compileClasspathConfigurationName].extendsFrom(configurations.compileClasspath.get())
configurations[selftest.runtimeClasspathConfigurationName].extendsFrom(configurations.runtimeClasspath.get())

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
        register("selftest") {
            client()
            gameDirectory = file("run-selftest")
            programArguments.addAll("--username", "Dev")
            systemProperty("stereoscopic.selftest", "true")
            // Shader pass of the self-test (skipped if the pack is absent).
            systemProperty("stereoscopic.test.shaderpack",
                (project.findProperty("stereoscopic.testShaderpack") as String?)
                    ?: "C:/Users/felix/AppData/Roaming/ModrinthApp/profiles/Fabric 26.1.2/shaderpacks/ComplementaryReimagined_r5.9.3.zip")
            sourceSet = selftest
        }
    }

    mods {
        register(project.property("mod_id") as String) {
            sourceSet(sourceSets.main.get())
            sourceSet(selftest)
        }
    }
}

neoForge.addModdingDependenciesTo(selftest)

dependencies {
    // Self-test LOD pass: Distant Horizons terrain beyond vanilla render
    // distance (-Pstereoscopic.noDh runs without).
    "selftestCompileOnly"("maven.modrinth:distanthorizons:${project.property("dh_version")}")
    if (!project.hasProperty("stereoscopic.noDh")) {
        "selftestRuntimeOnly"("maven.modrinth:distanthorizons:${project.property("dh_version")}")
    }
    // No Voxy pass: Voxy on NeoForge is the Fabric jar through Sinytra
    // Connector, which hooks FML's mod discovery as a boot-layer service; on
    // the dev runtime classpath FML just skips the Fabric jar.
}

// The client's exit status is unreliable (forced exits can die with
// 0xC0000409), so the self-test reports through a result file instead.
tasks.matching { it.name == "runSelftest" }.configureEach {
    val result = file("run-selftest/stereo-selftest-result.txt")
    (this as JavaExec).isIgnoreExitValue = true
    doFirst { result.delete() }
    doLast {
        val text = if (result.isFile) result.readText() else "no result file (client crashed before finishing)"
        if (!text.startsWith("PASS")) throw GradleException("Stereo self-test failed:\n$text")
        logger.lifecycle("Stereo self-test: PASS")
    }
}

dependencies {
    // Sodium NeoForge: required hard dep
    implementation("maven.modrinth:sodium:mc${project.property("minecraft_version")}-${project.property("sodium_version")}-neoforge")

    // Iris NeoForge: optional soft dep
    compileOnly("maven.modrinth:iris:${project.property("iris_version")}+${project.property("minecraft_version")}-neoforge")
    runtimeOnly("maven.modrinth:iris:${project.property("iris_version")}+${project.property("minecraft_version")}-neoforge")

    // Voxy: compile-only symbol source for the (disabled) VoxyEyeRebindHooks Iris-mode
    // rebind. The Fabric jar carries the me.cortex.voxy.client.iris.* classes that
    // voxy-neoforge excludes; NOT added at runtime (the hook is hard-disabled).
    compileOnly("maven.modrinth:voxy:${project.property("voxy_version")}")

    // SodiumOptionsAPI + Forgified Fabric API: optional soft deps from the
    // Aeronautics modpack (no Modrinth maven for SOAPI). SOAPI's OptionGUIConstruction.EVENT
    // is a net.fabricmc.fabric.api.event.Event which lives in forgified-fabric-api.
    val soapiJarPath = (project.findProperty("stereoscopic.soapiJar") as String?)
        ?: "C:/Users/felix/curseforge/minecraft/Instances/All of Create - Aeronautics (2)/mods/sodiumoptionsapi-neoforge-1.0.10-1.21.1.jar"
    // fabric-api-base is shipped jarjar'd inside forgified-fabric-api.
    // We extracted it once to libs/ so compileOnly can resolve net.fabricmc.fabric.api.event.Event
    // without unpacking the parent each build. The actual classes come from forgified-fabric-api
    // at runtime.
    val soapiJar = file(soapiJarPath)
    val fabricApiBaseJar = file("libs/fabric-api-base-0.4.42.jar")
    if (soapiJar.isFile) compileOnly(files(soapiJar))
        else logger.lifecycle("SOAPI compileOnly skipped: $soapiJarPath not found.")
    if (fabricApiBaseJar.isFile) compileOnly(files(fabricApiBaseJar))
        else logger.lifecycle("fabric-api-base compileOnly skipped: libs/fabric-api-base-0.4.42.jar not found.")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.joml:joml:${project.property("joml_version")}")
    testImplementation("com.google.code.gson:gson:${project.property("gson_version")}")
    testImplementation("org.slf4j:slf4j-api:${project.property("slf4j_version")}")
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
        ?: "C:/Users/felix/curseforge/minecraft/Instances/All of Create - Aeronautics (2)/mods"
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
