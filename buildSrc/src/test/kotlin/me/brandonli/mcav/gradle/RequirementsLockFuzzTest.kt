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

// The lock file of the Python requirements is written by uv, so any text must be read without a failure.
@Tag("fuzz")
class RequirementsLockFuzzTest {

    private companion object {

        const val MAXIMUM_LOCK_LENGTH: Int = 1024
        const val MAXIMUM_PIN_COUNT: Int = 8
        const val MAXIMUM_NAME_LENGTH: Int = 16

        const val LINE_BREAK: String = "\n"
    }

    @FuzzTest
    fun shouldFindEveryPinThatTheLockLacks(data: FuzzedDataProvider): Unit {
        val lock: String = data.consumeString(MAXIMUM_LOCK_LENGTH)
        val pinCount: Int = data.consumeInt(0, MAXIMUM_PIN_COUNT)
        val versions: Map<String, String> = (0 until pinCount).associate { this.consumeWord(data) to this.consumeWord(data) }
        val pins: List<String> = versions.map { (name, version) -> "$name==$version" }

        val missing: List<String> = RequirementsLock.missingPins(lock, versions)
        val fromTheCatalog: Boolean = pins.containsAll(missing)
        assertTrue(fromTheCatalog, "Every missing pin must be a pin of the catalog!")

        val completed: String = lock + LINE_BREAK + missing.joinToString(LINE_BREAK)
        val stillMissing: List<String> = RequirementsLock.missingPins(completed, versions)
        assertEquals(listOf<String>(), stillMissing)
    }

    private fun consumeWord(data: FuzzedDataProvider): String {
        val text: String = data.consumeString(MAXIMUM_NAME_LENGTH)
        return text.filterNot { it.isWhitespace() }
    }
}
