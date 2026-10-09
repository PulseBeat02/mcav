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

import java.util.Properties
import me.brandonli.mcav.gradle.HostPlatform
import me.brandonli.mcav.gradle.DocsEnvironment
import me.brandonli.mcav.gradle.DownloadToolArchive
import me.brandonli.mcav.gradle.JupyterBook
import me.brandonli.mcav.gradle.ToolVersions
import me.brandonli.mcav.gradle.isWindows
import me.brandonli.mcav.gradle.libs
import me.brandonli.mcav.gradle.uvEnvironment
import me.brandonli.mcav.gradle.versionOf

plugins {
    base
}

val host = HostPlatform.current()
val uvTarget = host.uvTarget
val uvArchive = "uv-$uvTarget" + if (isWindows) ".zip" else ".tar.gz"
val uvPath = if (isWindows) "uv.exe" else "uv-$uvTarget/uv"
val uvChecksums = Properties().apply {
    DownloadToolArchive::class.java.getResourceAsStream("/uv-checksums.properties")!!.use { load(it) }
}
val downloadUv = tasks.register<DownloadToolArchive>("downloadDocsUv") {
    group = "build setup"
    description = "Downloads the pinned uv and verifies its SHA-256"
    archiveUrl = "https://github.com/astral-sh/uv/releases/download/${libs.versionOf("uv")}/$uvArchive"
    sha256 = provider {
        uvChecksums.getProperty(uvArchive)
            ?: throw GradleException("uv-checksums.properties pins no SHA-256 for $uvArchive; set UV=/path/to/uv to build the docs here")
    }
    executablePath = uvPath
    installationDirectory = layout.buildDirectory.dir("tools/uv")
}
val uvOverride = providers.environmentVariable("UV")
val selectedUv = if (uvOverride.isPresent) {
    layout.file(uvOverride.map { file(it) })
} else {
    downloadUv.flatMap { it.installationDirectory.file(uvPath) }
}

val toolVersions = objects.newInstance<ToolVersions>()
val pinnedUvVersion = libs.versionOf("uv")
val requirementsLock = layout.projectDirectory.file("requirements.lock")
val documentationRequirements = listOf("jupyter-book", "matplotlib", "numpy", "sphinx-design")
    .associateWith { libs.versionOf("docs-$it") }
val pythonInstallation = layout.buildDirectory.dir("python")
val uvCache = layout.buildDirectory.dir("uv-cache")

val installEnvironment = tasks.register<DocsEnvironment>("installDocsEnvironment") {
    group = "build setup"
    description = "Installs the pinned Python and the hash-locked documentation packages"
    mustRunAfter("lockDocsRequirements")
    uvExecutable = selectedUv
    uvVersion = pinnedUvVersion
    pythonVersion = libs.versionOf("docs-python")
    requirements = requirementsLock
    pinnedRequirements = documentationRequirements
    pythonPath = if (isWindows) "Scripts/python.exe" else "bin/python"
    installationDirectory = pythonInstallation
    cacheDirectory = uvCache
}
val buildDocs = tasks.register<JupyterBook>("buildDocs") {
    group = "documentation"
    description = "Builds the documentation HTML; a Sphinx warning fails it"
    dependsOn(installEnvironment)
    mustRunAfter("lockDocsRequirements")
    sources.from(fileTree(projectDir) {
        exclude("build/**", "_build/**", ".jupyter_cache/**", "**/__pycache__/**", "build.gradle.kts", "requirements*")
    })
    requirements = requirementsLock
    pythonVersion = libs.versionOf("docs-python")
    platform = uvTarget
    pythonExecutable = installEnvironment.flatMap { environment ->
        environment.installationDirectory.file(environment.pythonPath.map { "venv/$it" })
    }
    outputDirectory = layout.buildDirectory.dir("html")
}
tasks.named("assemble") {
    dependsOn(buildDocs)
}

tasks.register<Exec>("lockDocsRequirements") {
    group = "build setup"
    description = "Regenerates requirements.lock from the documentation versions in the catalog"
    val pythonVersion = libs.versionOf("docs-python")
    val requirementsInput = temporaryDir.resolve("requirements.in")
    val requirementLines = documentationRequirements.entries.joinToString("\n", postfix = "\n") { "${it.key}==${it.value}" }
    val lock = requirementsLock.asFile
    val licenseHeader = rootDir.resolve("HEADER").readLines().drop(1).dropLast(1)
        .joinToString("\n", postfix = "\n\n") { "#" + it.removePrefix(" *").trimEnd() }
    inputs.file(selectedUv)
    inputs.property("uv", pinnedUvVersion)
    inputs.property("python", pythonVersion)
    inputs.property("requirements", documentationRequirements)
    outputs.file(lock)
    executable(selectedUv.get().asFile)
    args("pip", "compile", requirementsInput, "--universal", "--generate-hashes", "--no-header", "--no-annotate",
        "--python", pythonVersion, "--python-version", pythonVersion.substringBeforeLast('.'), "--only-binary", ":all:",
        "--default-index", "https://pypi.org/simple", "--output-file", lock, "--quiet")
    environment(uvEnvironment(pythonInstallation.get().asFile, uvCache.get().asFile))
    doFirst {
        toolVersions.requireVersion(selectedUv.get().asFile, "uv", pinnedUvVersion)
        requirementsInput.writeText(requirementLines)
    }
    doLast {
        lock.writeText(licenseHeader + lock.readText())
    }
}
