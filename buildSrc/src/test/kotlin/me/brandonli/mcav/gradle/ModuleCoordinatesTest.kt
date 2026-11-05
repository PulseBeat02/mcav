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

import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ModuleCoordinatesTest {

    private companion object {

        const val ROOT_NAME: String = "mcav"
        const val BUKKIT_NAME: String = "mcav-bukkit"
    }

    @Test
    fun shouldFollowTheRootGroupAndVersionInDependencyCoordinatesWithoutVersioningLibraryArchives(): Unit {
        val root: Project = ProjectBuilder.builder().withName(ROOT_NAME).build()
        root.group = "fixture.changed"
        root.version = "2.3.4-SNAPSHOT"

        val plugin: Project = ProjectBuilder.builder().withName("mcav-plugin").withParent(root).build()
        val coordinate: String = ModuleCoordinates.coordinateOf(plugin, BUKKIT_NAME)
        assertEquals("fixture.changed:mcav-bukkit:2.3.4-SNAPSHOT", coordinate)

        val pluginVersion: String = plugin.version.toString()
        assertEquals("unspecified", pluginVersion)

        root.group = "fixture.updated"
        root.version = "3.4.5-SNAPSHOT"

        val updatedCoordinate: String = ModuleCoordinates.coordinateOf(plugin, "mcav-voicechat")
        assertEquals("fixture.updated:mcav-voicechat:3.4.5-SNAPSHOT", updatedCoordinate)
    }

    @Test
    fun shouldListTheProjectPathsOfTheDependenciesThatAreModulesOfThisBuild(): Unit {
        val root: Project = ProjectBuilder.builder().withName(ROOT_NAME).build()
        root.group = "fixture.group"
        ProjectBuilder.builder().withName("mcav-common").withParent(root).build()
        ProjectBuilder.builder().withName(BUKKIT_NAME).withParent(root).build()

        val configuration: Configuration = root.configurations.create("runtimeDownload")
        val notations: List<String> =
            listOf("fixture.group:mcav-common:1.0.0", "fixture.group:mcav-absent:1.0.0", "fixture.other:mcav-bukkit:1.0.0")
        for (notation in notations) {
            val dependency: Dependency = root.dependencies.create(notation)
            configuration.dependencies.add(dependency)
        }

        val paths: List<String> = ModuleCoordinates.modulePathsOf(configuration, root)
        assertEquals(listOf(":mcav-common"), paths)
    }
}
