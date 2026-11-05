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
 * Finds the comments in Java source that are written as runs of `//` lines instead of one `/* */` block.
 *
 * A run is two or more lines in a row that each hold nothing but a `//` comment. A `//` after code on its line is not
 * such a line, and neither is an `// fqn:` marker or a tool directive: `// spotless:off`, `// spotless:on`, `// NOLINT`
 * or `// @formatter:`. Two slashes inside a block comment, Javadoc or a literal start no comment.
 */
object CommentForm {

    private const val NEW_LINE: Char = '\n'

    private const val FIRST_LINE_NUMBER: Int = 1

    private val DIRECTIVE: Regex = Regex("""//[ \t]*(?:fqn:|spotless:(?:off|on)|NOLINT|@formatter:)""")

    /**
     * Returns every run of `//` comment lines in [source], in source order.
     *
     * @param source the Java source
     * @return the lines of each run, from its first to its last line
     */
    fun find(source: String): List<IntRange> {
        val lines: List<Int> = JavaSource.findLineCommentOffsets(source)
            .filter { this.isOwnLine(source, it) }
            .filterNot { DIRECTIVE.matchesAt(source, it) }
            .map { this.lineOf(source, it) }
        return this.findRuns(lines)
    }

    private fun isOwnLine(source: String, offset: Int): Boolean {
        val lineStart: Int = source.lastIndexOf(NEW_LINE, offset) + 1
        val indentation: CharSequence = source.subSequence(lineStart, offset)
        return indentation.isBlank()
    }

    private fun lineOf(source: String, offset: Int): Int {
        val before: CharSequence = source.subSequence(0, offset)
        val newLines: Int = before.count { it == NEW_LINE }
        return newLines + FIRST_LINE_NUMBER
    }

    private fun findRuns(lines: List<Int>): List<IntRange> {
        val runs: MutableList<IntRange> = mutableListOf<IntRange>()
        for (line in lines) {
            val previous: IntRange? = runs.lastOrNull()
            if (previous != null && previous.last + 1 == line) {
                runs[runs.lastIndex] = previous.first..line
                continue
            }

            runs += line..line
        }

        return runs.filter { it.last > it.first }
    }
}
