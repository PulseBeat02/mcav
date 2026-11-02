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

class JavaSourceTest {

    @Test
    fun shouldMaskCommentsAndLiteralsAndKeepEveryOffset(): Unit {
        val quotes: String = "\"\"\""
        val source: String = listOf(
            "int a = 1; // note",
            "/* block",
            "   end */ call(\"text, (\", 'c');",
            "String b = $quotes",
            "  line \\$quotes",
            "  $quotes;",
        ).joinToString("\n")
        val expected: String = listOf(
            "int a = 1;${" ".repeat(8)}",
            " ".repeat(8),
            "${" ".repeat(9)} call(${" ".repeat(9)}, ${" ".repeat(3)});",
            "String b = ${" ".repeat(3)}",
            " ".repeat(11),
            "${" ".repeat(5)};",
        ).joinToString("\n")
        val masked: String = JavaSource.mask(source)
        assertEquals(expected, masked)
    }

    @Test
    fun shouldEndALiteralThatLacksItsQuoteAtTheEndOfTheLine(): Unit {
        val masked: String = JavaSource.mask("\"a\nnext")
        assertEquals("  \nnext", masked)
    }

    @Test
    fun shouldBlankAnEscapeThatEndsTheSource(): Unit {
        val masked: String = JavaSource.mask("\"a\\")
        assertEquals("   ", masked)
    }

    @Test
    fun shouldBlankCommentsAndKeepLiteralsWhenRemovingComments(): Unit {
        val source: String = "call(\"a // b\"); // note\n/* c */ 'd';"
        val expected: String = "call(\"a // b\");${" ".repeat(8)}\n${" ".repeat(8)}'d';"
        val withoutComments: String = JavaSource.withoutComments(source)
        assertEquals(expected, withoutComments)
    }

    @Test
    fun shouldKeepAnEscapeThatEndsTheSourceWhenRemovingComments(): Unit {
        val withoutComments: String = JavaSource.withoutComments("\"a\\")
        assertEquals("\"a\\", withoutComments)
    }
}
