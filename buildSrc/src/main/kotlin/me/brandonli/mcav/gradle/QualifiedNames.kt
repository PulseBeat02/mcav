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
 * Finds the fully qualified type names written out in Java code.
 *
 * A name counts when it starts with a common top-level package, such as `java` or `org`, and ends in a capitalized type.
 * Comments, literals, `package` and `import` lines, and lines marked `// fqn: <why>` are skipped.
 */
object QualifiedNames {

    private const val NEW_LINE: Char = '\n'

    private const val FIRST_LINE_NUMBER: Int = 1

    private const val ROOT_SEPARATOR: String = "|"

    private const val NAME_GROUP: Int = 1

    private val ROOTS: List<String> = listOf(
        "java", "javax", "jakarta", "jdk", "sun", "com", "org", "net", "io", "me", "uk", "de", "xyz", "it", "dev", "info"
    )

    private val NAME: Regex = Regex("(?<![\\w.$])((?:${ROOTS.joinToString(ROOT_SEPARATOR)})\\.(?:[a-z_][a-z0-9_]*\\.)+[A-Z][\\w$]*)")

    private val MARKER: Regex = Regex("//\\s*fqn:\\s*\\S")

    private val DECLARATION: Regex = Regex("^\\s*(package|import)\\s")

    /**
     * Returns every fully qualified type name in the code of [source], in source order.
     *
     * @param source the Java source
     * @return the written-out names with their lines
     */
    fun find(source: String): List<QualifiedName> {
        val lines: List<String> = source.split(NEW_LINE)
        val masked: String = JavaSource.mask(source)
        val code: List<String> = masked.split(NEW_LINE)
        val found: MutableList<QualifiedName> = mutableListOf<QualifiedName>()
        for ((index, line) in code.withIndex()) {
            val skipped: Boolean = DECLARATION.containsMatchIn(line) || MARKER.containsMatchIn(lines[index])
            if (skipped) {
                continue
            }
            val number: Int = index + FIRST_LINE_NUMBER
            NAME.findAll(line).forEach { found += QualifiedName(number, it.groupValues[NAME_GROUP]) }
        }

        return found
    }
}
