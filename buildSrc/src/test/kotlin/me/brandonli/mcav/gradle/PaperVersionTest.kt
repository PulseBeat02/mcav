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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PaperVersionTest {

    private companion object {

        const val MINECRAFT_VERSION: String = "26.3"
        const val OTHER_MINECRAFT_VERSION: String = "26.4"

        const val BUILD: Int = 49
        const val OTHER_BUILD: Int = 50

        const val BUILD_FILE_NAME: String = "paper-26.3-49.jar"
        const val RELEASE_FILE_NAME: String = "paper-26.4-123.jar"
    }

    @Test
    fun shouldSupplyTheExactServerBuildAndArtifactNameOfThePinnedAlphaVersion(): Unit {
        val version: PaperVersion = PaperVersion.parse("26.3.build.49-alpha")
        val minecraft: String = version.minecraft
        assertEquals(MINECRAFT_VERSION, minecraft)

        val build: Int = version.build
        assertEquals(BUILD, build)

        val fileName: String = version.fileName
        assertEquals(BUILD_FILE_NAME, fileName)

        val releaseFileName: String = PaperVersion.parse("26.4.build.123").fileName
        assertEquals(RELEASE_FILE_NAME, releaseFileName)
    }

    @Test
    fun shouldRejectAVersionWithoutAnExplicitBuild(): Unit {
        val versions: List<String> = listOf(MINECRAFT_VERSION, "26.3.build.latest", "26.3.build.2147483648")
        val messages: Map<String, String?> = versions.associateWith(this::rejectionOf)
        val expected: Map<String, String?> = versions.associateWith { "Paper version $it must name a build!" }
        assertEquals(expected, messages)
    }

    @Test
    fun shouldEqualOnlyAVersionOfTheSameMinecraftVersionAndBuild(): Unit {
        val version: PaperVersion = PaperVersion(MINECRAFT_VERSION, BUILD)
        val same: PaperVersion = PaperVersion(MINECRAFT_VERSION, BUILD)
        val otherMinecraft: PaperVersion = PaperVersion(OTHER_MINECRAFT_VERSION, BUILD)
        val otherBuild: PaperVersion = PaperVersion(MINECRAFT_VERSION, OTHER_BUILD)

        assertEquals(version, version)
        assertEquals(same, version)
        assertNotEquals(otherMinecraft, version)
        assertNotEquals(otherBuild, version)
        assertNotEquals(version, MINECRAFT_VERSION)

        val versionHash: Int = version.hashCode()
        val sameHash: Int = same.hashCode()
        assertEquals(sameHash, versionHash)

        val otherHash: Int = otherBuild.hashCode()
        assertNotEquals(otherHash, versionHash)
    }

    @Test
    fun shouldShowTheMinecraftVersionAndTheBuild(): Unit {
        val version: PaperVersion = PaperVersion(MINECRAFT_VERSION, BUILD)
        val text: String = version.toString()
        assertEquals("PaperVersion(minecraft=26.3, build=49)", text)
    }

    private fun rejectionOf(version: String): String? {
        val failure: IllegalArgumentException = assertThrows(IllegalArgumentException::class.java) { PaperVersion.parse(version) }
        return failure.message
    }
}
