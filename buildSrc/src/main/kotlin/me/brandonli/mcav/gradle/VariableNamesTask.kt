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
import java.util.SortedMap
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input

/**
 * Fails the build on every Java variable whose name says nothing about what it holds, unless [publishedNames] lists it.
 */
abstract class VariableNamesTask : JavaLintTask() {

    /**
     * The variables that keep their names because public API publishes them, as `<package>.<type>#<name>`; empty unless set.
     */
    @get:Input
    abstract val publishedNames: SetProperty<String>

    /**
     * Finds the undescriptive names in [files] with [VariableNames] and reports each unpublished one to [reportViolation].
     *
     * @param files           the sources to check, sorted by path
     * @param reportViolation receives the file, the line, the kind and the name of each variable
     */
    override fun findViolations(files: List<File>, reportViolation: (File, Number, List<String>) -> Unit): Unit {
        val published: Set<String> = this.publishedNames.get()
        val namesByFile: Map<File, List<UndescriptiveName>> = VariableNames.findInFiles(files)
        val sortedNames: SortedMap<File, List<UndescriptiveName>> = namesByFile.toSortedMap()
        for ((file, names) in sortedNames) {
            val unpublished: List<UndescriptiveName> = names.filter { it.member !in published }
            unpublished.forEach { found -> reportViolation(file, found.line, listOf(found.kind, found.name)) }
        }
    }

    /**
     * Returns the template that logs one undescriptive name, with its file, line, kind and name.
     *
     * @return the template
     */
    override fun diagnosticMessage(): String {
        return "{}:{}: error: the {} {} says nothing; name what it holds."
    }

    /**
     * Returns the message of the build failure.
     *
     * @param count the number of undescriptive names
     * @return the message
     */
    override fun failureMessage(count: Int): String {
        return "Found $count undescriptive variable names!"
    }

    /**
     * Returns the report text of sources without an undescriptive name.
     *
     * @param fileCount the number of checked files
     * @return the report text
     */
    override fun successMessage(fileCount: Int): String {
        return "no undescriptive variable name in $fileCount files\n"
    }
}
