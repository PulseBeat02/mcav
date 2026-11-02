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
import me.brandonli.mcav.gradle.DownloadToolArchive
import me.brandonli.mcav.gradle.HostPlatform
import me.brandonli.mcav.gradle.LockPythonRequirements
import me.brandonli.mcav.gradle.PythonEnvironment
import me.brandonli.mcav.gradle.RequirementsLock
import me.brandonli.mcav.gradle.Tools
import me.brandonli.mcav.gradle.libs

val uvTool: String = "uv"
val pythonAlias: String = "python"
val pythonPackageAliasPrefix: String = "python-"
val pythonPackages: List<String> =
    listOf("clang-format", "jupyter-book", "matplotlib", "moderngl", "numpy", "pillow", "pytest", "pytest-xdist", "sphinx-design")

val uvReleasesUrl: String = "https://github.com/astral-sh/uv/releases/download"
val zipExtension: String = ".zip"
val gzipTarExtension: String = ".tar.gz"
val windowsUvExecutable: String = "uv.exe"

val requirementsLockPath: String = "requirements.lock"
val uvToolsPath: String = "tools/uv"
val pythonToolsPath: String = "tools/python"
val uvCachePath: String = "tools/uv/cache"

val licenseHeaderPath: String = "HEADER"

val host: HostPlatform = HostPlatform.current()
val uvTarget: String = host.uvTarget
val pinnedUvVersion: String = CatalogUtils.versionOf(libs, uvTool)
val pinnedPythonVersion: String = CatalogUtils.versionOf(libs, pythonAlias)
val pinnedPackages: Map<String, String> = pythonPackages.associateWith { CatalogUtils.versionOf(libs, "$pythonPackageAliasPrefix$it") }
val packageMarkers: Map<String, String> = mapOf(
    "clang-format" to "sys_platform != 'win32' or platform_machine != 'ARM64'",
    "moderngl" to "platform_machine != 'aarch64' and platform_machine != 'ARM64'"
)
val uvArchive: String = "$uvTool-$uvTarget" + if (host.isWindows) zipExtension else gzipTarExtension
val uvPath: String = if (host.isWindows) windowsUvExecutable else "$uvTool-$uvTarget/$uvTool"

val downloadUv: TaskProvider<DownloadToolArchive> = tasks.register<DownloadToolArchive>("downloadUv") {
    this.group = "build setup"
    this.description = "Downloads the pinned uv and verifies its SHA-256"
    this.archiveUrl = "$uvReleasesUrl/$pinnedUvVersion/$uvArchive"
    this.sha256 = Tools.pinnedSha256(project, uvTool, pinnedUvVersion, uvArchive)
    this.executablePath = uvPath
    this.installationDirectory = layout.buildDirectory.dir("$uvToolsPath/$pinnedUvVersion")
}

val selectedUv: Provider<RegularFile> = Tools.selectTool(project, uvTool, downloadUv.flatMap { it.installationDirectory.file(uvPath) })
val requirementsLock: RegularFile = layout.projectDirectory.file(requirementsLockPath)
val pythonInstallation: Provider<Directory> = layout.buildDirectory.dir("$pythonToolsPath/$pinnedPythonVersion")
val uvCache: Provider<Directory> = layout.buildDirectory.dir(uvCachePath)
val licenseHeaderFile: RegularFile = layout.settingsDirectory.file(licenseHeaderPath)
val lockHeader: Provider<String> = providers.fileContents(licenseHeaderFile).asText.map(RequirementsLock::headerOf)

val lockRequirements: TaskProvider<LockPythonRequirements> = tasks.register<LockPythonRequirements>("lockPythonRequirements") {
    this.group = "build setup"
    this.description = "Regenerates requirements.lock from the Python versions in the catalog"
    this.uvExecutable = selectedUv
    this.uvVersion = pinnedUvVersion
    this.pythonVersion = pinnedPythonVersion
    this.versions = pinnedPackages
    this.markers = packageMarkers
    this.licenseHeader = lockHeader
    this.lockFile = requirementsLock
    this.installationDirectory = pythonInstallation
    this.cacheDirectory = uvCache
}

tasks.register<PythonEnvironment>("installPythonEnvironment") {
    this.group = "build setup"
    this.description = "Installs the pinned Python and the hash-locked packages of requirements.lock"
    this.mustRunAfter(lockRequirements)
    this.uvExecutable = selectedUv
    this.uvVersion = pinnedUvVersion
    this.pythonVersion = pinnedPythonVersion
    this.requirements = requirementsLock
    this.versions = pinnedPackages
    this.lockTask = lockRequirements.map { it.path }
    this.installationDirectory = pythonInstallation
    this.cacheDirectory = uvCache
}
