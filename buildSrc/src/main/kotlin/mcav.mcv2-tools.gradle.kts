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
import me.brandonli.mcav.gradle.HostPlatform
import me.brandonli.mcav.gradle.PythonEnvironment
import me.brandonli.mcav.gradle.PythonTests
import me.brandonli.mcav.gradle.libs

plugins {
    java
}

val lwjglAliases: List<String> = listOf("lwjgl", "lwjgl-shaderc", "lwjgl-spvc")

val toolsClasspathPath: String = "mcv2-tools-classpath.txt"

val pythonSourcePath: String = "src/test/python"
val pythonTestPattern: String = "test_*.py"
val timingTestFile: String = "test_shader_timing.py"
val pythonCachePattern: String = "**/__pycache__/**"
val testResourcePath: String = "src/test/resources/mcv2"
val packResourcePath: String = "src/main/resources/mcav/mcv2/pack"
val docsImagePath: String = "mcav-docs/images/mcv2"
val pythonReportPath: String = "test-results/pythonTest"

dependencies {
    val libraries: List<Provider<MinimalExternalModuleDependency>> = lwjglAliases.map { CatalogUtils.libraryOf(libs, it) }
    libraries.forEach { testImplementation(it) }
    HostPlatform.current().addLwjglTestNatives(dependencies, libraries)
}

val writeToolsClasspath: TaskProvider<Task> = tasks.register("writeMcv2ToolsClasspath") {
    this.group = "verification"
    this.description = "Writes the test runtime classpath that Mcv2ToolsTest starts Mcv2Tools with"

    val output: Provider<RegularFile> = layout.buildDirectory.file(toolsClasspathPath)
    val classpath: Provider<FileCollection> = sourceSets.test.map { it.runtimeClasspath }
    this.inputs.files(classpath)
    this.outputs.file(output)
    this.doLast {
        output.get().asFile.writeText(classpath.get().asPath)
    }
}

tasks.test {
    this.inputs.files(writeToolsClasspath)
}

pluginManager.withPlugin("info.solidsoft.pitest") {
    tasks.named("pitest") {
        this.inputs.files(writeToolsClasspath)
    }
}

val pythonEnvironment: TaskProvider<PythonEnvironment> = rootProject.tasks.named<PythonEnvironment>("installPythonEnvironment")
val pythonSources: Directory = layout.projectDirectory.dir(pythonSourcePath)
val pythonTest: TaskProvider<PythonTests> = tasks.register<PythonTests>("pythonTest") {
    this.group = "verification"
    this.description = "Runs the Python codec tests in parallel processes, then the timing tests alone"
    this.useEnvironment(pythonEnvironment)
    this.parallelTests.from(fileTree(pythonSources) {
        this.include(pythonTestPattern)
        this.exclude(timingTestFile)
    })
    this.serialTests.from(pythonSources.file(timingTestFile))
    this.sources.from(
        fileTree(pythonSources) { exclude(pythonTestPattern, pythonCachePattern) },
        layout.projectDirectory.dir(testResourcePath),
        layout.projectDirectory.dir(packResourcePath),
        layout.settingsDirectory.dir(docsImagePath)
    )
    this.testDirectory = pythonSources
    this.processCount = gradle.startParameter.maxWorkerCount
    this.reportDirectory = layout.buildDirectory.dir(pythonReportPath)
}

tasks.named("check") {
    this.dependsOn(pythonTest)
}
