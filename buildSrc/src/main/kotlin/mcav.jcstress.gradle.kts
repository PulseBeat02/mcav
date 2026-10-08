import me.brandonli.mcav.gradle.JcstressBudget
import me.brandonli.mcav.gradle.JcstressConsole
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

checkerFramework {
    skipCheckerFramework = true
}

tasks.named<JavaCompile>("compileJava") {
    options.compilerArgs.remove("-Werror")
    options.errorprone {
        excludedPaths = ".*/build/generated/.*"
    }
}

val jcstressJar = tasks.register<Jar>("jcstressJar") {
    description = "Packs the jcstress tests with everything they run"
    group = "build"
    archiveClassifier = "jcstress"
    manifest {
        attributes("Main-Class" to "org.openjdk.jcstress.Main")
    }
    from(sourceSets.main.get().output)
    val runtimeClasspath = configurations.runtimeClasspath
    dependsOn(runtimeClasspath)
    from(runtimeClasspath.map { classpath -> classpath.map { if (it.isDirectory) it else zipTree(it) } }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

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
    val console = workingDirectory.map { it.file("console.txt") }
    doFirst {
        workingDirectory.get().asFile.mkdirs()
        standardOutput = JcstressConsole.tee(System.out, console.get().asFile)
        if (budget != null) {
            val testCount = JcstressBudget.countTests(jar.get().asFile, tests)
            val iterationMillis = JcstressBudget.iterationMillis(mode, budget, testCount, cpuCount)
            logger.lifecycle(ITERATION_TIME, testCount, mode, budget, iterationMillis)
            args("-time", iterationMillis.toString())
        }
    }
    doLast {
        standardOutput.close()
        JcstressConsole.checkNoneSkipped(console.get().asFile)
    }
    outputs.upToDateWhen { false }
}
