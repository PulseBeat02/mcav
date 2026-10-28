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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BuildFilesTest {

    private companion object {

        const val REPOSITORY_DIRECTORY: String = ".."
        const val SETTINGS_SCRIPT: String = "settings.gradle.kts"
        const val BUILD_SCRIPT: String = "build.gradle.kts"
        const val BUILD_SOURCE_SETTINGS_SCRIPT: String = "buildSrc/settings.gradle.kts"
        const val BUILD_SOURCE_BUILD_SCRIPT: String = "buildSrc/build.gradle.kts"
        const val CATALOG_PATH: String = "gradle/libs.versions.toml"

        const val SOURCE_DIRECTORY: String = "src"
        const val KOTLIN_EXTENSION: String = ".kt"
        const val SCRIPT_EXTENSION: String = ".kts"
        const val HEADER_FILE: String = "HEADER"
        const val BUILD_SOURCE_DIRECTORY: String = "buildSrc"

        val MODULE_ENTRY: Regex = Regex("""^ {4}"([\w-]+)",$""", RegexOption.MULTILINE)
        const val DEPENDENCY_GROUPS: String = """( {4}.+\n)+(\n( {4}.+\n)+)*"""
        val MODULE_SCRIPT_LAYOUT: Regex =
            Regex("""((import [\w.]+\n)+\n)?plugins \{\n( {4}id\("mcav\.[\w-]+"\)\n)+}\n(\ndependencies \{\n$DEPENDENCY_GROUPS}\n)?""")
        val CATALOG_SECTION_HEADER: Regex = Regex("""^\[\w+]\n""", RegexOption.MULTILINE)
        val CATALOG_ENTRY: Regex = Regex("""^([\w-]+) = """, RegexOption.MULTILINE)
    }

    private val root: File = File(REPOSITORY_DIRECTORY)

    private val modules: List<String> = MODULE_ENTRY
        .findAll(this.root.resolve(SETTINGS_SCRIPT).readText())
        .map { it.groupValues[1] }
        .toList()

    private val moduleScripts: List<File> = this.modules.map { this.root.resolve("$it/build.gradle.kts") }

    private val buildScripts: List<File> = listOf(
        SETTINGS_SCRIPT,
        BUILD_SCRIPT,
        BUILD_SOURCE_SETTINGS_SCRIPT,
        BUILD_SOURCE_BUILD_SCRIPT
    ).map { this.root.resolve(it) } + this.moduleScripts

    private val sources: List<File> = File(SOURCE_DIRECTORY).walk()
        .filter { it.isFile && (it.name.endsWith(KOTLIN_EXTENSION) || it.name.endsWith(SCRIPT_EXTENSION)) }
        .toList()

    @Test
    fun shouldOpenEverySourceOfTheBuildWithTheLicenseHeaderAndNoBuildScript(): Unit {
        val header: String = this.root.resolve(HEADER_FILE).readText()
        val sourcesWithoutHeader: List<String> = this.sources.filterNot { it.readText().startsWith("$header\n") }.map { it.path }
        assertEquals(listOf<String>(), sourcesWithoutHeader)

        val scriptsWithHeader: List<String> = this.buildScripts.filter { it.readText().contains(header) }.map { it.path }
        assertEquals(listOf<String>(), scriptsWithHeader)
    }

    @Test
    fun shouldOnlyApplyConventionsAndListDependenciesInEveryModuleScript(): Unit {
        val offenders: List<String> = this.moduleScripts.filterNot { MODULE_SCRIPT_LAYOUT.matches(it.readText()) }.map { it.path }
        assertEquals(listOf<String>(), offenders)
    }

    @Test
    fun shouldIncludeEveryModuleInOrderAndSortEverySectionOfTheCatalog(): Unit {
        val directories: Array<File> =
            this.root.listFiles { file -> file.name != BUILD_SOURCE_DIRECTORY && file.resolve(BUILD_SCRIPT).isFile }!!
        val sortedModules: List<String> = directories.map { it.name }.sorted()
        assertEquals(sortedModules, this.modules)

        val sections: List<String> = this.root.resolve(CATALOG_PATH).readText().split(CATALOG_SECTION_HEADER)
        val names: List<List<String>> = sections.drop(1).map(this::catalogNamesIn)
        val sortedNames: List<List<String>> = names.map { it.sorted() }
        assertEquals(sortedNames, names)
    }

    @Test
    fun shouldKeepEveryLineOfTheBuildWithin140Columns(): Unit {
        val files: List<File> = this.sources + this.buildScripts
        val longLines: List<String> = files.flatMap(this::longLinesIn)
        assertEquals(listOf<String>(), longLines)
    }

    private fun catalogNamesIn(section: String): List<String> {
        return CATALOG_ENTRY.findAll(section)
            .map { it.groupValues[1] }
            .toList()
    }

    private fun longLinesIn(file: File): List<String> {
        val lines: List<String> = file.readLines()
        return lines.withIndex()
            .filter { it.value.length > 140 }
            .map { "${file.path}:${it.index + 1}" }
    }
}
