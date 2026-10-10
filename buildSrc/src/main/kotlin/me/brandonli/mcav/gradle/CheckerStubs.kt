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
    fun forProject(directory: File, projectName: String): List<File> {
        val folders = mutableListOf(directory.resolve(projectName), directory.resolve("shared"))
        if (projectName in setOf("mcav-common", "mcav-bukkit")) folders += directory.resolve("shared-guava")
        if (projectName in setOf("mcav-bukkit", "mcav-plugin")) folders += directory.resolve("shared-paper")
        return folders.filter { it.isDirectory }
    }
}
