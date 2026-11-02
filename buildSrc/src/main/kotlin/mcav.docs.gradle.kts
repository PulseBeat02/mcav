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

import me.brandonli.mcav.gradle.JupyterBook
import me.brandonli.mcav.gradle.PythonEnvironment

plugins {
    base
}

val excludedDocsSources: List<String> =
    listOf("build/**", "_build/**", ".jupyter_cache/**", "**/__pycache__/**", "build.gradle.kts", "requirements*", "conf.py")
val docsOutputPath: String = "html"

val pythonEnvironment: TaskProvider<PythonEnvironment> = rootProject.tasks.named<PythonEnvironment>("installPythonEnvironment")
val buildDocs: TaskProvider<JupyterBook> = tasks.register<JupyterBook>("buildDocs") {
    this.group = "documentation"
    this.description = "Builds the documentation HTML; a Sphinx warning fails it"
    this.useEnvironment(pythonEnvironment)
    this.sources.from(fileTree(projectDir) {
        this.exclude(excludedDocsSources)
    })
    this.outputDirectory = layout.buildDirectory.dir(docsOutputPath)
}

tasks.named("assemble") {
    this.dependsOn(buildDocs)
}
