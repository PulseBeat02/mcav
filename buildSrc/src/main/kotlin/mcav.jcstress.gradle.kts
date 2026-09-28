// Concurrency tests on jcstress, OpenJDK's harness that races a few actors over shared state millions of times.
// `./gradlew jcstressTest` runs every test in quick mode, -Pjcstress.tests=<regex> a selection; CI passes
// -Pjcstress.mode=quick|default|stress and -Pjcstress.timeBudgetMinutes=<n>, and -Pjcstress.cpus=<n> bounds the actors
// that run at once. A result a test marks FORBIDDEN makes jcstress exit with an error, which fails the task.

import me.brandonli.mcav.gradle.JcstressBudget
import me.brandonli.mcav.gradle.libraryOf
import me.brandonli.mcav.gradle.libs
import net.ltgt.gradle.errorprone.errorprone

plugins {
    id("mcav.java-library")
}

dependencies {
    implementation(libs.libraryOf("jcstress-core"))
    annotationProcessor(libs.libraryOf("jcstress-core"))
}

// The harness jcstress generates from the annotations is compiled with the tests and is not ours: it keeps neither
// every javac lint nor the nullness the checker wants, as it assigns null to fields the checker considers non-null.
checkerFramework {
    skipCheckerFramework = true
}

tasks.named<JavaCompile>("compileJava") {
    options.compilerArgs.remove("-Werror")
    options.errorprone {
        excludedPaths = ".*/build/generated/.*"
    }
}

// jcstress runs from one jar, which it also puts on the class path of the JVMs it forks. The annotation processor lists
// the tests in META-INF/TestList of the main output, so that output comes first and duplicates are dropped.
val jcstressJar = tasks.register<Jar>("jcstressJar") {
    description = "Packs the jcstress tests with everything they run"
    group = "build"
    archiveClassifier = "jcstress"
    manifest {
        attributes("Main-Class" to "org.openjdk.jcstress.Main")
    }
    from(sourceSets.main.get().output)
    // resolved when the jar is packed, after the jars of the modules under test were built
    val runtimeClasspath = configurations.runtimeClasspath
    dependsOn(runtimeClasspath)
    from(runtimeClasspath.map { classpath -> classpath.map { if (it.isDirectory) it else zipTree(it) } }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

// quick takes about a minute and a half per test here and surfaced every race the tests look for in every fork, while
// sanity is too short to surface a race at all; the CPU count bounds how many actors run at once, so the machine stays
// usable
val ITERATION_TIME = "jcstress: {} tests in {} mode within {} minutes: {} ms per iteration"
val mode = providers.gradleProperty("jcstress.mode").getOrElse("quick")
val budgetMinutes = providers.gradleProperty("jcstress.timeBudgetMinutes").map { it.toInt() }
val selection = providers.gradleProperty("jcstress.tests")
val cpus = providers.gradleProperty("jcstress.cpus").getOrElse(minOf(4, Runtime.getRuntime().availableProcessors()).toString())

tasks.register<JavaExec>("jcstressTest") {
    description = "Runs the jcstress concurrency tests; -Pjcstress.mode, -Pjcstress.timeBudgetMinutes, -Pjcstress.tests"
    group = "verification"
    val jar = jcstressJar.flatMap { it.archiveFile }
    inputs.file(jar)
    classpath = files(jar)
    mainClass = "org.openjdk.jcstress.Main"
    val workingDirectory = layout.buildDirectory.dir("jcstress")
    workingDir = workingDirectory.get().asFile
    val arguments = mutableListOf("-m", mode, "-c", cpus, "-r", "results", "-v")
    if (selection.isPresent) {
        arguments += listOf("-t", selection.get())
    }
    args(arguments)
    val budget = budgetMinutes.orNull
    val tests = selection.orNull
    val cpuCount = cpus.toInt()
    doFirst {
        workingDirectory.get().asFile.mkdirs()
        if (budget != null) {
            val testCount = JcstressBudget.countTests(jar.get().asFile, tests)
            val iterationMillis = JcstressBudget.iterationMillis(mode, budget, testCount, cpuCount)
            logger.lifecycle(ITERATION_TIME, testCount, mode, budget, iterationMillis)
            args("-time", iterationMillis.toString())
        }
    }
    // a stress run depends on the scheduling of the machine, so its result is never reused
    outputs.upToDateWhen { false }
}
