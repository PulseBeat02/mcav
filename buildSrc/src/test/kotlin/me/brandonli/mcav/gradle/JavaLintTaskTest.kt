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

class JavaLintTaskTest {

    private companion object {

        const val LOGS_TASK: String = "logs"
        const val QUALIFIED_TASK: String = "qualified"
        const val VARIABLES_TASK: String = "variables"
        const val COMMENTS_TASK: String = "comments"

        const val SOURCE_FILE: String = "Example.java"
        const val REPORT_EXTENSION: String = ".txt"
        const val VARIABLES_REPORT: String = "variables.txt"
    }

    @TempDir
    lateinit var directory: Path

    @Test
    fun shouldKeepTheExactSuccessReportOfEachLint(): Unit {
        val project: Project = ProjectBuilder.builder().withProjectDir(this.directory.toFile()).build()
        val source: File = this.directory.resolve(SOURCE_FILE).toFile()
        source.writeText("final class Example {}\n")

        val tasks: List<Pair<JavaLintTask, String>> = listOf(
            project.tasks.register(LOGS_TASK, LogMessagesTask::class.java).get() to "no log call with a concatenated message in 1 files\n",
            project.tasks.register(QUALIFIED_TASK, QualifiedNamesTask::class.java).get() to "no fully qualified type names in 1 files\n",
            project.tasks.register(VARIABLES_TASK, VariableNamesTask::class.java).get() to "no undescriptive variable name in 1 files\n",
            project.tasks.register(COMMENTS_TASK, CommentFormTask::class.java).get() to "no run of // comment lines in 1 files\n"
        )
        val reports: List<String> = tasks.map { (task, _) -> this.checkAndReadReport(task, source) }
        assertEquals(tasks.map { it.second }, reports)
    }

    @Test
    fun shouldCountTheViolationsAndKeepTheFailureMessageOfEachLint(): Unit {
        val project: Project = ProjectBuilder.builder().withProjectDir(this.directory.toFile()).build()
        val tasks: List<Triple<JavaLintTask, String, String>> = listOf(
            Triple(
                project.tasks.register(LOGS_TASK, LogMessagesTask::class.java).get(),
                "final class Example { void run() { logger.info(\"joined \" + name); logger.error(\"joined \" + name); } }",
                "Found 2 log calls with a concatenated message!"
            ),
            Triple(
                project.tasks.register(QUALIFIED_TASK, QualifiedNamesTask::class.java).get(),
                "final class Example { java.time.Instant first; java.time.Instant second; }",
                "Found 2 fully qualified type names in Java code!"
            ),
            Triple(
                project.tasks.register(VARIABLES_TASK, VariableNamesTask::class.java).get(),
                "final class Example { int x; int y; }",
                "Found 2 undescriptive variable names!"
            ),
            Triple(
                project.tasks.register(COMMENTS_TASK, CommentFormTask::class.java).get(),
                "final class Example {\n  // first\n  // second\n  int value;\n  // third\n  // fourth\n}\n",
                "Found 2 comments written as runs of // lines!"
            )
        )
        val outcomes: List<Pair<String?, Boolean>> = tasks.map { (task, input, _) -> this.checkFailing(task, input) }
        val expected: List<Pair<String?, Boolean>> = tasks.map { it.third to false }
        assertEquals(expected, outcomes)
    }

    @Test
    fun shouldKeepAPublishedNameWhileTheOtherNamesFail(): Unit {
        val project: Project = ProjectBuilder.builder().withProjectDir(this.directory.toFile()).build()
        val task: VariableNamesTask = project.tasks.register(VARIABLES_TASK, VariableNamesTask::class.java).get()
        val source: File = this.directory.resolve(SOURCE_FILE).toFile()
        source.writeText("package me.example;\nfinal class Example { int x; int y; }\n")
        task.sources.from(source)
        task.report.set(this.directory.resolve(VARIABLES_REPORT).toFile())
        task.publishedNames.set(setOf("me.example.Example#x"))

        val failure: GradleException = assertThrows(GradleException::class.java) { task.check() }
        val message: String? = failure.message
        assertEquals("Found 1 undescriptive variable names!", message)
    }

    @Test
    fun shouldLogTheFileTheLineAndTheCallOfAConcatenatedMessage(): Unit {
        val project: Project = ProjectBuilder.builder().withProjectDir(this.directory.toFile()).build()
        val task: RecordedLogMessagesTask = project.tasks.register(LOGS_TASK, RecordedLogMessagesTask::class.java).get()
        this.checkFailing(task, "final class Example {\n    void run() {\n        logger.info(\"joined \" + name);\n    }\n}\n")

        val source: File = task.sources.singleFile
        val messages: List<String> = task.recorder.messages
        val expected: String =
            "error: $source:3: error: the message of logger.info joins strings with +; use SLF4J placeholders for the arguments instead."
        assertEquals(listOf(expected), messages)
    }

    @Test
    fun shouldLogTheFileTheLineAndTheNameOfAQualifiedType(): Unit {
        val project: Project = ProjectBuilder.builder().withProjectDir(this.directory.toFile()).build()
        val task: RecordedQualifiedNamesTask = project.tasks.register(QUALIFIED_TASK, RecordedQualifiedNamesTask::class.java).get()
        this.checkFailing(task, "final class Example {\n    java.util.Map names;\n}\n")

        val source: File = task.sources.singleFile
        val messages: List<String> = task.recorder.messages
        val expected: String =
            "error: $source:2: error: java.util.Map is written out; import it, or mark the line // fqn: <why> if its simple name clashes."
        assertEquals(listOf(expected), messages)
    }

    @Test
    fun shouldLogTheFileTheLineTheKindAndTheNameOfAnUndescriptiveVariable(): Unit {
        val project: Project = ProjectBuilder.builder().withProjectDir(this.directory.toFile()).build()
        val task: RecordedVariableNamesTask = project.tasks.register(VARIABLES_TASK, RecordedVariableNamesTask::class.java).get()
        this.checkFailing(task, "final class Example {\n    int x;\n}\n")

        val source: File = task.sources.singleFile
        val messages: List<String> = task.recorder.messages
        assertEquals(listOf("error: $source:2: error: the field x says nothing; name what it holds."), messages)
    }

    @Test
    fun shouldLogTheFileTheFirstLineAndTheLastLineOfARunOfLineComments(): Unit {
        val project: Project = ProjectBuilder.builder().withProjectDir(this.directory.toFile()).build()
        val task: RecordedCommentFormTask = project.tasks.register(COMMENTS_TASK, RecordedCommentFormTask::class.java).get()
        this.checkFailing(task, "final class Example {\n    // first\n    // second\n    // third\n    int value;\n}\n")

        val source: File = task.sources.singleFile
        val messages: List<String> = task.recorder.messages
        val expected: String =
            "error: $source:2: error: the // lines up to line 4 make one comment; write it as one /* */ block, or cut it to one // line."
        assertEquals(listOf(expected), messages)
    }

    private fun checkAndReadReport(task: JavaLintTask, source: File): String {
        this.configureLint(task, source)
        task.check()

        val report: File = task.report.get().asFile
        return report.readText()
    }

    private fun configureLint(task: JavaLintTask, source: File): Unit {
        task.sources.from(source)
        task.report.set(this.directory.resolve(task.name + REPORT_EXTENSION).toFile())

        if (task is VariableNamesTask) {
            task.publishedNames.set(emptySet())
        }
    }

    private fun checkFailing(task: JavaLintTask, input: String): Pair<String?, Boolean> {
        val source: File = this.directory.resolve("${task.name}.java").toFile()
        source.writeText(input)
        this.configureLint(task, source)

        val failure: GradleException = assertThrows(GradleException::class.java) { task.check() }
        val reportWritten: Boolean = task.report.get().asFile.exists()
        return failure.message to reportWritten
    }

    abstract class RecordedLogMessagesTask : LogMessagesTask() {

        val recorder: RecordingLogger = RecordingLogger()

        override fun getLogger(): Logger {
            return this.recorder.logger
        }
    }

    abstract class RecordedQualifiedNamesTask : QualifiedNamesTask() {

        val recorder: RecordingLogger = RecordingLogger()

        override fun getLogger(): Logger {
            return this.recorder.logger
        }
    }

    abstract class RecordedVariableNamesTask : VariableNamesTask() {

        val recorder: RecordingLogger = RecordingLogger()

        override fun getLogger(): Logger {
            return this.recorder.logger
        }
    }

    abstract class RecordedCommentFormTask : CommentFormTask() {

        val recorder: RecordingLogger = RecordingLogger()

        override fun getLogger(): Logger {
            return this.recorder.logger
        }
    }
}
