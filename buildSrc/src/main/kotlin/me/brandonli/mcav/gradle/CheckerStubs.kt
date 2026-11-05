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

internal object CheckerStubs {

    private const val SHARED_STUBS: String = "shared"
    private const val GUAVA_STUBS: String = "shared-guava"
    private const val PAPER_STUBS: String = "shared-paper"

    private val GUAVA_PROJECTS: Set<String> = setOf("mcav-common", "mcav-bukkit")
    private val PAPER_PROJECTS: Set<String> = setOf("mcav-bukkit", "mcav-plugin")

    fun foldersFor(directory: File, projectName: String): List<File> {
        val folders: MutableList<File> = mutableListOf(directory.resolve(projectName), directory.resolve(SHARED_STUBS))
        if (projectName in GUAVA_PROJECTS) {
            folders += directory.resolve(GUAVA_STUBS)
        }

        if (projectName in PAPER_PROJECTS) {
            folders += directory.resolve(PAPER_STUBS)
        }

        return folders.filter { it.isDirectory }
    }

    fun argumentsFor(directory: File, projectName: String): List<String> {
        val folders: List<File> = this.foldersFor(directory, projectName)
        if (folders.isEmpty()) {
            return emptyList()
        }

        val path: String = folders.joinToString(File.pathSeparator)
        return listOf("-Astubs=$path")
    }
}
