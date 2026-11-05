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

// Fuzz test sources are read when the build is configured, so any text must be searched without a failure.
@Tag("fuzz")
class FuzzTargetsFuzzTest {

    private companion object {

        const val ANNOTATION: String = "@FuzzTest"

        val WORD: Regex = Regex("\\w+")
    }

    @FuzzTest
    fun shouldFindOnlyMethodsThatTheSourceAnnotates(data: FuzzedDataProvider): Unit {
        val source: String = data.consumeRemainingAsString()
        val annotations: Int = source.windowed(ANNOTATION.length).count { it == ANNOTATION }

        val methods: List<String> = FuzzTargets.findMethods(source)
        val words: Boolean = methods.all { WORD.matches(it) && source.contains(it) }
        assertTrue(words, "Every method must be a word of the source!")

        val annotated: Boolean = methods.size <= annotations
        assertTrue(annotated, "No more methods than annotations may be found!")
    }
}
