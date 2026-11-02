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

import java.io.File
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.LocalState
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault

/**
 * Installs a Python virtual environment from a requirements lock with uv.
 *
 * The task checks that the lock pins every version of the catalog, installs the pinned Python as a uv-managed build and
 * syncs the virtual environment to the lock, with hashed binary packages only.
 */
@DisableCachingByDefault(because = "Python virtual environments contain absolute installation paths")
abstract class PythonEnvironment : DefaultTask() {

    companion object {

        /**
         * The name under which `mcav.python` registers this task in the root project.
         */
        const val TASK_NAME: String = "installPythonEnvironment"

        private const val VIRTUAL_ENVIRONMENT_DIRECTORY: String = "venv"
        private const val PATH_SEPARATOR: String = "/"
    }

    /**
     * The uv executable that installs the environment.
     */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val uvExecutable: RegularFileProperty

    /**
     * The uv version that [uvExecutable] must report.
     */
    @get:Input
    abstract val uvVersion: Property<String>

    /**
     * The Python version of the environment.
     */
    @get:Input
    abstract val pythonVersion: Property<String>

    /**
     * The requirements lock that the environment is synced to.
     */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val requirements: RegularFileProperty

    /**
     * The version of each requirement that the catalog pins, which the lock must pin too.
     */
    @get:Input
    abstract val versions: MapProperty<String, String>

    /**
     * The path of the task that writes the lock, for the failure message of a stale lock.
     */
    @get:Internal
    abstract val lockTask: Property<String>

    /**
     * The directory that receives Python and the virtual environment.
     */
    @get:OutputDirectory
    abstract val installationDirectory: DirectoryProperty

    /**
     * The uv cache directory.
     */
    @get:LocalState
    abstract val cacheDirectory: DirectoryProperty

    /**
     * The service that runs uv.
     */
    @get:Inject
    protected abstract val processes: ExecOperations

    /**
     * Returns an executable of the virtual environment.
     *
     * @param name the name of the executable, such as `python`
     * @return the executable, in `venv/Scripts` on Windows and `venv/bin` elsewhere
     */
    fun executable(name: String): Provider<RegularFile> {
        val executablePath: String = HostPlatform.current().virtualEnvironmentExecutable(name)
        return this.installationDirectory.file("$VIRTUAL_ENVIRONMENT_DIRECTORY$PATH_SEPARATOR$executablePath")
    }

    /**
     * Checks the lock and the uv version, installs Python and syncs the virtual environment to the lock.
     *
     * @throws GradleException if the lock misses a pinned version, uv reports another version or an installation step fails
     */
    @TaskAction
    fun install(): Unit {
        val lock: File = this.requirements.get().asFile
        val missing: List<String> = RequirementsLock.missingPins(lock.readText(), this.versions.get())
        if (missing.isNotEmpty()) {
            val lockTaskPath: String = this.lockTask.get()
            val message: String =
                "$lock does not pin ${missing.joinToString()} of gradle/libs.versions.toml, so run ./gradlew $lockTaskPath to write it!"
            throw GradleException(message)
        }

        val uv: File = this.uvExecutable.get().asFile
        val version: String = this.uvVersion.get()
        ToolVersions.verify(this.processes, uv, UvEnvironment.TOOL, version)

        val python: String = this.pythonVersion.get()
        val virtualEnvironment: File = this.installationDirectory.get().asFile.resolve(VIRTUAL_ENVIRONMENT_DIRECTORY)
        this.runUv(uv, listOf("python", "install", python, "--no-bin", "--no-registry"))
        this.runUv(uv, listOf("venv", "--clear", "--no-project", "--no-python-downloads", "--python", python, virtualEnvironment))
        this.syncPackages(uv, virtualEnvironment, lock)
    }

    private fun runUv(uv: File, arguments: List<Any>): Unit {
        val installation: File = this.installationDirectory.get().asFile
        val cache: File = this.cacheDirectory.get().asFile
        val environment: Map<String, String> = UvEnvironment.variables(installation, cache)
        this.processes.exec {
            this.commandLine(listOf(uv) + arguments)
            this.environment(environment)
        }
    }

    private fun syncPackages(uv: File, virtualEnvironment: File, lock: File): Unit {
        val pythonPath: String = HostPlatform.current().virtualEnvironmentExecutable(PythonTask.PYTHON_EXECUTABLE)
        val virtualEnvironmentPython: File = virtualEnvironment.resolve(pythonPath)
        val arguments: List<Any> = listOf(
            "pip", "sync", "--python", virtualEnvironmentPython, "--require-hashes",
            "--only-binary", ":all:", "--default-index", UvEnvironment.PACKAGE_INDEX, lock
        )
        this.runUv(uv, arguments)
    }
}
