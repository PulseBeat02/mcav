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

import me.brandonli.mcav.gradle.javaLauncher

plugins {
    java
}

val benchmark = sourceSets.create("benchmark")

configurations.named(benchmark.implementationConfigurationName) {
    extendsFrom(configurations.testImplementation.get())
}

configurations.named(benchmark.runtimeOnlyConfigurationName) {
    extendsFrom(configurations.testRuntimeOnly.get())
}

dependencies {
    benchmark.implementationConfigurationName(sourceSets.main.get().output)
}

tasks.register<JavaExec>("browserBenchmark") {
    description = "Measures the frame rate and latency of a browser backend: -Pbenchmark.backend=<backend> -Pbenchmark.output=<file>"
    group = "verification"
    classpath = benchmark.runtimeClasspath
    mainClass = "me.brandonli.mcav.plugin.benchmark.BrowserBenchmark"
    val backend = providers.gradleProperty("benchmark.backend").orElse("")
    val output = providers.gradleProperty("benchmark.output").orElse(layout.buildDirectory.file("browser-benchmark.md").get().asFile.absolutePath)
    argumentProviders.add(CommandLineArgumentProvider { listOf(backend.get(), output.get()) })
    javaLauncher = javaLauncher()
    maxHeapSize = "4g"
    outputs.upToDateWhen { false }
}
