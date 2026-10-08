package me.brandonli.mcav.gradle

import java.io.File
import java.util.zip.ZipFile
import org.gradle.api.GradleException

/**
 * Turns a time budget into the time of one jcstress iteration, as jcstress has no time budget of its own. A run is
 * made of runs of every test: one per JVM configuration (14 for the two-actor tests here, measured) and fork, where a
 * mode has a number of normal forks and a multiple of that of stress forks. Each run starts a JVM (about 2 s here) and
 * then iterates a number of times; a two-actor run takes two CPUs, so the CPUs run several at once. Half of the budget
 * goes to the iterations, the rest to the JVM starts and to a machine slower than this one.
 */
object JcstressBudget {

    private const val CONFIGURATIONS = 14
    private const val JVM_START_MILLIS = 2_000L
    private const val MIN_ITERATION_MILLIS = 50L
    private const val MAX_ITERATION_MILLIS = 10_000L
    private const val MILLIS_PER_MINUTE = 60_000L
    private const val TEST_LINE = "JCTEST"

    private class Preset(val forks: Int, val stressMultiplier: Int, val iterations: Int)

    private val presets = mapOf(
        "sanity" to Preset(1, 1, 1),
        "quick" to Preset(1, 1, 5),
        "default" to Preset(1, 5, 5),
        "tough" to Preset(10, 5, 10),
        "stress" to Preset(10, 10, 50),
    )

    /**
     * Counts the tests of a jcstress jar, from the list the annotation processor writes.
     *
     * @param jar the jar that runs the tests
     * @param selection the regular expression of `-t` that selects tests, or null for every test
     * @return the number of tests the run starts
     */
    fun countTests(jar: File, selection: String?): Int {
        val pattern = selection?.let { Regex(it) }
        ZipFile(jar).use { zip ->
            val entry = zip.getEntry("META-INF/TestList") ?: return 0
            val lines = zip.getInputStream(entry).bufferedReader().readLines()
            return lines.count { line -> line.startsWith(TEST_LINE) && (pattern == null || pattern.containsMatchIn(testName(line))) }
        }
    }

    private fun testName(line: String): String {
        val lengthEnd = line.indexOf('S', TEST_LINE.length)
        val length = line.substring(TEST_LINE.length, lengthEnd).toInt()
        return line.substring(lengthEnd + 1, lengthEnd + 1 + length)
    }

    /**
     * The time of one iteration that fits a run into a budget.
     *
     * @param mode the jcstress mode, such as quick or stress
     * @param budgetMinutes the minutes the whole run may take
     * @param testCount the number of tests the run starts
     * @param cpus the CPUs jcstress may use
     * @return the milliseconds of one iteration, between 50 ms and 10 s
     */
    fun iterationMillis(mode: String, budgetMinutes: Int, testCount: Int, cpus: Int): Long {
        val preset = presets[mode] ?: throw GradleException("Unknown jcstress mode $mode")
        val runs = testCount.toLong() * CONFIGURATIONS * (preset.forks + preset.forks * preset.stressMultiplier)
        val parallelRuns = maxOf(1, cpus / 2)
        val budgetMillis = budgetMinutes * MILLIS_PER_MINUTE
        val startMillis = runs * JVM_START_MILLIS / parallelRuns
        val iterationBudget = budgetMillis / 2 - startMillis
        val perIteration = iterationBudget * parallelRuns / maxOf(1L, runs * preset.iterations)
        return perIteration.coerceIn(MIN_ITERATION_MILLIS, MAX_ITERATION_MILLIS)
    }
}
