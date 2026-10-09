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

val isWindows: Boolean = System.getProperty("os.name").lowercase().contains("windows")

fun javaExecutable(javaHome: String): String = javaHome + "/bin/java" + if (isWindows) ".exe" else ""

fun lwjglNatives(): String? {
    val operatingSystem = System.getProperty("os.name").lowercase()
    val architecture = System.getProperty("os.arch").lowercase()
    val isX86_64 = architecture == "amd64" || architecture == "x86_64"
    val isArm64 = architecture == "aarch64" || architecture == "arm64"
    return when {
        operatingSystem.contains("mac") || operatingSystem.contains("darwin") -> when {
            isArm64 -> "natives-macos-arm64"
            isX86_64 -> "natives-macos"
            else -> null
        }
        operatingSystem.contains("win") -> when {
            isArm64 -> "natives-windows-arm64"
            isX86_64 -> "natives-windows"
            architecture == "x86" || architecture == "i386" || architecture == "i686" -> "natives-windows-x86"
            else -> null
        }
        operatingSystem.contains("freebsd") -> if (isX86_64) "natives-freebsd" else null
        operatingSystem.contains("linux") -> when {
            isX86_64 -> "natives-linux"
            isArm64 -> "natives-linux-arm64"
            architecture == "arm" || architecture == "arm32" || architecture.startsWith("armv7") -> "natives-linux-arm32"
            architecture == "ppc64le" -> "natives-linux-ppc64le"
            architecture == "riscv64" -> "natives-linux-riscv64"
            else -> null
        }
        else -> null
    }
}
