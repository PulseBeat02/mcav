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

import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.artifacts.ArtifactSelectionDetails
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.DependencySubstitution
import org.gradle.api.artifacts.DependencySubstitutions
import org.gradle.api.artifacts.ResolutionStrategy
import org.gradle.api.artifacts.component.ComponentSelector
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ModuleCoordinatesTest {

    private companion object {

        const val ROOT_NAME: String = "mcav"
        const val BUKKIT_NAME: String = "mcav-bukkit"

        const val FIXTURE_GROUP: String = "fixture.group"
        const val ABSENT_COORDINATE: String = "fixture.group:mcav-absent:1.0.0"
        const val OTHER_GROUP_COORDINATE: String = "fixture.other:mcav-bukkit:1.0.0"
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
        root.group = FIXTURE_GROUP
        ProjectBuilder.builder().withName("mcav-common").withParent(root).build()
        ProjectBuilder.builder().withName(BUKKIT_NAME).withParent(root).build()

        val configuration: Configuration = root.configurations.create("runtimeDownload")
        val notations: List<String> = listOf("fixture.group:mcav-common:1.0.0", ABSENT_COORDINATE, OTHER_GROUP_COORDINATE)
        for (notation in notations) {
            val dependency: Dependency = root.dependencies.create(notation)
            configuration.dependencies.add(dependency)
        }

        val paths: List<String> = ModuleCoordinates.modulePathsOf(configuration, root)
        assertEquals(listOf(":mcav-common"), paths)
    }

    @Test
    fun shouldSubstituteTheProjectOnlyForARequestedModuleOfThisBuild(): Unit {
        val root: Project = ProjectBuilder.builder().withName(ROOT_NAME).build()
        root.group = FIXTURE_GROUP
        val bukkit: Project = ProjectBuilder.builder().withName(BUKKIT_NAME).withParent(root).build()
        val configuration: Configuration = root.configurations.create("runtimeClasspath")
        val strategy: ResolutionStrategy = configuration.resolutionStrategy
        val substitutions: DependencySubstitutions = strategy.dependencySubstitution

        val localModule: ComponentSelector = substitutions.module("fixture.group:mcav-bukkit:1.0.0")
        val localTarget: Any? = this.targetOf(localModule, root)
        val localProject: Project? = localTarget as? Project
        assertEquals(bukkit.path, localProject?.path)

        val otherGroupModule: ComponentSelector = substitutions.module(OTHER_GROUP_COORDINATE)
        val otherGroupTarget: Any? = this.targetOf(otherGroupModule, root)
        assertNull(otherGroupTarget)

        val absentModule: ComponentSelector = substitutions.module(ABSENT_COORDINATE)
        val absentTarget: Any? = this.targetOf(absentModule, root)
        assertNull(absentTarget)

        val projectSelector: ComponentSelector = substitutions.project(bukkit.path)
        val projectTarget: Any? = this.targetOf(projectSelector, root)
        assertNull(projectTarget)
    }

    private fun targetOf(selector: ComponentSelector, root: Project): Any? {
        val substitution: FakeSubstitution = FakeSubstitution(selector)
        ModuleCoordinates.substituteLocalProject(substitution, root)

        return substitution.target
    }

    private class FakeSubstitution(private val selector: ComponentSelector) : DependencySubstitution {

        var target: Any? = null

        override fun getRequested(): ComponentSelector {
            return this.selector
        }

        override fun useTarget(notation: Any): Unit {
            this.target = notation
        }

        override fun useTarget(notation: Any, reason: String): Unit {
            this.target = notation
        }

        override fun artifactSelection(action: Action<in ArtifactSelectionDetails>): Unit {
        }
    }
}
