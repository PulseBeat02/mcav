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
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Fails the build on every line of a module that its tests do not fully cover, unless a coverage exception excuses it.
 *
 * The task reads the JaCoCo XML report and the module's coverage exceptions file. It also fails on stale exceptions that
 * excuse no uncovered line, and skips itself with a warning when the tests ran with a filter.
 */
abstract class CoverageLintTask : DefaultTask() {

    /**
     * The JaCoCo XML report of the module's tests.
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val report: ConfigurableFileCollection

    /**
     * The directory of the module's main sources.
     */
    @get:Internal
    abstract val sourceDirectory: DirectoryProperty

    /**
     * The module's coverage exceptions file.
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val exceptionsFile: ConfigurableFileCollection

    /**
     * Whether the tests ran with a filter, so that the report covers only part of the code.
     */
    @get:Input
    abstract val testsFiltered: Property<Boolean>

    /**
     * The path of the project, for the warning that a filtered run is skipped.
     */
    @get:Internal
    abstract val projectPath: Property<String>

    /**
     * Logs every uncovered line and every stale exception, and fails if it finds one.
     *
     * @throws GradleException          if a line is not fully covered or an exception is stale
     * @throws IllegalArgumentException if an entry of the exceptions file is malformed
     */
    @TaskAction
    fun lint(): Unit {
        if (this.testsFiltered.get()) {
            this.logger.warn(
                "Skipped coverageLint of {}, since the tests ran with a filter and the report covers only part of the code.",
                this.projectPath.get()
            )
            return
        }

        val exceptionFile: File = this.exceptionsFile.singleFile
        val exceptions: List<CoverageException> = CoverageExceptions.read(exceptionFile)
        val reportFile: File = this.report.singleFile
        val sources: File = this.sourceDirectory.get().asFile
        val gaps: List<String> = CoverageReport.findGaps(reportFile, sources, exceptions)
        for (gap in gaps) {
            this.logger.error("{}", gap)
        }

        val staleExceptions: List<CoverageException> = exceptions.filterNot { it.used }
        for (staleException in staleExceptions) {
            this.logger.error(
                "{}: error: '{} | {}' matches no uncovered line, so remove it.",
                exceptionFile,
                staleException.path,
                staleException.sourceLine
            )
        }

        if (gaps.isNotEmpty() || staleExceptions.isNotEmpty()) {
            val message: String =
                "${gaps.size} lines are not fully covered by tests and ${staleExceptions.size} coverage exceptions are stale!"
            throw GradleException(message)
        }
    }
}
