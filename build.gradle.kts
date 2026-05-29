import org.gradle.api.tasks.Copy

plugins {
    id("com.gtnewhorizons.gtnhconvention")
}

tasks.test {
    useJUnitPlatform()
}

// Expand ${version} in META-INF/rfb-plugin/*.properties. The gtnhconvention plugin's
// default processResources substitutes mcmod.info but not rfb-plugin descriptors;
// without this block RFB rejects the unparseable literal and the plugin never loads.
// Mirrored from Angelica-sbs2 build.gradle.kts.
tasks.processResources {
    val projectVersion = project.version.toString()
    inputs.property("version", projectVersion)
    filesMatching("META-INF/rfb-plugin/*") {
        expand("version" to projectVersion)
    }
}

// Path to a Prism Launcher GTNH profile's mods dir. Overridable via
// -Pstereoscopic.deployDir=<other-path> when testing on a different instance.
val testInstanceMods = file(
    (project.findProperty("stereoscopic.deployDir") as String?)
        ?: "C:/Users/felix/AppData/Roaming/PrismLauncher/instances/GTNH-daily-2026-05-17+520-mmcprism-java17-25/.minecraft/mods"
)

val copyToTestInstance by tasks.registering(Copy::class) {
    group = "stereoscopic"
    description = "Deploys the built jar into the Prism Launcher GTNH profile."
    dependsOn(tasks.named("reobfJar"))
    onlyIf {
        val ok = testInstanceMods.isDirectory
        if (!ok) logger.lifecycle("Skipping copyToTestInstance: $testInstanceMods not found")
        ok
    }
    // Pull the reobf'd, downgraded jar (un-suffixed). The `jar` task's outputs include
    // the -predowngrade variant which is Java 25 bytecode and fails Mixin's JAVA_8 class-version
    // check at runtime.
    from(tasks.named("reobfJar").map { it.outputs.files }) {
        include("stereoscopic-*.jar")
        exclude("*-predowngrade.jar", "*-dev.jar", "*-sources.jar")
    }
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
