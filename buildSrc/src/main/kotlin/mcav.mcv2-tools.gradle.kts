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


import me.brandonli.mcav.gradle.libraryOf
import me.brandonli.mcav.gradle.libs

plugins {
    java
}

val operatingSystem = System.getProperty("os.name").lowercase()
val architecture = System.getProperty("os.arch").lowercase()
val arm64 = architecture == "aarch64" || architecture == "arm64"
val x64 = architecture == "amd64" || architecture == "x86_64"
val nativeClassifier = when {
    operatingSystem.contains("mac") || operatingSystem.contains("darwin") -> when {
        arm64 -> "natives-macos-arm64"
        x64 -> "natives-macos"
        else -> null
    }
    operatingSystem.contains("win") -> when {
        arm64 -> "natives-windows-arm64"
        x64 -> "natives-windows"
        else -> null
    }
    operatingSystem.contains("linux") -> when {
        arm64 -> "natives-linux-arm64"
        x64 -> "natives-linux"
        else -> null
    }
    else -> null
}

dependencies {
    listOf("lwjgl", "lwjgl-shaderc", "lwjgl-spvc").forEach { name ->
        val library = libs.libraryOf(name)
        testImplementation(library)
        nativeClassifier?.let { classifier ->
            testRuntimeOnly(variantOf(library) { classifier(classifier) })
        }
    }
}

tasks.register("writeMcv2ToolsClasspath") {
    val output = layout.buildDirectory.file("mcv2-tools-classpath.txt")
    val classpath = sourceSets.test.get().runtimeClasspath
    dependsOn(tasks.testClasses)
    inputs.files(classpath)
    outputs.file(output)
    doLast {
        output.get().asFile.writeText(classpath.asPath)
    }
}
