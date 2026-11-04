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
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CheckerStubsTest {

    private companion object {

        const val BUKKIT_PROJECT: String = "mcav-bukkit"
        const val MOD_PROJECT: String = "mcav-mod"

        const val SHARED_STUBS: String = "shared"
        const val GUAVA_STUBS: String = "shared-guava"
    }

    @TempDir
    lateinit var directory: Path

    @Test
    fun shouldGiveTheSharedGuavaAndPaperStubsOnlyToTheirConsumers(): Unit {
        val projects: List<String> = listOf("mcav-common", BUKKIT_PROJECT, "mcav-plugin", "mcav-browser", "mcav-discord", "mcav-http",
            "mcav-installer", "mcav-lwjgl", MOD_PROJECT, "mcav-vm", "mcav-vnc", "mcav-voicechat", "mcav-jcstress")
        val folders: List<String> = projects + listOf(SHARED_STUBS, GUAVA_STUBS, "shared-paper")
        folders.forEach { this.directory.resolve(it).toFile().mkdirs() }

        val expected: Map<String, List<String>> = projects.associateWith(this::expectedStubsOf)
        val stubs: Map<String, List<String>> = projects.associateWith(this::stubNamesOf)
        assertEquals(expected, stubs)
    }

    @Test
    fun shouldNotPassStubFoldersThatDoNotExistToTheChecker(): Unit {
        val withoutFolders: List<String> = this.stubNamesOf(BUKKIT_PROJECT)
        assertEquals(emptyList<String>(), withoutFolders)

        this.directory.resolve(GUAVA_STUBS).toFile().mkdirs()

        val withGuava: List<String> = this.stubNamesOf(BUKKIT_PROJECT)
        assertEquals(listOf(GUAVA_STUBS), withGuava)

        val otherProject: List<String> = this.stubNamesOf(MOD_PROJECT)
        assertEquals(emptyList<String>(), otherProject)
    }

    @Test
    fun shouldPassNoStubsArgumentWithoutStubFolders(): Unit {
        val arguments: List<String> = CheckerStubs.argumentsFor(this.directory.toFile(), MOD_PROJECT)
        assertEquals(emptyList<String>(), arguments)
    }

    @Test
    fun shouldJoinTheStubFoldersIntoOneStubsArgument(): Unit {
        val projectStubs: File = this.directory.resolve(MOD_PROJECT).toFile()
        val sharedStubs: File = this.directory.resolve(SHARED_STUBS).toFile()
        projectStubs.mkdirs()
        sharedStubs.mkdirs()

        val arguments: List<String> = CheckerStubs.argumentsFor(this.directory.toFile(), MOD_PROJECT)
        val expected: List<String> = listOf("-Astubs=$projectStubs${File.pathSeparator}$sharedStubs")
        assertEquals(expected, arguments)
    }

    private fun expectedStubsOf(project: String): List<String> {
        return when (project) {
            "mcav-common" -> listOf(project, SHARED_STUBS, GUAVA_STUBS)
            BUKKIT_PROJECT -> listOf(project, SHARED_STUBS, GUAVA_STUBS, "shared-paper")
            "mcav-plugin" -> listOf(project, SHARED_STUBS, "shared-paper")
            else -> listOf(project, SHARED_STUBS)
        }
    }

    private fun stubNamesOf(project: String): List<String> {
        val stubs: List<File> = CheckerStubs.foldersFor(this.directory.toFile(), project)
        return stubs.map { it.name }
    }
}
