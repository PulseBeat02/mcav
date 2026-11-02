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
import org.gradle.api.logging.Logger
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CoverageLintTaskTest {

    private companion object {

        const val SOURCE_PATH: String = "sources/sample/Example.java"
        const val PACKAGE_SOURCE_PATH: String = "sample/Example.java"
        const val REPORT_FILE: String = "report.xml"
        const val EXCEPTIONS_FILE: String = "exceptions.txt"

        const val EXCEPTION_LINE: String = "sample/Example.java | serverOnly(); | server constructor\n"

        const val SOURCES_DIRECTORY: String = "sources"

        const val UNCOVERED_LINE_COUNTS: String = "mi=\"1\" ci=\"0\""
        const val COVERED_LINE_COUNTS: String = "mi=\"0\" ci=\"1\""
    }

    @TempDir
    lateinit var directory: Path

    @Test
    fun shouldSkipTheLintOnAFilteredTestRun(): Unit {
        val task: RecordedCoverageLintTask = this.lint(UNCOVERED_LINE_COUNTS)
        task.testsFiltered.set(true)
        task.lint()

        val messages: List<String> = task.recorder.messages
        val expected: String =
            "warn: Skipped coverageLint of :fixture, since the tests ran with a filter and the report covers only part of the code."
        assertEquals(listOf(expected), messages)
    }

    @Test
    fun shouldFailOnAnUncoveredLine(): Unit {
        val task: RecordedCoverageLintTask = this.lint(UNCOVERED_LINE_COUNTS)
        val failure: GradleException = assertThrows(GradleException::class.java) { task.lint() }
        val message: String? = failure.message
        assertEquals("1 lines are not fully covered by tests and 0 coverage exceptions are stale!", message)

        val sources: File = task.sourceDirectory.get().asFile
        val source: File = sources.resolve(PACKAGE_SOURCE_PATH)
        val messages: List<String> = task.recorder.messages
        assertEquals(listOf("error: $source:1: error: line is not covered by any test."), messages)
    }

    @Test
    fun shouldFailOnAnExceptionThatMatchesNoUncoveredLine(): Unit {
        val task: RecordedCoverageLintTask = this.lint(COVERED_LINE_COUNTS)
        this.directory.resolve(EXCEPTIONS_FILE).toFile().writeText(EXCEPTION_LINE)

        val failure: GradleException = assertThrows(GradleException::class.java) { task.lint() }
        val message: String? = failure.message
        assertEquals("0 lines are not fully covered by tests and 1 coverage exceptions are stale!", message)

        val exceptions: File = task.exceptionsFile.singleFile
        val messages: List<String> = task.recorder.messages
        val expected: String = "error: $exceptions: error: 'sample/Example.java | serverOnly();' matches no uncovered line, so remove it."
        assertEquals(listOf(expected), messages)
    }

    @Test
    fun shouldPassWhenAnExceptionExcusesTheOnlyUncoveredLine(): Unit {
        val task: CoverageLintTask = this.lint(UNCOVERED_LINE_COUNTS)
        this.directory.resolve(EXCEPTIONS_FILE).toFile().writeText(EXCEPTION_LINE)
        task.lint()
    }

    private fun lint(lineCounts: String): RecordedCoverageLintTask {
        val source: File = this.directory.resolve(SOURCE_PATH).toFile()
        source.parentFile.mkdirs()
        source.writeText("serverOnly();\n")

        val coverage: File = this.directory.resolve(REPORT_FILE).toFile()
        coverage.writeText(
            "<report name=\"fixture\"><package name=\"sample\"><sourcefile name=\"Example.java\">" +
                "<line nr=\"1\" $lineCounts mb=\"0\" cb=\"0\"/></sourcefile></package></report>"
        )

        val project: Project = ProjectBuilder.builder().withProjectDir(this.directory.toFile()).build()
        val sources: File = this.directory.resolve(SOURCES_DIRECTORY).toFile()
        val exceptions: File = this.directory.resolve(EXCEPTIONS_FILE).toFile()
        return project.tasks.register("coverageLint", RecordedCoverageLintTask::class.java).get().apply {
            this.report.from(coverage)
            this.sourceDirectory.set(sources)
            this.exceptionsFile.from(exceptions)
            this.testsFiltered.set(false)
            this.projectPath.set(":fixture")
        }
    }

    abstract class RecordedCoverageLintTask : CoverageLintTask() {

        val recorder: RecordingLogger = RecordingLogger()

        override fun getLogger(): Logger {
            return this.recorder.logger
        }
    }
}
