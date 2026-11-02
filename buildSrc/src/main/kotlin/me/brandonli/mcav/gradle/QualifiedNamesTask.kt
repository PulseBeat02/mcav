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
 * Fails the build on every fully qualified type name in Java code.
 *
 * A line marked `// fqn: <why>` may write a name out, for a simple name that clashes with another.
 */
abstract class QualifiedNamesTask : JavaLintTask() {

    /**
     * Finds the written-out type names in [files] with [QualifiedNames] and reports each one to [reportViolation].
     *
     * @param files           the sources to check, sorted by path
     * @param reportViolation receives the file, the line and the name of each written-out type name
     */
    override fun findViolations(files: List<File>, reportViolation: (File, Number, List<String>) -> Unit): Unit {
        for (file in files) {
            val source: String = file.readText()
            val names: List<QualifiedName> = QualifiedNames.find(source)
            names.forEach { reportViolation(file, it.line, listOf(it.name)) }
        }
    }

    /**
     * Returns the template that logs one written-out name, with its file, line and name.
     *
     * @return the template
     */
    override fun diagnosticMessage(): String {
        return "{}:{}: error: {} is written out; import it, or mark the line // fqn: <why> if its simple name clashes."
    }

    /**
     * Returns the message of the build failure.
     *
     * @param count the number of written-out names
     * @return the message
     */
    override fun failureMessage(count: Int): String {
        return "Found $count fully qualified type names in Java code!"
    }

    /**
     * Returns the report text of sources without a written-out name.
     *
     * @param fileCount the number of checked files
     * @return the report text
     */
    override fun successMessage(fileCount: Int): String {
        return "no fully qualified type names in $fileCount files\n"
    }
}
