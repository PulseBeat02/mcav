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

class ClangFormatTest {

    private companion object {

        const val CLANG_FORMAT_EXECUTABLE: String = "clang-format"
        const val VERSION_OUTPUT: String = "clang-format version 18.1.8\n"

        const val VERSION_OPTION: String = "--version"
        const val STYLE_OPTION: String = "--style=file"

        const val FIRST_SOURCE: String = "a.cpp"
        const val SECOND_SOURCE: String = "b.cpp"
    }

    @TempDir
    lateinit var directory: Path

    @Test
    fun shouldFailTheCheckOnEveryUnformattedLineWithoutChangingTheSources(): Unit {
        val processes: RecordedProcesses = RecordedProcesses(VERSION_OUTPUT)
        val task: RecordingFormat = this.createTask(processes)
        task.rewrite.set(false)
        task.runClangFormat()

        val commands: List<List<String>> = processes.commands
        assertEquals(
            listOf(
                listOf(this.clangFormat(), VERSION_OPTION),
                listOf(this.clangFormat(), "--dry-run", "--Werror", STYLE_OPTION) + this.sources()
            ),
            commands
        )
    }

    @Test
    fun shouldRewriteTheSourcesInPlace(): Unit {
        val processes: RecordedProcesses = RecordedProcesses(VERSION_OUTPUT)
        val task: RecordingFormat = this.createTask(processes)
        task.rewrite.set(true)
        task.runClangFormat()

        val expected: List<List<String>> =
            listOf(listOf(this.clangFormat(), VERSION_OPTION), listOf(this.clangFormat(), "-i", STYLE_OPTION) + this.sources())
        val commands: List<List<String>> = processes.commands
        assertEquals(expected, commands)
    }

    @Test
    fun shouldTouchNoSourceWithAnotherClangFormatVersion(): Unit {
        val processes: RecordedProcesses = RecordedProcesses("Ubuntu clang-format version 19.1.1\n")
        val task: RecordingFormat = this.createTask(processes)
        task.rewrite.set(true)

        val failure: GradleException = assertThrows(GradleException::class.java) { task.runClangFormat() }
        val message: String? = failure.message
        assertEquals(
            "${this.clangFormat()} reports Ubuntu clang-format version 19.1.1, which is not the clang-format 18.1.8 this build pins!",
            message
        )

        val commands: List<List<String>> = processes.commands
        assertEquals(listOf(listOf(this.clangFormat(), VERSION_OPTION)), commands)
    }

    private fun createTask(processes: RecordedProcesses): RecordingFormat {
        val project: Project = ProjectBuilder.builder().withProjectDir(this.directory.toFile()).build()
        val task: RecordingFormat = project.tasks.register("format", RecordingFormat::class.java).get()
        val files: List<File> = listOf(SECOND_SOURCE, FIRST_SOURCE).map { this.directory.resolve(it).toFile() }
        files.forEach { it.writeText("int main() {}\n") }
        task.clangFormat.set(this.directory.resolve(CLANG_FORMAT_EXECUTABLE).toFile())
        task.clangFormatVersion.set("18.1.8")
        task.sources.from(files)
        task.recordedProcesses = processes.operations
        return task
    }

    private fun clangFormat(): String {
        return this.directory.resolve(CLANG_FORMAT_EXECUTABLE).toString()
    }

    private fun sources(): List<String> {
        return listOf(FIRST_SOURCE, SECOND_SOURCE).map { this.directory.resolve(it).toString() }
    }

    abstract class RecordingFormat : ClangFormat() {

        lateinit var recordedProcesses: ExecOperations
        override val processes: ExecOperations
            get() {
                return this.recordedProcesses
            }
    }
}
