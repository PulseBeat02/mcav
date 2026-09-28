// The test tiers of a module, whose task names CI calls. `test` runs the unit tests and every jqwik property with a few
// tries; `propertyTest` runs the properties (classes named *PropertyTest) with the real budget; `fuzzTest` replays the
// committed inputs of the Jazzer fuzz tests (classes named *FuzzTest, tagged "fuzz"), and fuzzes them on request. Every
// property pins its seed, so a run is reproducible and a failure reports the seed and the shrunk sample. The budgets
// are Gradle properties, so CI passes bigger numbers to the same tasks:
//   -Pproperty.tries=<n>          tries of every property that does not pin its own (default 200)
//   -Pproperty.smokeTries=<n>     the same during `test` (default 10)
//   -Pfuzz.seconds=<n>            seconds of coverage-guided fuzzing per fuzz test (default 0: only replay the inputs)
// Tests that need more than the build machine are opt-in:
//   -Pmcav.networkTests=true      the tests that download from the internet
//   -Pmcav.syncMeasurement=true   the measurements of how far sound drifts from picture, for a quiet machine
//   -Pmcav.testJavaHome=<JDK>     another JDK for the tests, as some JDK builds cannot load the bundled OpenCV natives

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
    // the tests load native libraries and attach Mockito's agent, which Java 24 and newer report unless allowed; the
    // agent appends to the boot class path, which turns class data sharing off with a warning, so it starts off; the
    // Paper API's libraries such as JOML still read memory through sun.misc.Unsafe
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
    // jqwik keeps the samples of failed properties in the build folder and replays none of them, so a result depends on
    // the code, the pinned seeds and the tries alone
    systemProperty("jqwik.database", "build/jqwik-database")
    systemProperty("jqwik.failures.after.default", "PREVIOUS_SEED")
}

tasks.test {
    // Jazzer puts an agent into the JVM that runs the fuzz tests, so they run in `fuzzTest` alone
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

// The replay of src/test/resources/<package>/<class>Inputs/<method>, the seed corpus and every crash reproducer, is
// deterministic and cached like any other test. Fuzzing depends on the clock, so it is never cached; it runs one JVM per
// test class, as Jazzer requires, and fails only when Jazzer finds a crash, which it writes to build/jazzer.
val fuzzTest = tasks.register<Test>("fuzzTest") {
    description = "Replays the committed fuzz inputs of this module, and fuzzes every fuzz test with -Pfuzz.seconds=<n>"
    group = "verification"
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath
    useJUnitPlatform {
        includeTags("fuzz")
    }
    // JUnit forks a JVM per scanned class to explore it, so only the fuzz test classes are scanned
    include("**/*FuzzTest.class")
    inputs.files(layout.projectDirectory.dir("src/test/resources"))
        .withPropertyName("fuzzInputs")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("fuzzSeconds", fuzzSeconds)
    outputs.dir(layout.buildDirectory.dir("jazzer")).withPropertyName("jazzerDirectory")
    outputs.cacheIf("only the replay of the committed inputs is deterministic") { fuzzSeconds == 0 }
    // relative, so the cache key does not depend on where the project lives; the JVM runs in the project folder
    systemProperty("jazzer.internal.basedir", "build/jazzer")
    // an input that runs longer than this is reported as a hang, both in the replay and while fuzzing
    systemProperty("junit.jupiter.execution.timeout.testtemplate.method.default", "30s")
    // a small heap turns an allocation sized from untrusted input into an OutOfMemoryError, which Jazzer reports
    maxHeapSize = "1g"
    if (fuzzSeconds > 0) {
        environment("JAZZER_FUZZ", "1")
        systemProperty("jazzer.max_duration", "${fuzzSeconds}s")
        // JUnit would apply the timeout to the whole fuzzing run; Jazzer hands it to libFuzzer, which applies it to
        // every input
        systemProperty("junit.jupiter.execution.timeout.mode", "disabled")
        forkEvery = 1
        outputs.upToDateWhen { false }
        // the fuzzer writes its progress straight to the standard error of the test JVM, which Gradle cannot tie to a
        // test, so the start of every input and fuzzing run is logged to tell whose progress follows
        testLogging {
            events("started", "failed", "skipped")
        }
    }
    mustRunAfter(propertyTest)
}

tasks.check {
    dependsOn(propertyTest, fuzzTest)
}
