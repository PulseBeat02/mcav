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

// The variableNames task parses every Java source with javac, so any text, compilable or not, must be searched without a failure.
@Tag("fuzz")
class VariableNamesFuzzTest {

    private companion object {

        const val LINE_BREAK: Char = '\n'

        const val FIRST_LINE_NUMBER: Long = 1L
    }

    @FuzzTest
    fun shouldReportOnlyNamesOnALineOfAnySource(data: FuzzedDataProvider): Unit {
        val source: String = data.consumeRemainingAsString()
        val lineBreaks: Int = source.count { it == LINE_BREAK }
        val lines: LongRange = FIRST_LINE_NUMBER..lineBreaks + FIRST_LINE_NUMBER

        val names: List<UndescriptiveName> = VariableNames.find(source)
        val placed: Boolean = names.all { it.line in lines && source.contains(it.name) }
        assertTrue(placed, "Every name must be in the source, on one of its lines!")
    }
}
