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
import java.util.SortedSet
import java.util.zip.ZipFile
import org.gradle.api.artifacts.ArtifactView
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ResolvableDependencies
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.file.FileCollection
import org.gradle.api.specs.Spec

/**
 * Finds the classes of the MCAV modules that the classes of a plugin use.
 *
 * A class counts when its internal name starts with `me/brandonli/mcav/`; the names are read from the bytes of the class
 * files.
 */
object ModuleClasses {

    private const val CLASS_SUFFIX: String = ".class"

    private val NAME: Regex = Regex("me/brandonli/mcav/[A-Za-z0-9_$/]+")

    /**
     * Returns the artifacts on [classpath] that the modules at [modulePaths] build.
     *
     * @param classpath   the classpath to resolve
     * @param modulePaths the project paths of the modules to keep, such as `:mcav-common`
     * @return the artifacts of those modules
     */
    fun moduleArtifacts(classpath: Configuration, modulePaths: List<String>): FileCollection {
        val modules: Spec<ComponentIdentifier> = Spec { it is ProjectComponentIdentifier && it.projectPath in modulePaths }
        val incoming: ResolvableDependencies = classpath.incoming
        val view: ArtifactView = incoming.artifactView { this.componentFilter(modules) }
        return view.files
    }

    /**
     * Returns the MCAV class names that a class file refers to, in internal form such as `me/brandonli/mcav/MCAV`.
     *
     * @param classFile the bytes of the class file
     * @return the referenced names
     */
    fun referenced(classFile: ByteArray): Set<String> {
        return NAME.findAll(String(classFile, Charsets.ISO_8859_1)).map { it.value }.toSet()
    }

    /**
     * Returns the MCAV class names in [roots], skipping the roots that do not exist.
     *
     * @param roots the class directories and jars to read
     * @return the class names in internal form
     */
    fun available(roots: Iterable<File>): Set<String> {
        return roots.filter { it.exists() }
            .flatMap(this::classNamesIn)
            .filter { NAME.matches(it) }
            .toSet()
    }

    /**
     * Returns the classes of [modules] that the class files in [pluginClasses] refer to.
     *
     * @param pluginClasses the class directories of the plugin
     * @param modules       the class directories and jars of the modules
     * @return the required class names in internal form, sorted
     */
    fun required(pluginClasses: Iterable<File>, modules: Iterable<File>): SortedSet<String> {
        val inModules: Set<String> = this.available(modules)
        return pluginClasses.filter { it.isDirectory }
            .flatMap(this::classFilesIn)
            .flatMap { this.referenced(it.readBytes()) }
            .filter { it in inModules }
            .toSortedSet()
    }

    private fun classNamesIn(root: File): List<String> {
        if (root.isDirectory) {
            return this.classFilesIn(root).map { it.relativeTo(root).invariantSeparatorsPath.removeSuffix(CLASS_SUFFIX) }
        }

        return ZipFile(root).use(this::classNamesInJar)
    }

    private fun classFilesIn(root: File): List<File> {
        return root.walkTopDown()
            .filter { it.isFile && it.name.endsWith(CLASS_SUFFIX) }
            .toList()
    }

    private fun classNamesInJar(jar: ZipFile): List<String> {
        return jar.entries().asSequence()
            .map { it.name }
            .filter { it.endsWith(CLASS_SUFFIX) }
            .map { it.removeSuffix(CLASS_SUFFIX) }
            .toList()
    }
}
