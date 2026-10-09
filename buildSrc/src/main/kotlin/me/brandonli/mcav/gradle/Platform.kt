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
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.provider.Provider

class HostPlatform private constructor(val operatingSystem: String, val architecture: String) {

    val isWindows: Boolean get() = operatingSystem.contains("windows")

    val zigSystem: String get() = when {
        isWindows -> "windows"
        operatingSystem.contains("mac") -> "macos"
        operatingSystem.contains("linux") -> "linux"
        else -> operatingSystem
    }

    val uvTarget: String get() = architecture + when {
        isWindows -> "-pc-windows-msvc"
        operatingSystem.contains("mac") -> "-apple-darwin"
        else -> "-unknown-linux-musl"
    }

    val lwjglNatives: String? get() = when {
        operatingSystem.contains("mac") || operatingSystem.contains("darwin") -> when (architecture) {
            "aarch64" -> "natives-macos-arm64"
            "x86_64" -> "natives-macos"
            else -> null
        }
        operatingSystem.contains("win") -> when (architecture) {
            "aarch64" -> "natives-windows-arm64"
            "x86_64" -> "natives-windows"
            "x86", "i386", "i686" -> "natives-windows-x86"
            else -> null
        }
        operatingSystem.contains("freebsd") -> if (architecture == "x86_64") "natives-freebsd" else null
        operatingSystem.contains("linux") -> when {
            architecture == "x86_64" -> "natives-linux"
            architecture == "aarch64" -> "natives-linux-arm64"
            architecture == "arm" || architecture == "arm32" || architecture.startsWith("armv7") -> "natives-linux-arm32"
            architecture == "ppc64le" -> "natives-linux-ppc64le"
            architecture == "riscv64" -> "natives-linux-riscv64"
            else -> null
        }
        else -> null
    }

    companion object {
        fun current(): HostPlatform = normalize(System.getProperty("os.name"), System.getProperty("os.arch"))

        fun normalize(operatingSystem: String, architecture: String): HostPlatform {
            val normalizedArchitecture = when (val name = architecture.lowercase(Locale.ROOT)) {
                "amd64", "x86_64" -> "x86_64"
                "aarch64", "arm64" -> "aarch64"
                else -> name
            }
            return HostPlatform(operatingSystem.lowercase(Locale.ROOT), normalizedArchitecture)
        }
    }
}

val isWindows: Boolean = HostPlatform.current().isWindows

fun javaExecutable(javaHome: String): String = javaHome + "/bin/java" + if (isWindows) ".exe" else ""

fun lwjglNatives(): String? = HostPlatform.current().lwjglNatives

fun DependencyHandler.addLwjglTestNatives(libraries: Iterable<Provider<MinimalExternalModuleDependency>>) {
    lwjglNatives()?.let { natives ->
        libraries.forEach { library ->
            add("testRuntimeOnly", variantOf(library) { classifier(natives) })
        }
    }
}
