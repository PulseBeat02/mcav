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
import org.junit.jupiter.api.Tag

// The Paper version can be set from the command line, so any text must either fail as an IllegalArgumentException or round-trip.
@Tag("fuzz")
class PaperVersionFuzzTest {

    @FuzzTest
    fun shouldReadBackEveryVersionItAccepts(data: FuzzedDataProvider): Unit {
        val text: String = data.consumeRemainingAsString()
        val version: PaperVersion = try {
            PaperVersion.parse(text)
        } catch (exception: IllegalArgumentException) {
            return
        }

        val written: String = "${version.minecraft}.build.${version.build}"
        val readBack: PaperVersion = PaperVersion.parse(written)
        assertEquals(version, readBack)
    }
}
