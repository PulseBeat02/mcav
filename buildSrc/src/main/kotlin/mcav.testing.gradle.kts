import me.brandonli.mcav.gradle.bundleOf
import me.brandonli.mcav.gradle.javaExecutable
import me.brandonli.mcav.gradle.libraryOf
import me.brandonli.mcav.gradle.libs

plugins {
    java
}

dependencies {
    testImplementation(platform(libs.libraryOf("junit-bom")))
    testImplementation(libs.bundleOf("testing"))
    testRuntimeOnly(libs.libraryOf("junit-platform-launcher"))
}

val networkTests = providers.gradleProperty("mcav.networkTests").getOrElse("false")
val syncMeasurement = providers.gradleProperty("mcav.syncMeasurement").getOrElse("false")
val testJavaHome = providers.gradleProperty("mcav.testJavaHome")
val propertyTries = providers.gradleProperty("property.tries").getOrElse("200")
val propertySmokeTries = providers.gradleProperty("property.smokeTries").getOrElse("10")
val fuzzSeconds = providers.gradleProperty("fuzz.seconds").map { it.toInt() }.getOrElse(0)
val testSourceSet = sourceSets.test.get()

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    failOnNoDiscoveredTests = false
    maxHeapSize = "2g"
    jvmArgs(
        "--enable-native-access=ALL-UNNAMED",
        "-XX:+EnableDynamicAgentLoading",
        "-Xshare:off",
        "--sun-misc-unsafe-memory-access=allow"
    )
    systemProperty("mcav.networkTests", networkTests)
    systemProperty("mcav.syncMeasurement", syncMeasurement)
    if (testJavaHome.isPresent) {
        executable = javaExecutable(testJavaHome.get())
    }
    systemProperty("jqwik.database", "build/jqwik-database")
    systemProperty("jqwik.failures.after.default", "PREVIOUS_SEED")
}

tasks.test {
    useJUnitPlatform {
        excludeTags("fuzz")
    }
    systemProperty("jqwik.tries.default", propertySmokeTries)
}

val propertyTest = tasks.register<Test>("propertyTest") {
    description = "Runs the jqwik property tests of this module"
    group = "verification"
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath
    useJUnitPlatform {
        includeEngines("jqwik")
    }
    systemProperty("jqwik.tries.default", propertyTries)
    mustRunAfter(tasks.test)
}

val fuzzTest = tasks.register<Test>("fuzzTest") {
    description = "Replays the committed fuzz inputs of this module, and fuzzes every fuzz test with -Pfuzz.seconds=<n>"
    group = "verification"
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath
    useJUnitPlatform {
        includeTags("fuzz")
    }
    include("**/*FuzzTest.class")
    inputs.files(layout.projectDirectory.dir("src/test/resources"))
        .withPropertyName("fuzzInputs")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("fuzzSeconds", fuzzSeconds)
    outputs.dir(layout.buildDirectory.dir("jazzer")).withPropertyName("jazzerDirectory")
    outputs.cacheIf("only the replay of the committed inputs is deterministic") { fuzzSeconds == 0 }
    systemProperty("jazzer.internal.basedir", "build/jazzer")
    systemProperty("junit.jupiter.execution.timeout.testtemplate.method.default", "30s")
    maxHeapSize = "1g"
    if (fuzzSeconds > 0) {
        environment("JAZZER_FUZZ", "1")
        systemProperty("jazzer.max_duration", "${fuzzSeconds}s")
        systemProperty("junit.jupiter.execution.timeout.mode", "disabled")
        forkEvery = 1
        outputs.upToDateWhen { false }
        testLogging {
            events("started", "failed", "skipped")
        }
    }
    mustRunAfter(propertyTest)
}

tasks.check {
    dependsOn(propertyTest, fuzzTest)
}
