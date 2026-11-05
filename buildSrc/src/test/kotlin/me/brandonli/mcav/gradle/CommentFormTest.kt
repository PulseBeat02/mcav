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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CommentFormTest {

    private companion object {

        const val LINE_SEPARATOR: String = "\n"
    }

    @Test
    fun shouldReportARunOfLineCommentsFromItsFirstToItsLastLine(): Unit {
        val source: String = """
            final class Example {
              // first
              // second
              // third
              void run() {}
            }
        """.trimIndent()
        val runs: List<IntRange> = CommentForm.find(source)
        assertEquals(listOf(2..4), runs)
    }

    @Test
    fun shouldReportEveryRunApartUpToTheEdgesOfTheSource(): Unit {
        val source: String = listOf(
            "// first",
            "// second",
            "int value;",
            "\t// third",
            "\t//",
            "\t// fourth",
        ).joinToString(LINE_SEPARATOR)
        val runs: List<IntRange> = CommentForm.find(source)
        assertEquals(listOf(1..2, 4..6), runs)
    }

    @Test
    fun shouldAcceptLineCommentsThatCodeOrABlankLineSeparates(): Unit {
        val source: String = """
            final class Example {
              // first

              // second
              int value;
              // third
            }
        """.trimIndent()
        val runs: List<IntRange> = CommentForm.find(source)
        assertEquals(emptyList<IntRange>(), runs)
    }

    @Test
    fun shouldNotCountACommentAfterCodeOnItsLine(): Unit {
        val source: String = """
            int first; // first
            int second; // second
            // own line
            int third; // third
            /* block */ // after a block comment
            // own line again
        """.trimIndent()
        val runs: List<IntRange> = CommentForm.find(source)
        assertEquals(emptyList<IntRange>(), runs)
    }

    @Test
    fun shouldNotCountMarkersAndToolDirectives(): Unit {
        val source: String = listOf(
            "// first",
            "// fqn: Display is imported as org.bukkit.entity.Display",
            "// second",
            "// spotless:off",
            "// third",
            "//spotless:on",
            "// fourth",
            "// NOLINT",
            "// fifth",
            "// NOLINTNEXTLINE(bugprone-branch-clone)",
            "// sixth",
            "// @formatter:off",
            "// seventh",
        ).joinToString(LINE_SEPARATOR)
        val runs: List<IntRange> = CommentForm.find(source)
        assertEquals(emptyList<IntRange>(), runs)
    }

    @Test
    fun shouldIgnoreSlashesInBlockCommentsJavadocAndLiterals(): Unit {
        val quotes: String = "\"\"\""
        val source: String = listOf(
            "/*",
            "// first",
            "// second",
            "*/",
            "/**",
            " * // third",
            " * // fourth",
            " */",
            "final String text = $quotes",
            "    // fifth",
            "    // sixth",
            "    $quotes;",
            "final String url = \"// seventh\";",
        ).joinToString(LINE_SEPARATOR)
        val runs: List<IntRange> = CommentForm.find(source)
        assertEquals(emptyList<IntRange>(), runs)
    }
}
