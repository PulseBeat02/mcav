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

import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PaperVersionTest {

    @Test
    fun thePinnedAlphaVersionSuppliesTheExactServerBuildAndArtifactName() {
        val version = PaperVersion.parse("26.3.build.49-alpha")
        assertEquals("26.3", version.minecraft)
        assertEquals(49, version.build)
        assertEquals("paper-26.3-49.jar", version.fileName)
        assertEquals("paper-26.4-123.jar", PaperVersion.parse("26.4.build.123").fileName)
    }

    @Test
    fun aVersionWithoutAnExplicitBuildIsRejected() {
        listOf("26.3", "26.3.build.latest").forEach { version ->
            val failure = assertThrows(GradleException::class.java) { PaperVersion.parse(version) }
            assertEquals("Paper version must name a build, but it is $version", failure.message)
        }
    }
}
