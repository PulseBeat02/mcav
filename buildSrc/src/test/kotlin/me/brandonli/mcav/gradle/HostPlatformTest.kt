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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HostPlatformTest {

    @Test
    fun theSixDownloadHostsKeepTheirArchiveAndClassifierMappings() {
        val hosts = listOf(
            listOf("Linux", "amd64", "x86_64", "linux", "x86_64-unknown-linux-musl", "natives-linux"),
            listOf("Linux", "arm64", "aarch64", "linux", "aarch64-unknown-linux-musl", "natives-linux-arm64"),
            listOf("Windows 11", "x86_64", "x86_64", "windows", "x86_64-pc-windows-msvc", "natives-windows"),
            listOf("Windows 11", "aarch64", "aarch64", "windows", "aarch64-pc-windows-msvc", "natives-windows-arm64"),
            listOf("Mac OS X", "amd64", "x86_64", "macos", "x86_64-apple-darwin", "natives-macos"),
            listOf("Mac OS X", "arm64", "aarch64", "macos", "aarch64-apple-darwin", "natives-macos-arm64")
        )
        hosts.forEach { expected ->
            val host = HostPlatform.normalize(expected[0], expected[1])
            assertEquals(expected[2], host.architecture)
            assertEquals(expected[3], host.zigSystem)
            assertEquals(expected[4], host.uvTarget)
            assertEquals(expected[5], host.lwjglNatives)
        }
    }

    @Test
    fun lwjglKeepsItsAdditionalSupportedArchitecturesAndRejectsUnknownHosts() {
        listOf("x86", "i386", "i686").forEach { architecture ->
            assertEquals("natives-windows-x86", HostPlatform.normalize("Windows", architecture).lwjglNatives)
        }
        listOf("arm", "arm32", "armv7l").forEach { architecture ->
            assertEquals("natives-linux-arm32", HostPlatform.normalize("Linux", architecture).lwjglNatives)
        }
        assertEquals("natives-linux-ppc64le", HostPlatform.normalize("Linux", "ppc64le").lwjglNatives)
        assertEquals("natives-linux-riscv64", HostPlatform.normalize("Linux", "riscv64").lwjglNatives)
        assertEquals("natives-freebsd", HostPlatform.normalize("FreeBSD", "amd64").lwjglNatives)
        assertEquals("natives-macos-arm64", HostPlatform.normalize("Darwin", "arm64").lwjglNatives)
        assertEquals("darwin", HostPlatform.normalize("Darwin", "arm64").zigSystem)
        assertEquals("aarch64-unknown-linux-musl", HostPlatform.normalize("Darwin", "arm64").uvTarget)
        listOf("Linux", "Windows", "Mac OS X", "FreeBSD", "Plan 9").forEach { system ->
            assertNull(HostPlatform.normalize(system, "unknown").lwjglNatives)
        }
        assertNull(HostPlatform.normalize("FreeBSD", "arm64").lwjglNatives)
    }

    @Test
    fun hostNormalizationDoesNotDependOnTheDefaultLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val host = HostPlatform.normalize("WINDOWS", "AMD64")
            assertTrue(host.isWindows)
            assertEquals("x86_64-pc-windows-msvc", host.uvTarget)
            assertEquals("natives-windows", host.lwjglNatives)
            assertEquals("natives-linux", HostPlatform.normalize("LINUX", "X86_64").lwjglNatives)
        } finally {
            Locale.setDefault(previous)
        }
    }
}
