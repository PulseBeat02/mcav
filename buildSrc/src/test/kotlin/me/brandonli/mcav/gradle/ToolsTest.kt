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
import java.net.URL
import java.net.URLClassLoader
import java.security.CodeSource
import java.security.ProtectionDomain
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ToolsTest {

    private companion object {

        const val CLANG_ARCHIVE: String = "clang.zip"
        const val FIXTURE_TOOL_PATH: String = "tools/fixture-tool/1.0/fixture-tool"
        const val PATH_TOOL_PATH: String = "tools/path/1.0/path"

        const val CHECKSUMS_RESOURCE: String = "tool-checksums.properties"
        const val BUILD_PACKAGE_PREFIX: String = "me.brandonli.mcav.gradle."
    }

    @Test
    fun shouldGiveAPinnedArchiveItsChecksumAndNameTheOverrideVariableOfAnUnpinnedOne(): Unit {
        val project: Project = ProjectBuilder.builder().build()
        val checksum: String = Tools.pinnedSha256(project, "zig", "0.16.0", "zig-x86_64-linux-0.16.0.tar.xz").get()
        assertEquals("70e49664a74374b48b51e6f3fdfbf437f6395d42509050588bd49abe52ba3d00", checksum)

        val failure: GradleException =
            assertThrows(GradleException::class.java) { Tools.pinnedSha256(project, "clang-format", "1.0", CLANG_ARCHIVE).get() }
        val message: String? = failure.message
        val expected: String =
            "tool-checksums.properties pins no SHA-256 for clang-format/1.0/clang.zip, so set CLANG_FORMAT to a clang-format 1.0 binary!"
        assertEquals(expected, message)
    }

    @Test
    fun shouldSelectTheInstalledToolWithoutItsOverrideVariable(): Unit {
        val project: Project = ProjectBuilder.builder().build()
        val installed: Provider<RegularFile> = project.layout.buildDirectory.file(FIXTURE_TOOL_PATH)
        val overridden: Boolean = Tools.toolOverride(project, "fixture-tool").isPresent
        assertFalse(overridden)

        val selected: Provider<RegularFile> = Tools.selectTool(project, "fixture-tool", installed)
        assertSame(installed, selected)
    }

    @Test
    fun shouldSelectTheFileOfASetOverrideVariableOverTheInstalledTool(): Unit {
        val project: Project = ProjectBuilder.builder().build()
        val installed: Provider<RegularFile> = project.layout.buildDirectory.file(PATH_TOOL_PATH)
        // a test cannot set a variable of its own process, and PATH is always set
        val variable: File = project.file(System.getenv("PATH"))
        val override: File = Tools.toolOverride(project, "path").get().asFile
        assertEquals(variable, override)

        val selected: File = Tools.selectTool(project, "path", installed).get().asFile
        assertEquals(variable, selected)
    }

    @Test
    fun shouldFailToLoadWithoutThePinnedChecksums(): Unit {
        val toolsClass: Class<Tools> = Tools::class.java
        val domain: ProtectionDomain = toolsClass.protectionDomain
        val codeSource: CodeSource = domain.codeSource
        val loader: URLClassLoader = ChecksumHidingClassLoader(codeSource.location, toolsClass.classLoader)
        val failure: ExceptionInInitializerError =
            assertThrows(ExceptionInInitializerError::class.java) { Class.forName(toolsClass.name, true, loader) }
        loader.close()

        val cause: Throwable? = failure.cause
        val message: String? = cause?.message
        assertEquals("Resource /tool-checksums.properties is missing from buildSrc!", message)
    }

    // loads the build classes again, child first, so that their own resource lookup misses the checksums
    private class ChecksumHidingClassLoader(classes: URL, parent: ClassLoader) : URLClassLoader(arrayOf(classes), parent) {

        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (!name.startsWith(BUILD_PACKAGE_PREFIX)) {
                return super.loadClass(name, resolve)
            }

            val loaded: Class<*>? = this.findLoadedClass(name)
            return loaded ?: this.findClass(name)
        }

        override fun getResource(name: String): URL? {
            if (name == CHECKSUMS_RESOURCE) {
                return null
            }

            return super.getResource(name)
        }
    }
}
