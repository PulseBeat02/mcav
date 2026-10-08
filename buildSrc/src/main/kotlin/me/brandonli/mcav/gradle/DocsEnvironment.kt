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

package me.brandonli.mcav.gradle

import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.LocalState
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault

fun uvEnvironment(installationDirectory: File, cacheDirectory: File): Map<String, String> = mapOf(
    "UV_NO_CONFIG" to "1",
    "UV_CACHE_DIR" to cacheDirectory.absolutePath,
    "UV_PYTHON_CACHE_DIR" to cacheDirectory.resolve("python").absolutePath,
    "UV_PYTHON_INSTALL_DIR" to installationDirectory.resolve("python").absolutePath,
    "UV_PYTHON_BIN_DIR" to installationDirectory.resolve("bin").absolutePath,
    "UV_PYTHON_PREFERENCE" to "only-managed",
    "UV_LINK_MODE" to "copy"
)

@DisableCachingByDefault(because = "Python virtual environments contain absolute installation paths")
abstract class DocsEnvironment : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val uvExecutable: RegularFileProperty

    @get:Input
    abstract val uvVersion: Property<String>

    @get:Input
    abstract val pythonVersion: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val requirements: RegularFileProperty

    @get:Input
    abstract val pinnedRequirements: MapProperty<String, String>

    @get:Input
    abstract val pythonPath: Property<String>

    @get:OutputDirectory
    abstract val installationDirectory: DirectoryProperty

    @get:LocalState
    abstract val cacheDirectory: DirectoryProperty

    @get:Inject
    protected abstract val processes: ExecOperations

    @TaskAction
    fun install() {
        val lock = requirements.get().asFile
        val missing = RequirementsLock.missingPins(lock.readText(), pinnedRequirements.get())
        if (missing.isNotEmpty()) {
            throw GradleException(
                "$lock does not pin ${missing.joinToString()} of gradle/libs.versions.toml; run ./gradlew :mcav-docs:lockDocsRequirements"
            )
        }
        val uv = uvExecutable.get().asFile
        val versionOutput = ByteArrayOutputStream()
        processes.exec {
            commandLine(uv, "--version")
            standardOutput = versionOutput
        }
        val reported = versionOutput.toString(Charsets.UTF_8).trim()
        if (reported.split(' ').getOrNull(1) != uvVersion.get()) {
            throw GradleException("$uv must be uv ${uvVersion.get()}, but it is $reported")
        }
        val installation = installationDirectory.get().asFile
        val environment = uvEnvironment(installation, cacheDirectory.get().asFile)
        val virtualEnvironment = installation.resolve("venv")
        val python = pythonVersion.get()
        listOf(
            listOf("python", "install", python, "--no-bin", "--no-registry"),
            listOf("venv", "--clear", "--no-project", "--no-python-downloads", "--python", python, virtualEnvironment),
            listOf("pip", "sync", "--python", virtualEnvironment.resolve(pythonPath.get()), "--require-hashes",
                "--only-binary", ":all:", "--default-index", "https://pypi.org/simple", lock)
        ).forEach { arguments ->
            processes.exec {
                commandLine(listOf(uv) + arguments)
                environment(environment)
            }
        }
    }
}
