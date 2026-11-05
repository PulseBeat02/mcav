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
import java.nio.file.Path
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.process.ExecOperations
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PythonEnvironmentTest {

    private companion object {

        const val UV_EXECUTABLE: String = "uv"
        const val UV_VERSION_OUTPUT: String = "uv 0.12.23\n"

        const val PYTHON_VERSION: String = "3.13.16"

        const val PACKAGE_INDEX: String = "https://pypi.org/simple"

        const val PYTHON_DIRECTORY: String = "python"
        const val UV_CACHE_DIRECTORY: String = "uv-cache"
        const val LOCK_FILE: String = "requirements.lock"
        const val VIRTUAL_ENVIRONMENT_PYTHON: String = "bin/python"
        const val VIRTUAL_ENVIRONMENT_CLANG_FORMAT: String = "bin/clang-format"

        const val VIRTUAL_ENVIRONMENT_DIRECTORY: String = "venv"
    }

    @TempDir
    lateinit var directory: Path

    @Test
    fun shouldInstallThePinnedPythonIntoAFreshVirtualEnvironmentAndSyncItWithTheHashedLock(): Unit {
        val task: RecordingEnvironment = this.environment("numpy==2.5.3 \\\n    --hash=sha256:00\npytest==9.1.1 \\\n    --hash=sha256:11\n")
        val processes: RecordedProcesses = RecordedProcesses(UV_VERSION_OUTPUT)
        task.recordedProcesses = processes.operations
        task.install()

        val uv: String = this.directory.resolve(UV_EXECUTABLE).toString()
        val installation: File = this.directory.resolve(PYTHON_DIRECTORY).toFile()
        val virtualEnvironment: File = installation.resolve(VIRTUAL_ENVIRONMENT_DIRECTORY)
        val commands: List<List<String>> = processes.commands
        assertEquals(
            listOf(
                listOf(uv, "--version"),
                listOf(uv, "python", "install", PYTHON_VERSION, "--no-bin", "--no-registry"),
                listOf(uv, "venv", "--clear", "--no-project", "--no-python-downloads", "--python", PYTHON_VERSION, virtualEnvironment.path),
                listOf(uv, "pip", "sync", "--python", virtualEnvironment.resolve(VIRTUAL_ENVIRONMENT_PYTHON).path, "--require-hashes",
                    "--only-binary", ":all:", "--default-index", PACKAGE_INDEX,
                    this.directory.resolve(LOCK_FILE).toString())
            ),
            commands
        )

        val uvVariables: Map<String, String> = UvEnvironment.variables(installation, this.directory.resolve(UV_CACHE_DIRECTORY).toFile())
        val environments: List<Map<String, Any?>> = processes.environments
        assertEquals(listOf(emptyMap(), uvVariables, uvVariables, uvVariables), environments)

        val clangFormat: File = task.executable("clang-format").get().asFile
        assertEquals(virtualEnvironment.resolve(VIRTUAL_ENVIRONMENT_CLANG_FORMAT), clangFormat)
    }

    @Test
    fun shouldNameTheTaskThatWritesALockWithoutTheCatalogVersionsAndInstallNothing(): Unit {
        val task: RecordingEnvironment = this.environment("numpy==2.5.2 \\\n    --hash=sha256:00\n")
        val processes: RecordedProcesses = RecordedProcesses(UV_VERSION_OUTPUT)
        task.recordedProcesses = processes.operations

        val failure: GradleException = assertThrows(GradleException::class.java) { task.install() }
        val message: String? = failure.message
        assertEquals(
            "${this.directory.resolve(LOCK_FILE)} does not pin numpy==2.5.3, pytest==9.1.1 of gradle/libs.versions.toml, " +
                "so run ./gradlew :lockPythonRequirements to write it!",
            message
        )

        val commands: List<List<String>> = processes.commands
        assertEquals(emptyList<List<String>>(), commands)
    }

    private fun environment(lock: String): RecordingEnvironment {
        val project: Project = ProjectBuilder.builder().withProjectDir(this.directory.toFile()).build()
        val task: RecordingEnvironment = project.tasks.register(PythonEnvironment.TASK_NAME, RecordingEnvironment::class.java).get()
        val requirements: File = this.directory.resolve(LOCK_FILE).toFile()
        requirements.writeText(lock)
        task.uvExecutable.set(this.directory.resolve(UV_EXECUTABLE).toFile())
        task.uvVersion.set("0.12.23")
        task.pythonVersion.set(PYTHON_VERSION)
        task.requirements.set(requirements)
        task.versions.set(mapOf("numpy" to "2.5.3", "pytest" to "9.1.1"))
        task.lockTask.set(":lockPythonRequirements")
        task.installationDirectory.set(this.directory.resolve(PYTHON_DIRECTORY).toFile())
        task.cacheDirectory.set(this.directory.resolve(UV_CACHE_DIRECTORY).toFile())
        return task
    }

    abstract class RecordingEnvironment : PythonEnvironment() {

        lateinit var recordedProcesses: ExecOperations
        override val processes: ExecOperations
            get() {
                return this.recordedProcesses
            }
    }
}
