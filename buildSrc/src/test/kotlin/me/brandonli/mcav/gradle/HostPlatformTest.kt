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

import java.util.Locale
import org.gradle.api.Project
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.ModuleIdentifier
import org.gradle.api.internal.artifacts.DefaultModuleIdentifier
import org.gradle.api.internal.artifacts.dependencies.DefaultMinimalDependency
import org.gradle.api.internal.artifacts.dependencies.DefaultMutableVersionConstraint
import org.gradle.api.provider.Provider
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HostPlatformTest {

    private companion object {

        const val LINUX_NAME: String = "Linux"
        const val WINDOWS_NAME: String = "Windows 11"
        const val MACOS_NAME: String = "Mac OS X"
        const val UNKNOWN_NAME: String = "Plan 9"

        const val AMD64: String = "amd64"
        const val ARM64: String = "arm64"

        const val LINUX_ARM64_TARGET: String = "aarch64-unknown-linux-musl"
        const val WINDOWS_TARGET: String = "x86_64-pc-windows-msvc"

        const val LINUX_CLASSIFIER: String = "natives-linux"
        const val WINDOWS_CLASSIFIER: String = "natives-windows"
        const val MACOS_ARM64_CLASSIFIER: String = "natives-macos-arm64"

        const val LINUX_JAVA: String = "/opt/jdk/bin/java"
        const val WINDOWS_JAVA: String = "/opt/jdk/bin/java.exe"
        const val LINUX_PYTHON: String = "bin/python"
        const val WINDOWS_PYTHON: String = "Scripts/python.exe"

        const val JAVA_HOME: String = "/opt/jdk"
        const val PYTHON: String = "python"

        const val OPERATING_SYSTEM_PROPERTY: String = "os.name"
        const val ARCHITECTURE_PROPERTY: String = "os.arch"

        const val LWJGL_GROUP: String = "org.lwjgl"
        const val LWJGL_NAME: String = "lwjgl"
        const val LWJGL_VERSION: String = "3.3.6"
    }

    @Test
    fun shouldKeepTheArchiveAndClassifierMappingsOfTheSixDownloadHosts(): Unit {
        val hosts: List<List<String>> = listOf(
            listOf(LINUX_NAME, AMD64, "x86_64", "linux", "x86_64-unknown-linux-musl", LINUX_CLASSIFIER),
            listOf(LINUX_NAME, ARM64, "aarch64", "linux", LINUX_ARM64_TARGET, "natives-linux-arm64"),
            listOf(WINDOWS_NAME, "x86_64", "x86_64", "windows", WINDOWS_TARGET, WINDOWS_CLASSIFIER),
            listOf(WINDOWS_NAME, "aarch64", "aarch64", "windows", "aarch64-pc-windows-msvc", "natives-windows-arm64"),
            listOf(MACOS_NAME, AMD64, "x86_64", "macos", "x86_64-apple-darwin", "natives-macos"),
            listOf(MACOS_NAME, ARM64, "aarch64", "macos", "aarch64-apple-darwin", MACOS_ARM64_CLASSIFIER)
        )
        val mappings: List<List<String?>> = hosts.map(this::mappingOf)
        assertEquals(hosts, mappings)
    }

    @Test
    fun shouldKeepTheAdditionalArchitecturesOfLwjglAndRejectUnknownHosts(): Unit {
        val windowsX86: List<String?> = listOf("x86", "i386", "i686").map { HostPlatform.normalize("Windows", it).lwjglNatives }
        assertEquals(List(3) { "natives-windows-x86" }, windowsX86)

        val linuxArm32: List<String?> = listOf("arm", "arm32", "armv7l").map { HostPlatform.normalize(LINUX_NAME, it).lwjglNatives }
        assertEquals(List(3) { "natives-linux-arm32" }, linuxArm32)

        val powerPc: String? = HostPlatform.normalize(LINUX_NAME, "ppc64le").lwjglNatives
        assertEquals("natives-linux-ppc64le", powerPc)

        val riscV: String? = HostPlatform.normalize(LINUX_NAME, "riscv64").lwjglNatives
        assertEquals("natives-linux-riscv64", riscV)

        val freeBsd: String? = HostPlatform.normalize("FreeBSD", AMD64).lwjglNatives
        assertEquals("natives-freebsd", freeBsd)

        val darwin: HostPlatform = HostPlatform.normalize("Darwin", ARM64)
        val darwinNatives: String? = darwin.lwjglNatives
        assertEquals(MACOS_ARM64_CLASSIFIER, darwinNatives)

        val darwinZigSystem: String = darwin.zigSystem
        assertEquals("darwin", darwinZigSystem)

        val darwinUvTarget: String = darwin.uvTarget
        assertEquals(LINUX_ARM64_TARGET, darwinUvTarget)

        val operatingSystems: List<String> = listOf(LINUX_NAME, "Windows", MACOS_NAME, "FreeBSD", UNKNOWN_NAME)
        val unknownArchitectures: List<String?> = operatingSystems.map { HostPlatform.normalize(it, "unknown").lwjglNatives }
        assertEquals(List(5) { null }, unknownArchitectures)

        val freeBsdArm: String? = HostPlatform.normalize("FreeBSD", ARM64).lwjglNatives
        assertNull(freeBsdArm)
    }

    @Test
    fun shouldNormalizeTheHostIndependentlyOfTheDefaultLocale(): Unit {
        val previous: Locale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))

            val host: HostPlatform = HostPlatform.normalize("WINDOWS", "AMD64")
            val operatingSystem: String = host.operatingSystem
            assertEquals("windows", operatingSystem)

            val windows: Boolean = host.isWindows
            assertTrue(windows)

            val uvTarget: String = host.uvTarget
            assertEquals(WINDOWS_TARGET, uvTarget)

            val windowsNatives: String? = host.lwjglNatives
            assertEquals(WINDOWS_CLASSIFIER, windowsNatives)

            val linuxNatives: String? = HostPlatform.normalize("LINUX", "X86_64").lwjglNatives
            assertEquals(LINUX_CLASSIFIER, linuxNatives)
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun shouldPlaceTheJavaAndVirtualEnvironmentExecutablesOfEachSystem(): Unit {
        val linux: HostPlatform = HostPlatform.normalize(LINUX_NAME, AMD64)
        val windows: HostPlatform = HostPlatform.normalize(WINDOWS_NAME, AMD64)

        val linuxJava: String = linux.javaExecutable(JAVA_HOME)
        assertEquals(LINUX_JAVA, linuxJava)

        val windowsJava: String = windows.javaExecutable(JAVA_HOME)
        assertEquals(WINDOWS_JAVA, windowsJava)

        val linuxPython: String = linux.virtualEnvironmentExecutable(PYTHON)
        assertEquals(LINUX_PYTHON, linuxPython)

        val windowsPython: String = windows.virtualEnvironmentExecutable(PYTHON)
        assertEquals(WINDOWS_PYTHON, windowsPython)
    }

    @Test
    fun shouldPlaceTheExecutablesOfThisHost(): Unit {
        val operatingSystem: String = System.getProperty(OPERATING_SYSTEM_PROPERTY)
        val architecture: String = System.getProperty(ARCHITECTURE_PROPERTY)
        val host: HostPlatform = HostPlatform.normalize(operatingSystem, architecture)

        val java: String = HostPlatform.current().javaExecutable(JAVA_HOME)
        val expectedJava: String = host.javaExecutable(JAVA_HOME)
        assertEquals(expectedJava, java)

        val python: String = HostPlatform.current().virtualEnvironmentExecutable(PYTHON)
        val expectedPython: String = host.virtualEnvironmentExecutable(PYTHON)
        assertEquals(expectedPython, python)
    }

    @Test
    fun shouldAddTheLwjglNativesOfAHostThatHasThem(): Unit {
        val project: Project = this.javaProject()
        val linux: HostPlatform = HostPlatform.normalize(LINUX_NAME, AMD64)
        linux.addLwjglTestNatives(project.dependencies, listOf(this.lwjgl(project)))

        val classifiers: List<String?> = this.testRuntimeClassifiers(project)
        assertEquals(listOf(LINUX_CLASSIFIER), classifiers)
    }

    @Test
    fun shouldAddNoLwjglNativesForAHostWithoutThem(): Unit {
        val project: Project = this.javaProject()
        val unknown: HostPlatform = HostPlatform.normalize(UNKNOWN_NAME, AMD64)
        unknown.addLwjglTestNatives(project.dependencies, listOf(this.lwjgl(project)))

        val classifiers: List<String?> = this.testRuntimeClassifiers(project)
        assertEquals(emptyList<String?>(), classifiers)
    }

    private fun mappingOf(row: List<String>): List<String?> {
        val operatingSystem: String = row[0]
        val architecture: String = row[1]
        val host: HostPlatform = HostPlatform.normalize(operatingSystem, architecture)
        return listOf(operatingSystem, architecture, host.architecture, host.zigSystem, host.uvTarget, host.lwjglNatives)
    }

    private fun javaProject(): Project {
        val project: Project = ProjectBuilder.builder().build()
        project.pluginManager.apply("java")
        return project
    }

    private fun lwjgl(project: Project): Provider<MinimalExternalModuleDependency> {
        return project.provider(this::lwjglDependency)
    }

    private fun lwjglDependency(): MinimalExternalModuleDependency {
        val module: ModuleIdentifier = DefaultModuleIdentifier.newId(LWJGL_GROUP, LWJGL_NAME)
        val version: DefaultMutableVersionConstraint = DefaultMutableVersionConstraint(LWJGL_VERSION)
        return DefaultMinimalDependency(module, version)
    }

    private fun testRuntimeClassifiers(project: Project): List<String?> {
        val dependencies: Iterable<ExternalModuleDependency> =
            project.configurations.getByName("testRuntimeOnly").dependencies.filterIsInstance<ExternalModuleDependency>()
        return dependencies.map { it.artifacts.single().classifier }
    }
}
