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

class LockPythonRequirementsTest {

    private companion object {

        const val NUMPY: String = "numpy"
        const val NUMPY_VERSION: String = "2.5.3"
        const val CLANG_FORMAT: String = "clang-format"
        const val CLANG_FORMAT_VERSION: String = "18.1.8"

        const val PYTHON_VERSION: String = "3.13.16"

        const val PACKAGE_INDEX: String = "https://pypi.org/simple"

        const val UV_EXECUTABLE: String = "uv"
        const val PYTHON_DIRECTORY: String = "python"
        const val UV_CACHE_DIRECTORY: String = "uv-cache"
        const val LOCK_FILE: String = "requirements.lock"

        const val REQUIREMENTS_INPUT_FILE: String = "requirements.in"
    }

    @TempDir
    lateinit var directory: Path

    @Test
    fun shouldSortTheRequirementsByNameAndKeepTheMarkersOfPackagesWithoutWheelsEverywhere(): Unit {
        val marker: String = "sys_platform != 'win32' or platform_machine != 'ARM64'"
        val lines: List<String> = LockPythonRequirements.requirementLines(
            mapOf("pytest" to "9.1.1", NUMPY to NUMPY_VERSION, CLANG_FORMAT to CLANG_FORMAT_VERSION),
            mapOf(CLANG_FORMAT to marker)
        )
        assertEquals(listOf("clang-format==18.1.8 ; $marker", "numpy==2.5.3", "pytest==9.1.1"), lines)
    }

    @Test
    fun shouldCompileAUniversalHashedLockOfWheelsForThePythonMinorBelowTheLicenseHeader(): Unit {
        val task: RecordingLock = this.lockTask()
        val lock: File = this.directory.resolve(LOCK_FILE).toFile()
        val requirementsInput: File = task.temporaryDir.resolve(REQUIREMENTS_INPUT_FILE)
        val processes: RecordedProcesses =
            RecordedProcesses("uv 0.12.23\n") { this.compileLock(it, requirementsInput, lock) }
        task.recordedProcesses = processes.operations
        task.lock()

        val uv: String = this.directory.resolve(UV_EXECUTABLE).toString()
        val commands: List<List<String>> = processes.commands
        assertEquals(
            listOf(
                listOf(uv, "--version"),
                listOf(uv, "pip", "compile", requirementsInput.path, "--universal", "--generate-hashes", "--no-header",
                    "--no-annotate", "--python", PYTHON_VERSION, "--python-version", "3.13", "--only-binary", ":all:",
                    "--default-index", PACKAGE_INDEX, "--output-file", lock.path, "--quiet")
            ),
            commands
        )

        val environment: Map<String, Any?> = processes.environments[1]
        val installation: File = this.directory.resolve(PYTHON_DIRECTORY).toFile()
        val cache: File = this.directory.resolve(UV_CACHE_DIRECTORY).toFile()
        assertEquals(UvEnvironment.variables(installation, cache), environment)

        val locked: String = lock.readText()
        assertEquals("# License header\n\nnumpy==2.5.3 \\\n    --hash=sha256:00\n", locked)
    }

    @Test
    fun shouldLeaveTheLockAsItIsWithAnUvOfAnotherVersion(): Unit {
        val task: RecordingLock = this.lockTask()
        val lock: File = this.directory.resolve(LOCK_FILE).toFile()
        lock.writeText("numpy==2.5.2\n")

        val processes: RecordedProcesses = RecordedProcesses("uv 0.12.22\n")
        task.recordedProcesses = processes.operations

        val failure: GradleException = assertThrows(GradleException::class.java) { task.lock() }
        val message: String? = failure.message
        assertEquals("${this.directory.resolve(UV_EXECUTABLE)} reports uv 0.12.22, which is not the uv 0.12.23 this build pins!", message)

        val commandCount: Int = processes.commands.size
        assertEquals(1, commandCount)

        val locked: String = lock.readText()
        assertEquals("numpy==2.5.2\n", locked)
    }

    private fun lockTask(): RecordingLock {
        val project: Project = ProjectBuilder.builder().withProjectDir(this.directory.toFile()).build()
        val task: RecordingLock = project.tasks.register("lockPythonRequirements", RecordingLock::class.java).get()
        task.uvExecutable.set(this.directory.resolve(UV_EXECUTABLE).toFile())
        task.uvVersion.set("0.12.23")
        task.pythonVersion.set(PYTHON_VERSION)
        task.versions.set(mapOf(NUMPY to NUMPY_VERSION, CLANG_FORMAT to CLANG_FORMAT_VERSION))
        task.markers.set(mapOf(CLANG_FORMAT to "platform_machine != 'ARM64'"))
        task.licenseHeader.set("# License header\n\n")
        task.lockFile.set(this.directory.resolve(LOCK_FILE).toFile())
        task.installationDirectory.set(this.directory.resolve(PYTHON_DIRECTORY).toFile())
        task.cacheDirectory.set(this.directory.resolve(UV_CACHE_DIRECTORY).toFile())
        return task
    }

    private fun compileLock(command: List<String>, requirementsInput: File, lock: File): Unit {
        if (command[1] != "pip") {
            return
        }

        val requirements: String = requirementsInput.readText()
        assertEquals("clang-format==18.1.8 ; platform_machine != 'ARM64'\nnumpy==2.5.3\n", requirements)

        lock.writeText("numpy==2.5.3 \\\n    --hash=sha256:00\n")
    }

    abstract class RecordingLock : LockPythonRequirements() {

        lateinit var recordedProcesses: ExecOperations

        override val processes: ExecOperations
            get() {
                return this.recordedProcesses
            }
    }
}
