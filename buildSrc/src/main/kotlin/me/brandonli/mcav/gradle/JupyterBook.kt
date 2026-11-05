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
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

/**
 * Builds the HTML site of a Jupyter Book with the Python of a [PythonEnvironment].
 *
 * The task stages [sources] as the book, configures Sphinx, builds the book with warnings as errors and syncs the HTML
 * into [outputDirectory].
 */
@CacheableTask
abstract class JupyterBook : PythonTask() {

    private companion object {

        const val BOOK_DIRECTORY: String = "book"
        const val SITE_DIRECTORY: String = "site"
        const val HTML_DIRECTORY: String = "site/_build/html"

        const val ENTRY_POINT: String = "from jupyter_book.cli.main import main\nmain()"

        const val JUPYTER_CONFIGURATION_VARIABLE: String = "JUPYTER_CONFIG_DIR"
        const val JUPYTER_CONFIGURATION_DIRECTORY: String = "jupyter"

        private val CONFIGURE_COMMAND: List<String> = listOf("config", "sphinx", BOOK_DIRECTORY)
        private val BUILD_COMMAND: List<String> = listOf(
            "build",
            BOOK_DIRECTORY,
            "--path-output",
            SITE_DIRECTORY,
            "--warningiserror",
            "--keep-going",
            "--all"
        )
    }

    /**
     * The notebooks and the other files of the book.
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    /**
     * The directory that receives the HTML site.
     */
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    /**
     * The service that stages the book and publishes the site.
     */
    @get:Inject
    protected abstract val files: FileSystemOperations

    /**
     * The service that runs Jupyter Book.
     */
    @get:Inject
    protected abstract val processes: ExecOperations

    /**
     * Stages the book, builds it and publishes its HTML site.
     *
     * @throws GradleException if Jupyter Book fails or warns
     */
    @TaskAction
    fun build(): Unit {
        val staging: File = this.temporaryDir
        this.files.delete { this.delete(staging) }

        this.stageNotebooks(staging)
        this.runJupyterBook(staging, CONFIGURE_COMMAND)
        this.runJupyterBook(staging, BUILD_COMMAND)
        this.publishSite(staging)
    }

    private fun stageNotebooks(staging: File): Unit {
        val book: File = staging.resolve(BOOK_DIRECTORY)
        val notebooks: ConfigurableFileCollection = this.sources
        this.files.sync {
            this.from(notebooks)
            this.into(book)
        }
    }

    private fun runJupyterBook(staging: File, arguments: List<String>): Unit {
        val python: File = this.pythonExecutable.get().asFile
        val matplotlibConfiguration: String = staging.resolve(MATPLOTLIB_CONFIGURATION_DIRECTORY).absolutePath
        val jupyterConfiguration: String = staging.resolve(JUPYTER_CONFIGURATION_DIRECTORY).absolutePath
        this.processes.exec {
            this.workingDir(staging)
            this.commandLine(listOf(python, "-c", ENTRY_POINT) + arguments)
            this.environment(NO_USER_SITE_VARIABLE, ENABLED)
            this.environment(NO_BYTECODE_VARIABLE, ENABLED)
            this.environment(HASH_SEED_VARIABLE, FIXED_HASH_SEED)
            this.environment(MATPLOTLIB_CONFIGURATION_VARIABLE, matplotlibConfiguration)
            this.environment(JUPYTER_CONFIGURATION_VARIABLE, jupyterConfiguration)
        }
    }

    private fun publishSite(staging: File): Unit {
        val site: File = staging.resolve(HTML_DIRECTORY)
        val output: DirectoryProperty = this.outputDirectory
        this.files.sync {
            this.from(site)
            this.into(output)
        }
    }
}
