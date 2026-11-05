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

/**
 * Reads and writes the requirements lock of a Python environment.
 */
object RequirementsLock {

    /**
     * The separator between the name and the version of a pinned requirement.
     */
    const val PIN_SEPARATOR: String = "=="

    private const val PIN_END: Char = ' '

    private const val JAVADOC_LINE_PREFIX: String = " *"
    private const val COMMENT_PREFIX: String = "#"
    private const val LINE_SEPARATOR: String = "\n"

    /**
     * Returns the pins of [versions] that [lock] does not hold, as `name==version`.
     *
     * @param lock     the text of the lock
     * @param versions the version of each requirement that must be pinned
     * @return the missing pins, or an empty list when the lock holds them all
     */
    fun missingPins(lock: String, versions: Map<String, String>): List<String> {
        val lines: List<String> = lock.lines()
        val locked: Set<String> = lines.map { it.substringBefore(PIN_END) }
            .toSet()
        return versions.map { (name, version) -> "$name$PIN_SEPARATOR$version" }
            .filterNot { it in locked }
    }

    /**
     * Returns the comment that opens a requirements lock, made from the license header of the sources.
     *
     * The first and last lines of [licenseHeader] open and close the block comment and are dropped; every other line
     * trades its leading ` *` for `#`, and a blank line follows the comment.
     *
     * @param licenseHeader the license header, a Javadoc-style block comment
     * @return the lock comment
     */
    fun headerOf(licenseHeader: String): String {
        val lines: List<String> = licenseHeader.trimEnd().lines()
        val text: List<String> = lines.drop(1).dropLast(1)
        return text.joinToString(LINE_SEPARATOR, postfix = LINE_SEPARATOR + LINE_SEPARATOR, transform = this::commentOf)
    }

    private fun commentOf(line: String): String {
        return COMMENT_PREFIX + line.removePrefix(JAVADOC_LINE_PREFIX).trimEnd()
    }
}
