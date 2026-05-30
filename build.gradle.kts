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

    // Voxy: deferred. No NeoForge build with an Iris module exists yet;
    // j-shelfwood/voxy-neoforge excludes iris from its jar. Re-add as
    // compileOnly when a usable Voxy + Iris combination is sourced.

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
