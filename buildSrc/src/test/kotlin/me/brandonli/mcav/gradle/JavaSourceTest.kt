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

    private companion object {

        const val LINE_SEPARATOR: String = "\n"

        const val SPACE: String = " "

        const val SOURCE_ENDING_IN_AN_ESCAPE: String = "\"a\\"
    }

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
        ).joinToString(LINE_SEPARATOR)
        val expected: String = listOf(
            "int a = 1;${SPACE.repeat(8)}",
            SPACE.repeat(8),
            "${SPACE.repeat(9)} call(${SPACE.repeat(9)}, ${SPACE.repeat(3)});",
            "String b = ${SPACE.repeat(3)}",
            SPACE.repeat(11),
            "${SPACE.repeat(5)};",
        ).joinToString(LINE_SEPARATOR)
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
        val masked: String = JavaSource.mask(SOURCE_ENDING_IN_AN_ESCAPE)
        assertEquals("   ", masked)
    }

    @Test
    fun shouldBlankCommentsAndKeepLiteralsWhenRemovingComments(): Unit {
        val source: String = "call(\"a // b\"); // note\n/* c */ 'd';"
        val expected: String = "call(\"a // b\");${SPACE.repeat(8)}\n${SPACE.repeat(8)}'d';"
        val withoutComments: String = JavaSource.withoutComments(source)
        assertEquals(expected, withoutComments)
    }

    @Test
    fun shouldKeepAnEscapeThatEndsTheSourceWhenRemovingComments(): Unit {
        val withoutComments: String = JavaSource.withoutComments(SOURCE_ENDING_IN_AN_ESCAPE)
        assertEquals(SOURCE_ENDING_IN_AN_ESCAPE, withoutComments)
    }

    @Test
    fun shouldFindEveryLineCommentOutsideBlockCommentsAndLiterals(): Unit {
        val source: String = "// a // b\nint c; // d\n/* // e */ \"// f\" '/' // g"
        val offsets: List<Int> = JavaSource.findLineCommentOffsets(source)
        assertEquals(listOf(0, 17, 44), offsets)
    }
}
