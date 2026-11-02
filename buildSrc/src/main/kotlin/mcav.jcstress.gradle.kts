/*
 * This file is part of mcav, a media playback library for Java
 * Copyright (C) Brandon Li <https://brandonli.me/>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

import me.brandonli.mcav.gradle.CatalogUtils
import me.brandonli.mcav.gradle.JcstressBudget
import me.brandonli.mcav.gradle.JcstressConsole
import me.brandonli.mcav.gradle.libs
import net.ltgt.gradle.errorprone.errorprone

plugins {
    id("mcav.java-library")
    id("mcav.formatting")
    id("mcav.lint")
}

val jcstressAlias: String = "jcstress-core"

val generatedSourcePattern: String = ".*/build/generated/.*"

val jcstressClassifier: String = "jcstress"
val jcstressMainClass: String = "org.openjdk.jcstress.Main"
val signatureFiles: List<String> = listOf("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")

val modeProperty: String = "jcstress.mode"
val budgetProperty: String = "jcstress.timeBudgetMinutes"
val selectionProperty: String = "jcstress.tests"
val cpusProperty: String = "jcstress.cpus"
val defaultMode: String = "quick"
val maximumDefaultCpus: Int = 4

val workingPath: String = "jcstress"
val resultsPath: String = "results"
val consolePath: String = "console.txt"

dependencies {
    implementation(CatalogUtils.libraryOf(libs, jcstressAlias))
    annotationProcessor(CatalogUtils.libraryOf(libs, jcstressAlias))
}

checkerFramework {
    skipCheckerFramework = true
}

tasks.named<JavaCompile>("compileJava") {
    this.options.compilerArgs.remove("-Werror")
    this.options.errorprone {
        this.excludedPaths = generatedSourcePattern
    }
}

val jcstressJar: TaskProvider<Jar> = tasks.register<Jar>("jcstressJar") {
    this.group = "build"
    this.description = "Packs the jcstress tests with everything they run"
    this.archiveClassifier = jcstressClassifier
    this.manifest {
        this.attributes("Main-Class" to jcstressMainClass)
    }
    this.from(sourceSets.main.get().output)

    val runtimeClasspath: NamedDomainObjectProvider<Configuration> = configurations.runtimeClasspath
    this.dependsOn(runtimeClasspath)
    this.from(runtimeClasspath.map { classpath -> classpath.map { if (it.isDirectory) it else zipTree(it) } }) {
        this.exclude(signatureFiles)
    }
    this.duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

val ITERATION_TIME: String = "Running {} jcstress tests in {} mode within {} minutes, at {} ms per iteration."
val mode: String = providers.gradleProperty(modeProperty).getOrElse(defaultMode)
val budgetMinutes: Provider<Int> = providers.gradleProperty(budgetProperty).map { it.toInt() }
val selection: Provider<String> = providers.gradleProperty(selectionProperty)
val defaultCpus: String = minOf(maximumDefaultCpus, Runtime.getRuntime().availableProcessors()).toString()
val cpus: String = providers.gradleProperty(cpusProperty).getOrElse(defaultCpus)

tasks.register<JavaExec>("jcstressTest") {
    this.group = "verification"
    this.description = "Runs the jcstress concurrency tests; -Pjcstress.mode, -Pjcstress.timeBudgetMinutes, -Pjcstress.tests"

    val jar: Provider<RegularFile> = jcstressJar.flatMap { it.archiveFile }
    this.inputs.file(jar)
    this.classpath = files(jar)
    this.mainClass = jcstressMainClass

    val workingDirectory: Provider<Directory> = layout.buildDirectory.dir(workingPath)
    this.workingDir = workingDirectory.get().asFile

    val arguments: MutableList<String> = mutableListOf("-m", mode, "-c", cpus, "-r", resultsPath, "-v")
    if (selection.isPresent) {
        arguments += listOf("-t", selection.get())
    }

    this.args(arguments)

    val budget: Int? = budgetMinutes.orNull
    val tests: String? = selection.orNull
    val cpuCount: Int = cpus.toInt()
    val stressMode: String = mode
    val iterationTime: String = ITERATION_TIME
    val console: Provider<RegularFile> = workingDirectory.map { it.file(consolePath) }
    this.doFirst {
        workingDirectory.get().asFile.mkdirs()
        this@register.standardOutput = JcstressConsole.tee(System.out, console.get().asFile)

        if (budget != null) {
            val testCount: Int = JcstressBudget.countTests(jar.get().asFile, tests)
            val iterationMilliseconds: Long = JcstressBudget.iterationMilliseconds(stressMode, budget, testCount, cpuCount)
            this.logger.lifecycle(iterationTime, testCount, stressMode, budget, iterationMilliseconds)
            this@register.args("-time", iterationMilliseconds.toString())
        }
    }
    this.doLast {
        this@register.standardOutput.close()
        JcstressConsole.checkNoneSkipped(console.get().asFile)
    }
    this.outputs.upToDateWhen { false }
}
