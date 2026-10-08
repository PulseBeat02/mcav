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

import java.util.Locale
import java.util.Properties
import me.brandonli.mcav.gradle.BuildMcv2Natives
import me.brandonli.mcav.gradle.DownloadToolArchive
import me.brandonli.mcav.gradle.libs
import me.brandonli.mcav.gradle.versionOf

plugins {
    java
}

val operatingSystem = System.getProperty("os.name").lowercase(Locale.ROOT)
val system = when {
    operatingSystem.contains("windows") -> "windows"
    operatingSystem.contains("mac") -> "macos"
    operatingSystem.contains("linux") -> "linux"
    else -> operatingSystem
}
val architecture = when (val hostArchitecture = System.getProperty("os.arch")) {
    "amd64", "x86_64" -> "x86_64"
    "aarch64", "arm64" -> "aarch64"
    else -> hostArchitecture
}
val pinnedZigVersion = libs.versionOf("zig")
val zigFolder = "zig-$architecture-$system-$pinnedZigVersion"
val zigArchive = zigFolder + if (system == "windows") ".zip" else ".tar.xz"
val zigPath = "$zigFolder/zig" + if (system == "windows") ".exe" else ""
val zigChecksums = Properties().apply {
    DownloadToolArchive::class.java.getResourceAsStream("/zig-checksums.properties")!!.use { load(it) }
}
val zigChecksum = provider {
    zigChecksums.getProperty(zigArchive)
        ?: throw GradleException("No pinned Zig archive for $architecture-$system; set ZIG=/path/to/zig $pinnedZigVersion")
}
val downloadZig = tasks.register<DownloadToolArchive>("downloadMcv2Zig") {
    group = "build setup"
    description = "Downloads the pinned Zig compiler and verifies its SHA-256"
    archiveUrl = "https://ziglang.org/download/$pinnedZigVersion/$zigArchive"
    sha256 = zigChecksum
    executablePath = zigPath
    installationDirectory = layout.buildDirectory.dir("tools/zig/$pinnedZigVersion")
}
val zigOverride = providers.environmentVariable("ZIG")
val selectedZig = if (zigOverride.isPresent) {
    layout.file(zigOverride.map { file(it) })
} else {
    downloadZig.flatMap { it.installationDirectory.file(zigPath) }
}
val nativeSources = layout.projectDirectory.dir("src/main/native/mcv2")
val buildMcv2Natives = tasks.register<BuildMcv2Natives>("buildMcv2Natives") {
    group = "build"
    description = "Compiles MCV2's six native libraries from source"
    sourcesDirectory = nativeSources
    zigExecutable = selectedZig
    zigVersion = pinnedZigVersion
    toolchainChecksum = if (zigOverride.isPresent) provider { "ZIG override" } else zigChecksum
    outputDirectory = layout.buildDirectory.dir("generated/natives")
    cacheDirectory = layout.buildDirectory.dir("zig-cache")
}
sourceSets.main {
    resources.srcDir(buildMcv2Natives)
}

tasks.register<Exec>("formatMcv2Natives") {
    group = "formatting"
    description = "Formats the MCV2 native sources with clang-format ${libs.versionOf("clang-format")}"
    workingDir = nativeSources.asFile
    commandLine(listOf(providers.environmentVariable("CLANG_FORMAT").getOrElse("clang-format"), "-i", "--style=file") +
        fileTree(nativeSources) { include("*.cpp") }.files.sorted().map { it.absolutePath })
}
