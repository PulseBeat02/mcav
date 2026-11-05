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
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag

// Coverage exceptions are written by hand, so any file must either be refused as an IllegalArgumentException or be read.
@Tag("fuzz")
class CoverageExceptionsFuzzTest {

    private companion object {

        const val FILE_PREFIX: String = "coverage-exceptions"
        const val FILE_SUFFIX: String = ".txt"

        const val SEPARATOR: String = " | "
    }

    @FuzzTest
    fun shouldReadOnlyEntriesThatTheFileSpellsOut(data: FuzzedDataProvider): Unit {
        val bytes: ByteArray = data.consumeRemainingAsBytes()
        val text: String = String(bytes, Charsets.UTF_8)
        val path: Path = Files.createTempFile(FILE_PREFIX, FILE_SUFFIX)
        try {
            Files.write(path, bytes)
            this.assertEntriesSpelledOut(path.toFile(), text)
        } finally {
            Files.delete(path)
        }
    }

    private fun assertEntriesSpelledOut(file: File, text: String): Unit {
        val exceptions: List<CoverageException> = try {
            CoverageExceptions.read(file)
        } catch (exception: IllegalArgumentException) {
            return
        }

        val spelledOut: Boolean = exceptions.all { text.contains(it.path + SEPARATOR + it.sourceLine + SEPARATOR + it.reason) }
        assertTrue(spelledOut, "Every exception must spell out an entry of the file!")
    }
}
