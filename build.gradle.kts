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

// Stereo self-test (src/selftest): a separate dev-only mod, never in the
// shipped jar. `./gradlew runClient25 -Pstereoscopic.selftest` runs it and
// fails unless run/client/stereo-selftest-result.txt reads PASS (the client's
// exit status is not a reliable verdict).
val selftest: SourceSet by sourceSets.creating {
    // RFG adds the patched Minecraft/Forge jars to main's classpath directly,
    // not via a configuration, so inherit main's resolved classpath.
    compileClasspath += sourceSets.main.get().output + sourceSets.main.get().compileClasspath
    runtimeClasspath += sourceSets.main.get().output
}
tasks.named<JavaCompile>(selftest.compileJavaTaskName) {
    // Same toolchain as main (pre-downgrade Java 25 bytecode); runs only on
    // the Java 25 dev client, so no downgrade step is needed.
    javaCompiler.set(tasks.named<JavaCompile>("compileJava").flatMap { it.javaCompiler })
}
if (project.hasProperty("stereoscopic.selftest")) {
    tasks.matching { it.name == "runClient25" }.configureEach {
        val run = this as JavaExec
        val result = file("run/client/stereo-selftest-result.txt")
        run.dependsOn(selftest.classesTaskName)
        run.classpath(selftest.output)
        run.systemProperty("stereoscopic.selftest", "true")
        // Shader passes (skipped if the pack is absent): the GTNH instance's pack.
        run.systemProperty("stereoscopic.test.shaderpack",
            (project.findProperty("stereoscopic.testShaderpack") as String?)
                ?: "C:/Users/felix/AppData/Roaming/PrismLauncher/instances/GTNH-daily-2026-05-17+520-mmcprism-java17-25/.minecraft/shaderpacks/ComplementaryReimagined_r5.7.1.zip")
        run.isIgnoreExitValue = true
        doFirst { result.delete() }
        doLast {
            val text = if (result.isFile) result.readText() else "no result file (client crashed before finishing)"
            if (!text.startsWith("PASS")) throw GradleException("Stereo self-test failed:\n$text")
            logger.lifecycle("Stereo self-test: PASS")
        }
    }
}
