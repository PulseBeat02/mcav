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

import java.io.File

/**
 * Reads a coverage exceptions file: one `path | source line | reason` entry on each line, where blank lines and lines
 * starting with `#` are skipped.
 */
object CoverageExceptions {

    private const val SEPARATOR: String = " | "

    private const val COMMENT_PREFIX: String = "#"

    /**
     * Returns the coverage exceptions in [file].
     *
     * @param file the coverage exceptions file
     * @return the exceptions in file order, or an empty list when there is no such file
     * @throws IllegalArgumentException if an entry does not read `path | source line | reason`
     */
    fun read(file: File): List<CoverageException> {
        if (!file.isFile) {
            return emptyList()
        }

        val lines: List<String> = file.readLines()
        val entries: List<String> = lines.map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith(COMMENT_PREFIX) }
        return entries.map { this.parse(file, it) }
    }

    private fun parse(file: File, entry: String): CoverageException {
        val first: Int = entry.indexOf(SEPARATOR)
        val last: Int = entry.lastIndexOf(SEPARATOR)
        val malformed: Boolean = first < 0 || last < first + SEPARATOR.length
        if (malformed) {
            val message: String = "Coverage exception '$entry' in $file must read 'path | source line | reason'!"
            throw IllegalArgumentException(message)
        }

        val path: String = entry.substring(0, first)
        val sourceLine: String = entry.substring(first + SEPARATOR.length, last)
        val reason: String = entry.substring(last + SEPARATOR.length)
        return CoverageException(path, sourceLine, reason)
    }
}
