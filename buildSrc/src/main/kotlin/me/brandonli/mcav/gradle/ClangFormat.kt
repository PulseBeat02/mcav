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
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault

/**
 * Checks or rewrites C++ sources with the clang-format version that this build pins, in the style of their `.clang-format`
 * file.
 *
 * The task checks the clang-format version first. Then it either fails on every source that clang-format would change,
 * or rewrites the sources in place.
 */
@DisableCachingByDefault(because = "clang-format checks or rewrites the sources in place")
abstract class ClangFormat : DefaultTask() {

    private companion object {

        const val TOOL: String = "clang-format"
        const val VERSION_PREFIX: String = "clang-format version "
    }

    /**
     * The sources to format.
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    /**
     * The clang-format executable.
     */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val clangFormat: RegularFileProperty

    /**
     * The clang-format version that [clangFormat] must report.
     */
    @get:Input
    abstract val clangFormatVersion: Property<String>

    /**
     * Whether the task rewrites the sources in place, instead of failing on unformatted ones.
     */
    @get:Input
    abstract val rewrite: Property<Boolean>

    /**
     * The service that runs clang-format.
     */
    @get:Inject
    protected abstract val processes: ExecOperations

    /**
     * Checks the clang-format version, then checks or rewrites the sources.
     *
     * @throws GradleException if clang-format cannot run, reports another version or finds an unformatted source in a check
     */
    @TaskAction
    fun runClangFormat(): Unit {
        val executable: File = this.clangFormat.get().asFile
        ToolVersions.verify(this.processes, executable, TOOL, this.clangFormatVersion.get(), VERSION_PREFIX)

        val mode: List<String> = if (this.rewrite.get()) listOf("-i") else listOf("--dry-run", "--Werror")
        val sourcePaths: List<String> = this.sources.files.sorted().map { it.path }
        this.processes.exec {
            this.commandLine(listOf(executable.path) + mode + "--style=file" + sourcePaths)
        }
    }
}
