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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CoverageReportTest {

    private companion object {

        const val SOURCE_FILE: String = "Example.java"
        const val SOURCE_PATH: String = "sample/Example.java"
        const val OTHER_SOURCE_PATH: String = "other/Example.java"
        const val MISSING_SOURCE_PATH: String = "sample/Missing.java"

        const val GENERATED_SOURCE_REASON: String = "generated source"

        const val REPORT_FILE: String = "report.xml"
        const val MISSING_REPORT_FILE: String = "missing.xml"
        const val EXCEPTIONS_FILE: String = "exceptions.txt"

        const val SOURCES_DIRECTORY: String = "sources"
    }

    @TempDir
    lateinit var directory: Path

    @Test
    fun shouldKeepPartialInstructionsAndBranchesAsGaps(): Unit {
        val source: File = this.directory.resolve(SOURCE_PATH).toFile()
        source.parentFile.mkdirs()
        source.writeText("uncalled();\nchoose();\nRunnable action = () -> work();\ncovered();\n")

        val report: File = this.directory.resolve(REPORT_FILE).toFile()
        report.writeText("""
            <report name="fixture"><package name="sample"><sourcefile name="Example.java">
              <line nr="1" mi="3" ci="0" mb="0" cb="0"/>
              <line nr="2" mi="0" ci="3" mb="1" cb="1"/>
              <line nr="3" mi="2" ci="4" mb="0" cb="0"/>
              <line nr="4" mi="0" ci="3" mb="0" cb="0"/>
            </sourcefile></package></report>
        """.trimIndent())

        val gaps: List<String> = CoverageReport.findGaps(report, this.directory.toFile(), emptyList())
        assertEquals(listOf(
            "$source:1: error: line is not covered by any test.",
            "$source:2: error: 1 of 2 branches are not covered by any test.",
            "$source:3: error: 2 of 6 instructions on this line are not covered by any test."
        ), gaps)
    }

    @Test
    fun shouldExcuseExactlyOneMatchingUncoveredLineByAnException(): Unit {
        val source: File = this.directory.resolve(SOURCE_PATH).toFile()
        source.parentFile.mkdirs()
        source.writeText("serverOnly();\nserverOnly();\ncovered();\n")

        val report: File = this.directory.resolve(REPORT_FILE).toFile()
        report.writeText("""
            <report name="fixture"><package name="sample"><sourcefile name="Example.java">
              <line nr="1" mi="1" ci="0" mb="0" cb="0"/>
              <line nr="2" mi="1" ci="0" mb="0" cb="0"/>
              <line nr="3" mi="0" ci="1" mb="0" cb="0"/>
            </sourcefile></package></report>
        """.trimIndent())

        val matching: CoverageException = CoverageException(SOURCE_PATH, "serverOnly();", "server constructor")
        val stale: CoverageException = CoverageException(SOURCE_PATH, "covered();", "obsolete exception")
        val wrongPath: CoverageException = CoverageException(OTHER_SOURCE_PATH, "serverOnly();", "different source")

        val gaps: List<String> = CoverageReport.findGaps(report, this.directory.toFile(), listOf(matching, stale, wrongPath))
        assertEquals(listOf("$source:2: error: line is not covered by any test."), gaps)

        val matchingUsed: Boolean = matching.used
        assertTrue(matchingUsed)

        val staleUsed: Boolean = stale.used
        assertFalse(staleUsed)

        val wrongPathUsed: Boolean = wrongPath.used
        assertFalse(wrongPathUsed)
    }

    @Test
    fun shouldReadAReportWithoutLoadingTheDocumentTypeItNames(): Unit {
        val report: File = this.directory.resolve(REPORT_FILE).toFile()
        report.writeText("""
            <!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">
            <report name="fixture"><package name="sample"><sourcefile name="Missing.java">
              <line nr="1" mi="0" ci="3" mb="0" cb="0"/>
            </sourcefile></package></report>
        """.trimIndent())

        val gaps: List<String> = CoverageReport.findGaps(report, this.directory.toFile(), emptyList())
        assertEquals(emptyList<String>(), gaps)
    }

    @Test
    fun shouldFailOnAMissingReportOnlyWhenProductionSourcesExist(): Unit {
        val report: File = this.directory.resolve(MISSING_REPORT_FILE).toFile()
        val sources: File = this.directory.resolve(SOURCES_DIRECTORY).toFile()
        val withoutDirectory: List<String> = CoverageReport.findGaps(report, sources, emptyList())
        assertEquals(emptyList<String>(), withoutDirectory)

        sources.mkdirs()

        val withoutSources: List<String> = CoverageReport.findGaps(report, sources, emptyList())
        assertEquals(emptyList<String>(), withoutSources)

        sources.resolve(SOURCE_FILE).writeText("class Example {}\n")

        val withSources: List<String> = CoverageReport.findGaps(report, sources, emptyList())
        assertEquals(listOf("$sources: error: no test ran, so none of this code is covered."), withSources)
    }

    @Test
    fun shouldRequireAReasonAndKeepTheSeparatorsOfTheSource(): Unit {
        val file: File = this.directory.resolve(EXCEPTIONS_FILE).toFile()
        val withoutFile: List<CoverageException> = CoverageExceptions.read(file)
        assertEquals(emptyList<CoverageException>(), withoutFile)

        file.writeText("# comment\n\nsample/Example.java | left | right | native boundary\n")

        val entries: List<CoverageException> = CoverageExceptions.read(file)
        val entryCount: Int = entries.size
        assertEquals(1, entryCount)

        val path: String = entries[0].path
        assertEquals(SOURCE_PATH, path)

        val sourceLine: String = entries[0].sourceLine
        assertEquals("left | right", sourceLine)

        val reason: String = entries[0].reason
        assertEquals("native boundary", reason)

        for (invalid in listOf("missing separators", "path | source", "path | source | ")) {
            file.writeText(invalid)
            assertThrows(IllegalArgumentException::class.java) { CoverageExceptions.read(file) }
        }
    }

    @Test
    fun shouldReadAnEmptySourceLineBetweenTheSeparators(): Unit {
        val file: File = this.directory.resolve(EXCEPTIONS_FILE).toFile()
        file.writeText("sample/Missing.java |  | generated source\n")

        val entries: List<CoverageException> = CoverageExceptions.read(file)
        val fields: List<Triple<String, String, String>> = entries.map { Triple(it.path, it.sourceLine, it.reason) }
        assertEquals(listOf(Triple(MISSING_SOURCE_PATH, "", GENERATED_SOURCE_REASON)), fields)
    }

    @Test
    fun shouldReadAMissingSourceAsBlankLinesAndSkipALineWithoutInstructions(): Unit {
        val report: File = this.directory.resolve(REPORT_FILE).toFile()
        report.writeText("""
            <report name="fixture"><package name="sample"><sourcefile name="Missing.java">
              <line nr="1" mi="3" ci="0" mb="0" cb="0"/>
              <line nr="2" mi="0" ci="0" mb="0" cb="0"/>
            </sourcefile></package></report>
        """.trimIndent())

        val blankLine: CoverageException = CoverageException(MISSING_SOURCE_PATH, "", GENERATED_SOURCE_REASON)

        val gaps: List<String> = CoverageReport.findGaps(report, this.directory.toFile(), listOf(blankLine))
        assertEquals(emptyList<String>(), gaps)

        val used: Boolean = blankLine.used
        assertTrue(used)
    }
}
