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

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.code_intelligence.jazzer.junit.FuzzTest
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag

// JaCoCo writes the coverage report, so the counters of its lines and the source they point into can be any values.
@Tag("fuzz")
class CoverageReportFuzzTest {

    private companion object {

        const val DIRECTORY_PREFIX: String = "coverage-report"
        const val REPORT_NAME: String = "report.xml"

        const val PACKAGE_NAME: String = "me/brandonli/mcav"
        const val FILE_NAME: String = "Source.java"
        const val SOURCE_PATH: String = "$PACKAGE_NAME/$FILE_NAME"
        const val REASON: String = "fuzzed"

        const val MAXIMUM_SOURCE_LINES: Int = 8
        const val MAXIMUM_SOURCE_LINE_LENGTH: Int = 16
        const val MAXIMUM_REPORT_LINES: Int = 16
        const val MAXIMUM_LINE_NUMBER: Int = 12
        const val MAXIMUM_COUNTER: Int = 3
        const val MAXIMUM_EXCEPTIONS: Int = 4

        const val LINE_BREAK: String = "\n"
    }

    @FuzzTest
    fun shouldReportOrExcuseEveryLineWithAMissedCounter(data: FuzzedDataProvider): Unit {
        val directory: Path = Files.createTempDirectory(DIRECTORY_PREFIX)
        try {
            this.assertEveryGapReportedOrExcused(data, directory.toFile())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun assertEveryGapReportedOrExcused(data: FuzzedDataProvider, directory: File): Unit {
        val sourceLines: List<String> = this.writeSource(data, directory)
        val lineCount: Int = data.consumeInt(0, MAXIMUM_REPORT_LINES)
        val lines: List<ReportLine> = (0 until lineCount).map { this.consumeLine(data) }
        val report: File = this.writeReport(directory, lines)
        val exceptionCount: Int = data.consumeInt(0, MAXIMUM_EXCEPTIONS)
        val exceptions: List<CoverageException> = (0 until exceptionCount).map { this.consumeException(data, sourceLines) }

        val gaps: List<String> = CoverageReport.findGaps(report, directory, exceptions)
        val missedLines: Int = lines.count { it.isMissed() }
        val usedExceptions: Int = exceptions.count { it.used }
        assertEquals(missedLines, gaps.size + usedExceptions)
    }

    private fun writeSource(data: FuzzedDataProvider, directory: File): List<String> {
        val lineCount: Int = data.consumeInt(0, MAXIMUM_SOURCE_LINES)
        val lines: List<String> = (0 until lineCount).map { data.consumeAsciiString(MAXIMUM_SOURCE_LINE_LENGTH) }
        val source: File = File(directory, SOURCE_PATH)
        val packageDirectory: File = source.parentFile
        packageDirectory.mkdirs()
        source.writeText(lines.joinToString(LINE_BREAK))

        return lines
    }

    private fun consumeLine(data: FuzzedDataProvider): ReportLine {
        val number: Int = data.consumeInt(0, MAXIMUM_LINE_NUMBER)
        val missedInstructions: Int = data.consumeInt(0, MAXIMUM_COUNTER)
        val coveredInstructions: Int = data.consumeInt(0, MAXIMUM_COUNTER)
        val missedBranches: Int = data.consumeInt(0, MAXIMUM_COUNTER)
        val coveredBranches: Int = data.consumeInt(0, MAXIMUM_COUNTER)
        return ReportLine(number, missedInstructions, coveredInstructions, missedBranches, coveredBranches)
    }

    private fun writeReport(directory: File, lines: List<ReportLine>): File {
        val elements: String = lines.joinToString("") { it.toXml() }
        val report: File = File(directory, REPORT_NAME)
        report.writeText(
            """<report name="fuzz"><package name="$PACKAGE_NAME"><sourcefile name="$FILE_NAME">$elements</sourcefile></package></report>"""
        )

        return report
    }

    private fun consumeException(data: FuzzedDataProvider, sourceLines: List<String>): CoverageException {
        if (sourceLines.isEmpty() || data.consumeBoolean()) {
            val sourceLine: String = data.consumeAsciiString(MAXIMUM_SOURCE_LINE_LENGTH)
            return CoverageException(SOURCE_PATH, sourceLine, REASON)
        }

        val pickedLine: String = data.pickValue(sourceLines)
        val sourceLine: String = pickedLine.trim()
        return CoverageException(SOURCE_PATH, sourceLine, REASON)
    }

    private class ReportLine(
        private val number: Int,
        private val missedInstructions: Int,
        private val coveredInstructions: Int,
        private val missedBranches: Int,
        private val coveredBranches: Int,
    ) {

        fun isMissed(): Boolean {
            return this.missedInstructions > 0 || this.missedBranches > 0
        }

        fun toXml(): String {
            return """<line nr="${this.number}" mi="${this.missedInstructions}" ci="${this.coveredInstructions}" """ +
                """mb="${this.missedBranches}" cb="${this.coveredBranches}"/>"""
        }
    }
}
