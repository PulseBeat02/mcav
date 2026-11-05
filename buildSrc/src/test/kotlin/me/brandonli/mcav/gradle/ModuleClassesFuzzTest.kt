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

// The class files of a plugin are read byte by byte, so any bytes must be searched without a failure.
@Tag("fuzz")
class ModuleClassesFuzzTest {

    private companion object {

        const val MCAV_PACKAGE: String = "me/brandonli/mcav/"
    }

    @FuzzTest
    fun shouldFindOnlyMcavClassesThatTheBytesName(data: FuzzedDataProvider): Unit {
        val bytes: ByteArray = data.consumeRemainingAsBytes()
        val text: String = String(bytes, Charsets.ISO_8859_1)

        val classes: Set<String> = ModuleClasses.referenced(bytes)
        val named: Boolean = classes.all { it.startsWith(MCAV_PACKAGE) && text.contains(it) }
        assertTrue(named, "Every class must be an MCAV class that the bytes name!")
    }
}
