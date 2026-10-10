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

import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

@CacheableTask
abstract class JupyterBook : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val requirements: RegularFileProperty

    @get:Input
    abstract val pythonVersion: Property<String>

    @get:Input
    abstract val platform: Property<String>

    @get:Internal
    abstract val pythonExecutable: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Inject
    protected abstract val files: FileSystemOperations

    @get:Inject
    protected abstract val processes: ExecOperations

    @TaskAction
    fun build() {
        val staging = temporaryDir
        files.delete { delete(staging) }
        val book = staging.resolve("book")
        files.sync {
            from(sources)
            into(book)
        }
        val commands = listOf(
            listOf("config", "sphinx", "book"),
            listOf("build", "book", "--path-output", "site", "--warningiserror", "--keep-going", "--all")
        )
        commands.forEach { arguments ->
            processes.exec {
                workingDir(staging)
                commandLine(listOf(pythonExecutable.get().asFile, "-c", "from jupyter_book.cli.main import main\nmain()") + arguments)
                environment("PYTHONNOUSERSITE", "1")
                environment("PYTHONDONTWRITEBYTECODE", "1")
                environment("PYTHONHASHSEED", "0")
                environment("MPLCONFIGDIR", staging.resolve("matplotlib").absolutePath)
                environment("JUPYTER_CONFIG_DIR", staging.resolve("jupyter").absolutePath)
            }
        }
        files.sync {
            from(staging.resolve("site/_build/html"))
            into(outputDirectory)
        }
    }
}
