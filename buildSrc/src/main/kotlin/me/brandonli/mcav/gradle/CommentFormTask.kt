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
 * Fails the build on every comment in Java code that is written as a run of `//` lines.
 *
 * A comment longer than one line is one `/* */` block; an `// fqn:` marker and a tool directive such as
 * `// spotless:off` stay line comments.
 */
abstract class CommentFormTask : JavaLintTask() {

    /**
     * Finds the runs of `//` comment lines in [files] with [CommentForm] and reports each one to [reportViolation].
     *
     * @param files           the sources to check, sorted by path
     * @param reportViolation receives the file, the first line and the last line of each run
     */
    override fun findViolations(files: List<File>, reportViolation: (File, Number, List<String>) -> Unit): Unit {
        for (file in files) {
            val source: String = file.readText()
            val runs: List<IntRange> = CommentForm.find(source)
            runs.forEach { reportViolation(file, it.first, listOf("${it.last}")) }
        }
    }

    /**
     * Returns the template that logs one run, with its file, its first line and its last line.
     *
     * @return the template
     */
    override fun diagnosticMessage(): String {
        return "{}:{}: error: the // lines up to line {} make one comment; write it as one /* */ block, or cut it to one // line."
    }

    /**
     * Returns the message of the build failure.
     *
     * @param count the number of runs
     * @return the message
     */
    override fun failureMessage(count: Int): String {
        return "Found $count comments written as runs of // lines!"
    }

    /**
     * Returns the report text of sources without a run.
     *
     * @param fileCount the number of checked files
     * @return the report text
     */
    override fun successMessage(fileCount: Int): String {
        return "no run of // comment lines in $fileCount files\n"
    }
}
