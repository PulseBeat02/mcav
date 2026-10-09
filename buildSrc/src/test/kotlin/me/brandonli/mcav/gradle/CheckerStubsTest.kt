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

import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CheckerStubsTest {

    @TempDir
    lateinit var directory: Path

    @Test
    fun onlyTheExistingConsumersReceiveTheSharedGuavaAndPaperStubs() {
        val projects = listOf("mcav-common", "mcav-bukkit", "mcav-plugin", "mcav-browser", "mcav-discord", "mcav-http",
            "mcav-installer", "mcav-lwjgl", "mcav-mod", "mcav-vm", "mcav-vnc", "mcav-voicechat", "mcav-jcstress")
        (projects + listOf("shared", "shared-guava", "shared-paper")).forEach { directory.resolve(it).toFile().mkdirs() }
        projects.forEach { project ->
            val expected = when (project) {
                "mcav-common" -> listOf(project, "shared", "shared-guava")
                "mcav-bukkit" -> listOf(project, "shared", "shared-guava", "shared-paper")
                "mcav-plugin" -> listOf(project, "shared", "shared-paper")
                else -> listOf(project, "shared")
            }
            assertEquals(expected, CheckerStubs.forProject(directory.toFile(), project).map { it.name })
        }
    }

    @Test
    fun nonexistentStubFoldersAreNotPassedToTheChecker() {
        assertEquals(emptyList<String>(), CheckerStubs.forProject(directory.toFile(), "mcav-bukkit").map { it.name })
        directory.resolve("shared-guava").toFile().mkdirs()
        assertEquals(listOf("shared-guava"), CheckerStubs.forProject(directory.toFile(), "mcav-bukkit").map { it.name })
        assertEquals(emptyList<String>(), CheckerStubs.forProject(directory.toFile(), "mcav-mod").map { it.name })
    }
}
