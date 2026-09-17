package me.brandonli.mcav.gradle

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * A line of production code that no test can run, such as a constructor only a server may call, together with the
 * reason. Each entry excuses exactly one uncovered line, so an entry for a line such as a closing brace cannot hide
 * other gaps.
 */
class CoverageException(val path: String, val sourceLine: String, val reason: String) {
    /** Whether the entry matched an uncovered line of the current report. */
    var used = false
}

/**
 * Reads {@code coverage-exceptions.txt}: one entry per line, written as
 * {@code path below src/main/java | exact source line | reason}, with {@code #} starting a comment line. The reason
 * must not contain {@code " | "}.
 */
object CoverageExceptions {

    private const val SEPARATOR = " | "

    fun read(file: File): List<CoverageException> {
        if (!file.isFile) {
            return emptyList()
        }
        val entries = file.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        return entries.map { entry -> parse(file, entry) }
    }

    private fun parse(file: File, entry: String): CoverageException {
        val first = entry.indexOf(SEPARATOR)
        val last = entry.lastIndexOf(SEPARATOR)
        require(first >= 0 && last > first) { "$file: expected 'path | source line | reason' but found '$entry'" }
        val path = entry.substring(0, first)
        val sourceLine = entry.substring(first + SEPARATOR.length, last)
        val reason = entry.substring(last + SEPARATOR.length)
        require(reason.isNotBlank()) { "$file: every exception needs a reason: '$entry'" }
        return CoverageException(path, sourceLine, reason)
    }
}

/**
 * Finds the gaps in a JaCoCo XML report. Like JaCoCo's line counter, a line is a gap when none of it runs or when one
 * of its branches never runs.
 */
object CoverageReport {

    fun findGaps(report: File, sourceDirectory: File, exceptions: List<CoverageException>): List<String> {
        if (!report.isFile) {
            val hasSources = sourceDirectory.isDirectory && sourceDirectory.walk().any { it.extension == "java" }
            return if (hasSources) listOf("$sourceDirectory: warning: no test ran, so none of this code is covered") else emptyList()
        }
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        val document = factory.newDocumentBuilder().parse(report)
        val sourceFiles = document.getElementsByTagName("sourcefile")
        val gaps = mutableListOf<String>()
        for (index in 0 until sourceFiles.length) {
            val sourceFile = sourceFiles.item(index) as Element
            gaps += findGapsInFile(sourceFile, sourceDirectory, exceptions)
        }
        return gaps
    }

    private fun findGapsInFile(sourceFile: Element, sourceDirectory: File, exceptions: List<CoverageException>): List<String> {
        val packageElement = sourceFile.parentNode as Element
        val relativePath = packageElement.getAttribute("name") + "/" + sourceFile.getAttribute("name")
        val file = File(sourceDirectory, relativePath)
        val sourceLines by lazy { if (file.isFile) file.readLines() else emptyList() }
        val lines = sourceFile.getElementsByTagName("line")
        val gaps = mutableListOf<String>()
        for (index in 0 until lines.length) {
            val line = lines.item(index) as Element
            val gap = describeGap(line) ?: continue
            val number = line.getAttribute("nr").toInt()
            val text = sourceLines.getOrNull(number - 1)?.trim() ?: ""
            val exception = exceptions.firstOrNull { !it.used && it.path == relativePath && it.sourceLine == text }
            if (exception == null) {
                gaps += "$file:$number: warning: $gap"
            } else {
                exception.used = true
            }
        }
        return gaps
    }

    private fun describeGap(line: Element): String? {
        val missedInstructions = line.getAttribute("mi").toInt()
        val coveredInstructions = line.getAttribute("ci").toInt()
        val missedBranches = line.getAttribute("mb").toInt()
        val coveredBranches = line.getAttribute("cb").toInt()
        return when {
            coveredInstructions == 0 && missedInstructions > 0 -> "line is not covered by any test"
            missedBranches > 0 -> "$missedBranches of ${missedBranches + coveredBranches} branches are not covered by any test"
            // a line JaCoCo counts as covered can still hold instructions no test ran, which is what a lambda whose
            // body is never invoked looks like: the body belongs to the line that declares it. Reporting only whole
            // lines and branches would call such a line covered and hide the untested body.
            missedInstructions > 0 -> "$missedInstructions of ${missedInstructions + coveredInstructions} instructions on this line are not covered by any test"
            else -> null
        }
    }
}
