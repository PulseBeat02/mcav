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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag

// The lints search masked sources and line comments: any text keeps its length and line breaks, and every comment found is blanked.
@Tag("fuzz")
class JavaSourceFuzzTest {

    private companion object {

        const val LINE_BREAK: Char = '\n'
        const val BLANK: Char = ' '

        const val LINE_COMMENT_START: String = "//"
    }

    @FuzzTest
    fun shouldKeepTheShapeOfAnySourceAndBlankEveryLineCommentItFinds(data: FuzzedDataProvider): Unit {
        val source: String = data.consumeRemainingAsString()
        val masked: String = JavaSource.mask(source)
        val withoutComments: String = JavaSource.withoutComments(source)
        val lineBreaks: List<Int> = this.findLineBreaks(source)

        val maskedLineBreaks: List<Int> = this.findLineBreaks(masked)
        assertEquals(lineBreaks, maskedLineBreaks)

        val lineBreaksWithoutComments: List<Int> = this.findLineBreaks(withoutComments)
        assertEquals(lineBreaks, lineBreaksWithoutComments)

        val maskedKept: Boolean = this.isKeptOrBlanked(source, masked)
        assertTrue(maskedKept, "Masking must keep or blank every character!")

        val keptWithoutComments: Boolean = this.isKeptOrBlanked(source, withoutComments)
        assertTrue(keptWithoutComments, "Removing comments must keep or blank every character!")

        val offsets: List<Int> = JavaSource.findLineCommentOffsets(source)
        val commentsBlanked: Boolean = offsets.all { this.isBlankedLineComment(source, masked, it) }
        assertTrue(commentsBlanked, "Every line comment found must start with // and be blanked to the end of its line!")
    }

    private fun findLineBreaks(text: String): List<Int> {
        return text.indices.filter { text[it] == LINE_BREAK }
    }

    private fun isKeptOrBlanked(source: String, result: String): Boolean {
        if (result.length != source.length) {
            return false
        }

        return source.indices.all { result[it] == source[it] || result[it] == BLANK }
    }

    private fun isBlankedLineComment(source: String, masked: String, offset: Int): Boolean {
        val lineBreak: Int = source.indexOf(LINE_BREAK, offset)
        val lineEnd: Int = if (lineBreak < 0) source.length else lineBreak
        val comment: String = masked.substring(offset, lineEnd)
        return source.startsWith(LINE_COMMENT_START, offset) && comment.all { it == BLANK }
    }
}
