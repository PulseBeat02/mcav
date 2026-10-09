package me.brandonli.mcav.gradle

import java.io.File
import java.util.zip.ZipFile
import org.gradle.api.GradleException

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
