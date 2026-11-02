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
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.NodeList

/**
 * Finds the lines that a JaCoCo XML report shows as not fully covered.
 */
object CoverageReport {

    private const val LOAD_EXTERNAL_DTD: String = "http://apache.org/xml/features/nonvalidating/load-external-dtd"

    private const val JAVA_EXTENSION: String = "java"

    private const val PATH_SEPARATOR: String = "/"

    private const val SOURCE_FILE_TAG: String = "sourcefile"
    private const val LINE_TAG: String = "line"

    private const val NAME_ATTRIBUTE: String = "name"
    private const val LINE_NUMBER_ATTRIBUTE: String = "nr"
    private const val MISSED_INSTRUCTIONS_ATTRIBUTE: String = "mi"
    private const val COVERED_INSTRUCTIONS_ATTRIBUTE: String = "ci"
    private const val MISSED_BRANCHES_ATTRIBUTE: String = "mb"
    private const val COVERED_BRANCHES_ATTRIBUTE: String = "cb"

    private const val FIRST_LINE_NUMBER: Int = 1

    /**
     * Returns an error for every line in [report] that the tests do not fully cover and no exception excuses.
     *
     * An exception excuses one uncovered line of its source file with the same trimmed text, and is marked as used. Without
     * a report, the result is one error that no test ran, or nothing when [sourceDirectory] holds no Java source.
     *
     * @param report          the JaCoCo XML report
     * @param sourceDirectory the directory of the main sources
     * @param exceptions      the coverage exceptions of the module
     * @return the errors, one for each gap, in report order
     * @throws org.xml.sax.SAXException if the report is not well-formed XML
     */
    fun findGaps(report: File, sourceDirectory: File, exceptions: List<CoverageException>): List<String> {
        if (!report.isFile) {
            return this.describeMissingReport(sourceDirectory)
        }

        val factory: DocumentBuilderFactory = DocumentBuilderFactory.newInstance()
        factory.setFeature(LOAD_EXTERNAL_DTD, false)

        val builder: DocumentBuilder = factory.newDocumentBuilder()
        val document: Document = builder.parse(report)
        val sourceFiles: NodeList = document.getElementsByTagName(SOURCE_FILE_TAG)
        val gaps: MutableList<String> = mutableListOf<String>()
        for (index in 0 until sourceFiles.length) {
            val sourceFile: Element = sourceFiles.item(index) as Element
            gaps += this.findGapsInFile(sourceFile, sourceDirectory, exceptions)
        }

        return gaps
    }

    private fun describeMissingReport(sourceDirectory: File): List<String> {
        val hasSources: Boolean = sourceDirectory.isDirectory && sourceDirectory.walk().any { it.extension == JAVA_EXTENSION }
        return if (hasSources) listOf("$sourceDirectory: error: no test ran, so none of this code is covered.") else emptyList()
    }

    private fun findGapsInFile(sourceFile: Element, sourceDirectory: File, exceptions: List<CoverageException>): List<String> {
        val relativePath: String = this.relativePathOf(sourceFile)
        val file: File = File(sourceDirectory, relativePath)
        val sourceLines: List<String> by lazy { if (file.isFile) file.readLines() else emptyList() }
        val lines: NodeList = sourceFile.getElementsByTagName(LINE_TAG)
        val gaps: MutableList<String> = mutableListOf<String>()
        for (index in 0 until lines.length) {
            val line: Element = lines.item(index) as Element
            val gap: String = this.describeGap(line) ?: continue
            val number: Int = line.getAttribute(LINE_NUMBER_ATTRIBUTE).toInt()
            val text: String = sourceLines.getOrNull(number - FIRST_LINE_NUMBER)?.trim() ?: ""
            val exception: CoverageException? = exceptions.firstOrNull { !it.used && it.path == relativePath && it.sourceLine == text }
            if (exception == null) {
                gaps += "$file:$number: error: $gap."
            } else {
                exception.used = true
            }
        }

        return gaps
    }

    private fun relativePathOf(sourceFile: Element): String {
        val packageElement: Element = sourceFile.parentNode as Element
        val packageName: String = packageElement.getAttribute(NAME_ATTRIBUTE)
        val fileName: String = sourceFile.getAttribute(NAME_ATTRIBUTE)
        return "$packageName$PATH_SEPARATOR$fileName"
    }

    private fun describeGap(line: Element): String? {
        val missedInstructions: Int = line.getAttribute(MISSED_INSTRUCTIONS_ATTRIBUTE).toInt()
        val coveredInstructions: Int = line.getAttribute(COVERED_INSTRUCTIONS_ATTRIBUTE).toInt()
        val missedBranches: Int = line.getAttribute(MISSED_BRANCHES_ATTRIBUTE).toInt()
        val coveredBranches: Int = line.getAttribute(COVERED_BRANCHES_ATTRIBUTE).toInt()
        return when {
            coveredInstructions == 0 && missedInstructions > 0 -> "line is not covered by any test"
            missedBranches > 0 -> "$missedBranches of ${missedBranches + coveredBranches} branches are not covered by any test"
            missedInstructions > 0 ->
                "$missedInstructions of ${missedInstructions + coveredInstructions} instructions on this line are not covered by any test"
            else -> null
        }
    }
}
