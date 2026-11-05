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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag

// The commentForm task reads every Java source of a module, so any text must be searched without a failure.
@Tag("fuzz")
class CommentFormFuzzTest {

    private companion object {

        const val LINE_BREAK: Char = '\n'
        const val LINE_COMMENT_START: String = "//"

        const val FIRST_LINE_NUMBER: Int = 1
    }

    @FuzzTest
    fun shouldReportOnlyRunsOfLinesThatStartWithAComment(data: FuzzedDataProvider): Unit {
        val source: String = data.consumeRemainingAsString()
        val lines: List<String> = source.split(LINE_BREAK)

        val runs: List<IntRange> = CommentForm.find(source)
        val placed: Boolean = runs.all { this.isRunOfLineComments(it, lines) }
        assertTrue(placed, "Every run must span two or more lines that start with //!")
    }

    private fun isRunOfLineComments(run: IntRange, lines: List<String>): Boolean {
        val runLines: List<String?> = run.map { lines.getOrNull(it - FIRST_LINE_NUMBER) }
        return run.last > run.first && runLines.all { it != null && it.trimStart().startsWith(LINE_COMMENT_START) }
    }
}
