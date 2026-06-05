plugins {
    // MC 26.1 is unobfuscated: use the non-remapping Loom line (1.16-SNAPSHOT),
    // matching FabricMC/fabric-example-mod @ 26.1.2. The old fabric-loom /
    // 1.17.0-alpha line still expects (now non-existent) Mojang mappings.
    id("net.fabricmc.fabric-loom") version "1.16-SNAPSHOT"
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

    // MixinExtras is bundled inside Fabric Loader 0.19 at runtime; compileOnly
    // for the annotations (@WrapOperation, @ModifyReturnValue, ...).
    compileOnly("io.github.llamalad7:mixinextras-fabric:0.5.4")

    testImplementation("org.junit.jupiter:junit-jupiter:6.0.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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
        ?: "C:/Users/felix/AppData/Roaming/ModrinthApp/profiles/Fabric 26.1.2/mods"
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
