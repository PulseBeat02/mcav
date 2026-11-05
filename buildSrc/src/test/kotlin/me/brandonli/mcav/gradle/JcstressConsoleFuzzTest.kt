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

// The console of a jcstress run is read after the run, so any lines must be searched without a failure.
@Tag("fuzz")
class JcstressConsoleFuzzTest {

    private companion object {

        const val MAXIMUM_LINE_COUNT: Int = 16
        const val MAXIMUM_LINE_LENGTH: Int = 64
    }

    @FuzzTest
    fun shouldNameEverySkippedTestOnceFromItsLine(data: FuzzedDataProvider): Unit {
        val lineCount: Int = data.consumeInt(0, MAXIMUM_LINE_COUNT)
        val lines: List<String> = (0 until lineCount).map { data.consumeString(MAXIMUM_LINE_LENGTH) }

        val skipped: List<String> = JcstressConsole.skippedTests(lines)
        val named: Boolean = skipped.all { name -> name.isNotEmpty() && lines.any { it.contains(name) } }
        assertTrue(named, "Every skipped test must be named by a line!")

        val distinct: List<String> = skipped.distinct()
        assertEquals(distinct, skipped)
    }
}
