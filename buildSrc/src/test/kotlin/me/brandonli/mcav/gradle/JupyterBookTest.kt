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
import org.gradle.api.Action
import org.gradle.process.ExecOperations
import org.gradle.process.ExecResult
import org.gradle.process.ExecSpec
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.RETURNS_SELF

class JupyterBookTest {

    @TempDir
    lateinit var directory: Path

    abstract class RecordingBook : JupyterBook() {
        lateinit var recordedProcesses: ExecOperations
        override val processes: ExecOperations get() = recordedProcesses
    }

    @Test
    fun generatedConfigurationPrecedesTheHtmlBuildAndOnlyHtmlLeavesTheTask() {
        val project = ProjectBuilder.builder().withProjectDir(directory.toFile()).build()
        val task = project.tasks.create("book", RecordingBook::class.java)
        val source = directory.resolve("source").toFile()
        source.mkdirs()
        source.resolve("_config.yml").writeText("title: Fixture book\n")
        source.resolve("conf.py").writeText("html_title = 'Stale title'\n")
        task.sources.from(project.fileTree(source))
        task.pythonExecutable.set(directory.resolve("managed-python").toFile())
        task.outputDirectory.set(directory.resolve("html").toFile())
        val commands = mutableListOf<List<String>>()
        val processes = mock(ExecOperations::class.java)
        val expectedConfiguration = "html_title = 'Fixture book'\n"
        val expectedHtml = "<!doctype html><title>Fixture book</title>\n"
        doAnswer { invocation ->
            val specification = mock(ExecSpec::class.java, RETURNS_SELF)
            invocation.getArgument<Action<ExecSpec>>(0).execute(specification)
            val calls = mockingDetails(specification).invocations
            val command = (calls.single { it.method.name == "commandLine" }.rawArguments[0] as Iterable<*>).map { it.toString() }
            val staging = calls.single { it.method.name == "workingDir" }.rawArguments[0] as File
            val arguments = command.drop(3)
            commands += arguments
            val book = staging.resolve("book")
            assertEquals("title: Fixture book\n", book.resolve("_config.yml").readText())
            if (arguments.first() == "config") {
                book.resolve("conf.py").writeText(expectedConfiguration)
            } else {
                assertEquals(expectedConfiguration, book.resolve("conf.py").readText())
                val html = staging.resolve("site/_build/html")
                html.mkdirs()
                html.resolve("index.html").writeText(expectedHtml)
                val epub = staging.resolve("site/_build/epub")
                epub.mkdirs()
                epub.resolve("book.epub").writeText("not an HTML output")
            }
            mock(ExecResult::class.java)
        }.`when`(processes).exec(any())
        task.recordedProcesses = processes
        task.build()
        assertEquals(listOf(
            listOf("config", "sphinx", "book"),
            listOf("build", "book", "--path-output", "site", "--warningiserror", "--keep-going", "--all")
        ), commands)
        assertEquals(expectedHtml, task.outputDirectory.get().asFile.resolve("index.html").readText())
        assertFalse(task.outputDirectory.get().asFile.resolve("conf.py").exists())
        assertFalse(task.outputDirectory.get().asFile.resolve("book.epub").exists())
    }
}
