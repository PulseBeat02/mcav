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
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Sizes a jcstress run to a time budget.
 */
object JcstressBudget {

    private const val CONFIGURATIONS: Int = 14
    private const val CPUS_PER_RUN: Int = 2
    private const val ITERATION_SHARE_OF_BUDGET: Int = 2

    private const val JVM_START_MILLISECONDS: Long = 2_000L
    private const val MINIMUM_ITERATION_MILLISECONDS: Long = 50L
    private const val MAXIMUM_ITERATION_MILLISECONDS: Long = 10_000L
    private const val MILLISECONDS_PER_MINUTE: Long = 60_000L

    private const val TEST_LIST_ENTRY: String = "META-INF/TestList"
    private const val TEST_LINE: String = "JCTEST"
    private const val NAME_LENGTH_TERMINATOR: Char = 'S'

    private val PRESETS: Map<String, Preset> = mapOf(
        "sanity" to Preset(1, 1, 1),
        "quick" to Preset(1, 1, 5),
        "default" to Preset(1, 5, 5),
        "tough" to Preset(10, 5, 10),
        "stress" to Preset(10, 10, 50),
    )

    /**
     * Returns the number of tests in a jcstress jar that [selection] selects.
     *
     * @param jar       the jcstress test jar
     * @param selection the regular expression that matches part of each selected test name, or `null` to select every test
     * @return the number of selected tests, or `0` when the jar has no test list
     */
    fun countTests(jar: File, selection: String?): Int {
        val pattern: Regex? = selection?.let { Regex(it) }
        val lines: List<String> = ZipFile(jar).use(this::readTestList)
        return lines.count { this.isSelectedTest(it, pattern) }
    }

    private fun readTestList(zip: ZipFile): List<String> {
        val entry: ZipEntry = zip.getEntry(TEST_LIST_ENTRY) ?: return emptyList()
        return zip.getInputStream(entry).reader().readLines()
    }

    private fun isSelectedTest(line: String, pattern: Regex?): Boolean {
        if (!line.startsWith(TEST_LINE)) {
            return false
        }

        return pattern == null || pattern.containsMatchIn(this.testName(line))
    }

    private fun testName(line: String): String {
        val lengthEnd: Int = line.indexOf(NAME_LENGTH_TERMINATOR, TEST_LINE.length)
        val length: Int = line.substring(TEST_LINE.length, lengthEnd).toInt()
        return line.substring(lengthEnd + 1, lengthEnd + 1 + length)
    }

    /**
     * Returns the iteration time that fits the iterations of a run, after the time that its JVMs take to start, into half of
     * its budget.
     *
     * The result lies between 50 milliseconds and 10 seconds.
     *
     * @param mode          the jcstress mode: `sanity`, `quick`, `default`, `tough` or `stress`
     * @param budgetMinutes the time budget of the run in minutes
     * @param testCount     the number of tests that the run selects
     * @param cpus          the number of processors that the run may use
     * @return the iteration time in milliseconds
     * @throws IllegalArgumentException if [mode] is unknown
     */
    fun iterationMilliseconds(mode: String, budgetMinutes: Int, testCount: Int, cpus: Int): Long {
        val message: String = "Jcstress mode $mode is unknown!"
        val preset: Preset = PRESETS[mode] ?: throw IllegalArgumentException(message)
        val runs: Long = testCount.toLong() * CONFIGURATIONS * (preset.forks + preset.forks * preset.stressMultiplier)
        val parallelRuns: Int = maxOf(1, cpus / CPUS_PER_RUN)
        val budgetMilliseconds: Long = budgetMinutes * MILLISECONDS_PER_MINUTE
        val startMilliseconds: Long = runs * JVM_START_MILLISECONDS / parallelRuns
        val iterationBudget: Long = budgetMilliseconds / ITERATION_SHARE_OF_BUDGET - startMilliseconds
        val perIteration: Long = iterationBudget * parallelRuns / maxOf(1L, runs * preset.iterations)
        return perIteration.coerceIn(MINIMUM_ITERATION_MILLISECONDS, MAXIMUM_ITERATION_MILLISECONDS)
    }

    private class Preset(val forks: Int, val stressMultiplier: Int, val iterations: Int)
}
