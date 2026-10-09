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

import java.nio.file.Path
import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class JavaLintTaskTest {

    @TempDir
    lateinit var directory: Path

    @Test
    fun eachLintKeepsItsExactSuccessReport() {
        val project = ProjectBuilder.builder().withProjectDir(directory.toFile()).build()
        val source = directory.resolve("Example.java").toFile()
        source.writeText("final class Example {}\n")
        val tasks = listOf(
            project.tasks.create("logs", LogMessagesTask::class.java) to "no log call with an inline message in 1 files\n",
            project.tasks.create("qualified", QualifiedNamesTask::class.java) to "no fully qualified type names in 1 files\n",
            project.tasks.create("variables", VariableNamesTask::class.java) to "no undescriptive variable name in 1 files\n"
        )
        tasks.forEach { (task, expected) ->
            task.sources.from(source)
            task.report.set(directory.resolve(task.name + ".txt").toFile())
            if (task is VariableNamesTask) task.publishedNames.set(emptySet())
            task.check()
            assertEquals(expected, task.report.get().asFile.readText())
        }
    }

    @Test
    fun eachLintCountsViolationsAndKeepsItsFailureMessage() {
        val project = ProjectBuilder.builder().withProjectDir(directory.toFile()).build()
        val tasks = listOf(
            Triple(project.tasks.create("logs", LogMessagesTask::class.java), "final class Example { void run() { logger.info(\"inline\"); logger.error(\"inline\"); } }", "2 log calls with an inline message"),
            Triple(project.tasks.create("qualified", QualifiedNamesTask::class.java), "final class Example { java.time.Instant first; java.time.Instant second; }", "2 fully qualified type names in Java code"),
            Triple(project.tasks.create("variables", VariableNamesTask::class.java), "final class Example { int x; int y; }", "2 undescriptive variable names")
        )
        tasks.forEach { (task, input, expected) ->
            val source = directory.resolve(task.name + ".java").toFile()
            source.writeText(input)
            task.sources.from(source)
            task.report.set(directory.resolve(task.name + ".txt").toFile())
            if (task is VariableNamesTask) task.publishedNames.set(emptySet())
            val failure = assertThrows(GradleException::class.java) { task.check() }
            assertEquals(expected, failure.message)
            assertFalse(task.report.get().asFile.exists())
        }
    }
}
