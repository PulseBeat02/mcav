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
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

/**
 * Runs the pytest suites of a Python project: first the parallel tests, spread over processes by file, then the serial
 * tests.
 *
 * Each suite writes a JUnit XML report to [reportDirectory]. The tests run headless, with a fixed hash seed and without
 * the user's site packages or pytest options.
 */
@CacheableTask
abstract class PythonTests : PythonTask() {

    private companion object {

        const val PARALLEL_REPORT: String = "parallel.xml"
        const val SERIAL_REPORT: String = "serial.xml"

        const val PYTEST_OPTIONS_VARIABLE: String = "PYTEST_ADDOPTS"
        const val NO_OPTIONS: String = ""

        const val MATPLOTLIB_BACKEND_VARIABLE: String = "MPLBACKEND"
        const val HEADLESS_BACKEND: String = "Agg"
    }

    /**
     * The test files that may run in parallel.
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val parallelTests: ConfigurableFileCollection

    /**
     * The test files that must run one at a time.
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val serialTests: ConfigurableFileCollection

    /**
     * The sources that the tests check.
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    /**
     * The root directory of the tests, which they run in.
     */
    @get:Internal
    abstract val testDirectory: DirectoryProperty

    /**
     * The number of processes that the parallel tests are spread over.
     */
    @get:Internal
    abstract val processCount: Property<Int>

    /**
     * The directory that receives the `parallel.xml` and `serial.xml` reports.
     */
    @get:OutputDirectory
    abstract val reportDirectory: DirectoryProperty

    /**
     * The service that clears the report directory.
     */
    @get:Inject
    protected abstract val files: FileSystemOperations

    /**
     * The service that runs pytest.
     */
    @get:Inject
    protected abstract val processes: ExecOperations

    /**
     * Runs the parallel tests, then the serial tests.
     *
     * @throws GradleException if a test fails
     */
    @TaskAction
    fun test(): Unit {
        val reports: File = this.reportDirectory.get().asFile
        this.files.delete { this.delete(reports) }

        val parallel: List<String> = this.pathsOf(this.parallelTests)
        val forks: String = minOf(this.processCount.get(), parallel.size).toString()
        val distribution: List<String> = listOf("-n", forks, "--dist", "loadfile")
        this.runPytest(reports.resolve(PARALLEL_REPORT), distribution + parallel)

        val serial: List<String> = this.pathsOf(this.serialTests)
        this.runPytest(reports.resolve(SERIAL_REPORT), serial)
    }

    private fun pathsOf(tests: ConfigurableFileCollection): List<String> {
        val files: List<File> = tests.files.sorted()
        return files.map { it.path }
    }

    private fun runPytest(report: File, arguments: List<String>): Unit {
        val directory: File = this.testDirectory.get().asFile
        val python: String = this.pythonExecutable.get().asFile.path
        val matplotlibConfiguration: String = this.temporaryDir.resolve(MATPLOTLIB_CONFIGURATION_DIRECTORY).path
        val command: List<String> =
            listOf(python, "-m", "pytest", "-p", "no:cacheprovider", "-ra", "--rootdir", directory.path, "--junitxml", report.path)
        this.processes.exec {
            this.workingDir(directory)
            this.commandLine(command + arguments)
            this.environment(NO_USER_SITE_VARIABLE, ENABLED)
            this.environment(NO_BYTECODE_VARIABLE, ENABLED)
            this.environment(HASH_SEED_VARIABLE, FIXED_HASH_SEED)
            this.environment(PYTEST_OPTIONS_VARIABLE, NO_OPTIONS)
            this.environment(MATPLOTLIB_BACKEND_VARIABLE, HEADLESS_BACKEND)
            this.environment(MATPLOTLIB_CONFIGURATION_VARIABLE, matplotlibConfiguration)
        }
    }
}
