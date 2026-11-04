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
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class NodeLayoutTest {

    private companion object {

        const val UNIX_NODE_PATH: String = "bin/node"
        const val WINDOWS_NODE_PATH: String = "node.exe"

        const val UNIX_COREPACK_PATH: String = "lib/node_modules/corepack/dist/corepack.js"
        const val WINDOWS_COREPACK_PATH: String = "node_modules/corepack/dist/corepack.js"

        val LINUX: HostPlatform = HostPlatform.normalize("Linux", "amd64")
        val WINDOWS: HostPlatform = HostPlatform.normalize("Windows 11", "amd64")
    }

    private lateinit var installation: Directory

    @BeforeEach
    fun setUp(): Unit {
        val project: Project = ProjectBuilder.builder().build()
        this.installation = project.layout.projectDirectory
    }

    @Test
    fun shouldFindTheExecutableInTheBinDirectoryOutsideWindows(): Unit {
        val executable: File = NodeLayout.executable(this.installation, LINUX).asFile
        val expected: File = this.installation.asFile.resolve(UNIX_NODE_PATH)
        assertEquals(expected, executable)
    }

    @Test
    fun shouldFindTheExecutableAtTheTopOnWindows(): Unit {
        val executable: File = NodeLayout.executable(this.installation, WINDOWS).asFile
        val expected: File = this.installation.asFile.resolve(WINDOWS_NODE_PATH)
        assertEquals(expected, executable)
    }

    @Test
    fun shouldFindTheCorepackScriptInTheLibDirectoryOutsideWindows(): Unit {
        val script: File = NodeLayout.corepackScript(this.installation, LINUX).asFile
        val expected: File = this.installation.asFile.resolve(UNIX_COREPACK_PATH)
        assertEquals(expected, script)
    }

    @Test
    fun shouldFindTheCorepackScriptInTheTopModulesOnWindows(): Unit {
        val script: File = NodeLayout.corepackScript(this.installation, WINDOWS).asFile
        val expected: File = this.installation.asFile.resolve(WINDOWS_COREPACK_PATH)
        assertEquals(expected, script)
    }
}
