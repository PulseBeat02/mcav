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

import me.brandonli.mcav.gradle.BuildMcv2Natives
import me.brandonli.mcav.gradle.CatalogUtils
import me.brandonli.mcav.gradle.ClangFormat
import me.brandonli.mcav.gradle.DownloadToolArchive
import me.brandonli.mcav.gradle.HostPlatform
import me.brandonli.mcav.gradle.PythonEnvironment
import me.brandonli.mcav.gradle.Tools
import me.brandonli.mcav.gradle.libs

plugins {
    java
}

val zigTool: String = "zig"
val clangFormatTool: String = "clang-format"
val clangFormatAlias: String = "python-clang-format"

val windowsSystem: String = "windows"
val zipExtension: String = ".zip"
val xzTarExtension: String = ".tar.xz"
val executableExtension: String = ".exe"
val zigDownloadUrl: String = "https://ziglang.org/download"
val zigOverrideChecksum: String = "ZIG override"

val nativeSourcePath: String = "src/main/native/mcv2"
val nativeSourcePattern: String = "*.cpp"
val zigToolsPath: String = "tools/zig"
val zigCachePath: String = "tools/zig/cache"
val generatedNativesPath: String = "generated/natives"

val nativeResourcesConfiguration: String = "mcv2NativeResources"
val directoryArtifactType: String = "directory"

val host: HostPlatform = HostPlatform.current()
val system: String = host.zigSystem
val architecture: String = host.architecture
val pinnedZigVersion: String = CatalogUtils.versionOf(libs, zigTool)
val zigFolder: String = "$zigTool-$architecture-$system-$pinnedZigVersion"
val zigArchive: String = zigFolder + if (system == windowsSystem) zipExtension else xzTarExtension
val zigPath: String = "$zigFolder/$zigTool" + if (system == windowsSystem) executableExtension else ""
val zigChecksum: Provider<String> = Tools.pinnedSha256(project, zigTool, pinnedZigVersion, zigArchive)

val downloadZig: TaskProvider<DownloadToolArchive> = tasks.register<DownloadToolArchive>("downloadZig") {
    this.group = "build setup"
    this.description = "Downloads the pinned Zig and verifies its SHA-256"
    this.archiveUrl = "$zigDownloadUrl/$pinnedZigVersion/$zigArchive"
    this.sha256 = zigChecksum
    this.executablePath = zigPath
    this.installationDirectory = layout.buildDirectory.dir("$zigToolsPath/$pinnedZigVersion")
}

val nativeSources: Directory = layout.projectDirectory.dir(nativeSourcePath)
val buildMcv2Natives: TaskProvider<BuildMcv2Natives> = tasks.register<BuildMcv2Natives>("buildMcv2Natives") {
    this.group = "build"
    this.description = "Compiles MCV2's six native libraries from source"
    this.sourcesDirectory = nativeSources
    this.zigExecutable = Tools.selectTool(project, zigTool, downloadZig.flatMap { it.installationDirectory.file(zigPath) })
    this.zigVersion = pinnedZigVersion
    this.toolchainChecksum = Tools.toolOverride(project, zigTool).map { zigOverrideChecksum }.orElse(zigChecksum)
    this.outputDirectory = layout.buildDirectory.dir(generatedNativesPath)
    this.cacheDirectory = layout.buildDirectory.dir(zigCachePath)
    this.processCount = gradle.startParameter.maxWorkerCount
}

sourceSets.main {
    resources.srcDir(buildMcv2Natives)
}

val nativeResources: NamedDomainObjectProvider<Configuration> = configurations.register(nativeResourcesConfiguration) {
    isCanBeConsumed = true
    isCanBeResolved = false
}
artifacts.add(nativeResources.name, buildMcv2Natives.flatMap { it.outputDirectory }) {
    builtBy(buildMcv2Natives)
    type = directoryArtifactType
}

val pinnedClangFormatVersion: String = CatalogUtils.versionOf(libs, clangFormatAlias)
val pythonEnvironment: TaskProvider<PythonEnvironment> = rootProject.tasks.named<PythonEnvironment>("installPythonEnvironment")
val installedClangFormat: Provider<RegularFile> = pythonEnvironment.flatMap { it.executable(clangFormatTool) }
val selectedClangFormat: Provider<RegularFile> = Tools.selectTool(project, clangFormatTool, installedClangFormat)
val formattedSources: ConfigurableFileTree = fileTree(nativeSources) { include(nativeSourcePattern) }

tasks.register<ClangFormat>("formatMcv2Natives") {
    this.group = "formatting"
    this.description = "Formats the MCV2 native sources with clang-format $pinnedClangFormatVersion"
    this.sources.from(formattedSources)
    this.clangFormat = selectedClangFormat
    this.clangFormatVersion = pinnedClangFormatVersion
    this.rewrite = true
}

val checkNativesFormat: TaskProvider<ClangFormat> = tasks.register<ClangFormat>("checkMcv2NativesFormat") {
    this.group = "verification"
    this.description = "Checks that the MCV2 native sources are formatted with clang-format $pinnedClangFormatVersion"
    this.sources.from(formattedSources)
    this.clangFormat = selectedClangFormat
    this.clangFormatVersion = pinnedClangFormatVersion
    this.rewrite = false
}

tasks.named("check") {
    this.dependsOn(checkNativesFormat)
}
