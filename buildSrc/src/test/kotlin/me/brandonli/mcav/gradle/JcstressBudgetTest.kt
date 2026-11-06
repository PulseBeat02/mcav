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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.gradle.api.Project
import org.gradle.api.logging.Logger
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.TaskProvider
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class JcstressBudgetTest {

    private companion object {

        const val QUICK_PRESET: String = "quick"

        const val BUDGET_MINUTES: Int = 10

        const val SECOND_TEST_SELECTION: String = """^a\.b\.Second$"""

        const val TESTS_JAR: String = "tests.jar"
        const val EMPTY_JAR: String = "empty.jar"
        const val TEST_LIST_ENTRY: String = "META-INF/TestList"

        const val OTHER_ENTRY: String = "other"
    }

    @TempDir
    lateinit var directory: Path

    @Test
    fun shouldSplitHalfOfTheBudgetOverTheIterationsOfEveryRun(): Unit {
        val iterationMilliseconds: Long = JcstressBudget.iterationMilliseconds(QUICK_PRESET, BUDGET_MINUTES, 9, 4)
        assertEquals(76L, iterationMilliseconds)
    }

    @Test
    fun shouldKeepTheIterationTimeWithinItsBounds(): Unit {
        val shortest: Long = JcstressBudget.iterationMilliseconds(QUICK_PRESET, 1, 9, 4)
        assertEquals(50L, shortest)

        val longest: Long = JcstressBudget.iterationMilliseconds("sanity", 100_000, 1, 4)
        assertEquals(10_000L, longest)
    }

    @Test
    fun shouldRunOneRunAtATimeOnASingleCpu(): Unit {
        val iterationMilliseconds: Long = JcstressBudget.iterationMilliseconds("default", BUDGET_MINUTES, 1, 1)
        assertEquals(314L, iterationMilliseconds)
    }

    @Test
    fun shouldRefuseAnUnknownMode(): Unit {
        assertThrows(IllegalArgumentException::class.java) { JcstressBudget.iterationMilliseconds("forever", BUDGET_MINUTES, 1, 4) }
    }

    @Test
    fun shouldLimitTheIterationTimeOfARunWithABudget(): Unit {
        val jar: File = this.writeTestsJar()
        val task: JavaExec = this.createTask()
        JcstressBudget.limitIterations(task, jar, SECOND_TEST_SELECTION, QUICK_PRESET, BUDGET_MINUTES, 4)

        val arguments: List<String> = task.args
        assertEquals(listOf("-time", "3885"), arguments)
    }

    @Test
    fun shouldLeaveARunWithoutABudgetUnlimited(): Unit {
        val jar: File = this.directory.resolve(EMPTY_JAR).toFile()
        val task: JavaExec = this.createTask()
        JcstressBudget.limitIterations(task, jar, null, QUICK_PRESET, null, 4)

        val arguments: List<String> = task.args
        assertEquals(listOf<String>(), arguments)
    }

    @Test
    fun shouldCountTheTestsThatTheSelectionMatches(): Unit {
        val jar: File = this.writeTestsJar()

        val all: Int = JcstressBudget.countTests(jar, null)
        assertEquals(2, all)

        val selected: Int = JcstressBudget.countTests(jar, SECOND_TEST_SELECTION)
        assertEquals(1, selected)

        val unmatched: Int = JcstressBudget.countTests(jar, "Third")
        assertEquals(0, unmatched)
    }

    @Test
    fun shouldCountNoTestsInAJarWithoutTheList(): Unit {
        val jar: File = this.directory.resolve(EMPTY_JAR).toFile()
        this.writeJar(jar, OTHER_ENTRY, "")

        val count: Int = JcstressBudget.countTests(jar, null)
        assertEquals(0, count)
    }

    @Test
    fun shouldCloseTheJarOnceItsTestsAreCounted(): Unit {
        OpenFiles.assumeListable()

        val jar: File = this.directory.resolve(EMPTY_JAR).toFile()
        this.writeJar(jar, OTHER_ENTRY, "")
        JcstressBudget.countTests(jar, null)

        val open: Boolean = OpenFiles.isOpen(jar)
        assertFalse(open)
    }

    @Test
    fun shouldLogTheSelectedTestsAndIterationPlan(): Unit {
        val jar: File = this.writeTestsJar()
        val project: Project = ProjectBuilder.builder().withProjectDir(this.directory.toFile()).build()
        val task: RecordedJavaExec = project.tasks.register("recordedJcstressTest", RecordedJavaExec::class.java).get()
        JcstressBudget.limitIterations(task, jar, SECOND_TEST_SELECTION, QUICK_PRESET, BUDGET_MINUTES, 4)

        val messages: List<String> = task.recorder.messages
        assertEquals(listOf("lifecycle: Running 1 jcstress tests in quick mode within 10 minutes, at 3885 ms per iteration."), messages)
    }

    private fun writeTestsJar(): File {
        val jar: File = this.directory.resolve(TESTS_JAR).toFile()
        val testList: String = listOf("JCTEST9Sa.b.First,x", "JCTEST10Sa.b.Second,y", "other line").joinToString("\n")
        this.writeJar(jar, TEST_LIST_ENTRY, testList)

        return jar
    }

    private fun createTask(): JavaExec {
        val project: Project = ProjectBuilder.builder().withProjectDir(this.directory.toFile()).build()
        val task: TaskProvider<JavaExec> = project.tasks.register("jcstressTest", JavaExec::class.java)
        return task.get()
    }

    private fun writeJar(jar: File, entry: String, content: String): Unit {
        ZipOutputStream(jar.outputStream()).use { this.writeEntry(it, entry, content) }
    }

    private fun writeEntry(zip: ZipOutputStream, entry: String, content: String): Unit {
        zip.putNextEntry(ZipEntry(entry))
        zip.write(content.toByteArray())
        zip.closeEntry()
    }

    abstract class RecordedJavaExec : JavaExec() {

        val recorder: RecordingLogger = RecordingLogger()

        override fun getLogger(): Logger {
            return this.recorder.logger
        }
    }
}
