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

/**
 * The operating system and the architecture of a machine, in the names that the tools of this build use.
 *
 * It maps the names of the JVM to the platform names of Zig, uv and the LWJGL natives, and to the paths of the Java and
 * virtual environment executables.
 *
 * @property operatingSystem the operating system in lowercase, such as `linux` or `windows 11`
 * @property architecture    the architecture in lowercase, with `amd64` named `x86_64` and `arm64` named `aarch64`
 */
class HostPlatform private constructor(val operatingSystem: String, val architecture: String) {

    /**
     * Creates host platforms from the running JVM or from given names.
     */
    companion object {

        private const val WINDOWS: String = "windows"
        private const val WIN: String = "win"
        private const val MAC: String = "mac"
        private const val DARWIN: String = "darwin"
        private const val LINUX: String = "linux"
        private const val FREEBSD: String = "freebsd"

        private const val ZIG_MACOS: String = "macos"

        private const val X86_64: String = "x86_64"
        private const val AARCH64: String = "aarch64"
        private const val X86: String = "x86"
        private const val I386: String = "i386"
        private const val I686: String = "i686"
        private const val ARM: String = "arm"
        private const val ARM32: String = "arm32"
        private const val ARMV7_PREFIX: String = "armv7"
        private const val PPC64LE: String = "ppc64le"
        private const val RISCV64: String = "riscv64"
        private const val AMD64: String = "amd64"
        private const val ARM64: String = "arm64"

        private const val WINDOWS_UV_TARGET: String = "-pc-windows-msvc"
        private const val MACOS_UV_TARGET: String = "-apple-darwin"
        private const val LINUX_UV_TARGET: String = "-unknown-linux-musl"

        private const val MACOS_ARM64_NATIVES: String = "natives-macos-arm64"
        private const val MACOS_NATIVES: String = "natives-macos"
        private const val WINDOWS_ARM64_NATIVES: String = "natives-windows-arm64"
        private const val WINDOWS_NATIVES: String = "natives-windows"
        private const val WINDOWS_X86_NATIVES: String = "natives-windows-x86"
        private const val FREEBSD_NATIVES: String = "natives-freebsd"
        private const val LINUX_NATIVES: String = "natives-linux"
        private const val LINUX_ARM64_NATIVES: String = "natives-linux-arm64"
        private const val LINUX_ARM32_NATIVES: String = "natives-linux-arm32"
        private const val LINUX_PPC64LE_NATIVES: String = "natives-linux-ppc64le"
        private const val LINUX_RISCV64_NATIVES: String = "natives-linux-riscv64"

        private const val JAVA_BINARY: String = "/bin/java"
        private const val EXECUTABLE_EXTENSION: String = ".exe"
        private const val WINDOWS_SCRIPTS_DIRECTORY: String = "Scripts/"
        private const val UNIX_BINARIES_DIRECTORY: String = "bin/"

        private const val TEST_RUNTIME_CONFIGURATION: String = "testRuntimeOnly"

        private const val OPERATING_SYSTEM_PROPERTY: String = "os.name"
        private const val ARCHITECTURE_PROPERTY: String = "os.arch"

        private val ARCHITECTURE_ALIASES: Map<String, String> = mapOf(AMD64 to X86_64, ARM64 to AARCH64)

        /**
         * Returns the platform of the running JVM.
         *
         * @return the host platform
         */
        fun current(): HostPlatform {
            return this.normalize(System.getProperty(OPERATING_SYSTEM_PROPERTY), System.getProperty(ARCHITECTURE_PROPERTY))
        }

        /**
         * Returns the platform with the given JVM names, in lowercase and with the architecture aliases resolved.
         *
         * @param operatingSystem the operating system, as `os.name` names it
         * @param architecture    the architecture, as `os.arch` names it
         * @return the host platform
         */
        fun normalize(operatingSystem: String, architecture: String): HostPlatform {
            val name: String = architecture.lowercase(Locale.ROOT)
            val normalizedArchitecture: String = ARCHITECTURE_ALIASES.getOrDefault(name, name)
            return HostPlatform(operatingSystem.lowercase(Locale.ROOT), normalizedArchitecture)
        }
    }

    /**
     * Whether the operating system is Windows.
     */
    val isWindows: Boolean
        get() {
            return this.operatingSystem.contains(WINDOWS)
        }

    /**
     * The operating system as Zig names it: `windows`, `macos` or `linux`, or else [operatingSystem] unchanged.
     */
    val zigSystem: String
        get() {
            return when {
                this.isWindows -> WINDOWS
                this.operatingSystem.contains(MAC) -> ZIG_MACOS
                this.operatingSystem.contains(LINUX) -> LINUX
                else -> this.operatingSystem
            }
        }

    /**
     * The target triple of the uv build for this platform, such as `x86_64-unknown-linux-musl`.
     */
    val uvTarget: String
        get() {
            val suffix: String = when {
                this.isWindows -> WINDOWS_UV_TARGET
                this.operatingSystem.contains(MAC) -> MACOS_UV_TARGET
                else -> LINUX_UV_TARGET
            }
            return this.architecture + suffix
        }

    /**
     * The classifier of the LWJGL natives for this platform, such as `natives-linux-arm64`, or `null` when LWJGL has none.
     */
    val lwjglNatives: String?
        get() {
            return when {
                this.operatingSystem.contains(MAC) || this.operatingSystem.contains(DARWIN) -> this.macosNatives()
                this.operatingSystem.contains(WIN) -> this.windowsNatives()
                this.operatingSystem.contains(FREEBSD) -> this.freebsdNatives()
                this.operatingSystem.contains(LINUX) -> this.linuxNatives()
                else -> null
            }
        }

    /**
     * Returns the path of the `java` executable of a Java installation.
     *
     * @param javaHome the home directory of the Java installation
     * @return the path of the executable, ending in `.exe` on Windows
     */
    fun javaExecutable(javaHome: String): String {
        val extension: String = if (this.isWindows) EXECUTABLE_EXTENSION else ""
        return javaHome + JAVA_BINARY + extension
    }

    /**
     * Returns the path of an executable inside a Python virtual environment, relative to the environment.
     *
     * @param name the name of the executable, such as `python`
     * @return `Scripts/<name>.exe` on Windows and `bin/<name>` elsewhere
     */
    fun virtualEnvironmentExecutable(name: String): String {
        return if (this.isWindows) "$WINDOWS_SCRIPTS_DIRECTORY$name$EXECUTABLE_EXTENSION" else "$UNIX_BINARIES_DIRECTORY$name"
    }

    /**
     * Adds the LWJGL natives of this platform for each of [libraries] to the `testRuntimeOnly` configuration.
     *
     * Nothing is added when LWJGL has no natives for this platform.
     *
     * @param dependencies the dependency handler of the project
     * @param libraries    the LWJGL libraries whose natives the tests load
     */
    fun addLwjglTestNatives(dependencies: DependencyHandler, libraries: Iterable<Provider<MinimalExternalModuleDependency>>): Unit {
        val natives: String = this.lwjglNatives ?: return
        libraries.forEach { this.addTestNatives(dependencies, it, natives) }
    }

    private fun addTestNatives(dependencies: DependencyHandler, library: Provider<MinimalExternalModuleDependency>, natives: String): Unit {
        val variant: Provider<MinimalExternalModuleDependency> = dependencies.variantOf(library) { this.classifier(natives) }
        dependencies.add(TEST_RUNTIME_CONFIGURATION, variant)
    }

    private fun macosNatives(): String? {
        return when (this.architecture) {
            AARCH64 -> MACOS_ARM64_NATIVES
            X86_64 -> MACOS_NATIVES
            else -> null
        }
    }

    private fun windowsNatives(): String? {
        return when (this.architecture) {
            AARCH64 -> WINDOWS_ARM64_NATIVES
            X86_64 -> WINDOWS_NATIVES
            X86, I386, I686 -> WINDOWS_X86_NATIVES
            else -> null
        }
    }

    private fun freebsdNatives(): String? {
        return if (this.architecture == X86_64) FREEBSD_NATIVES else null
    }

    private fun linuxNatives(): String? {
        return when {
            this.architecture == X86_64 -> LINUX_NATIVES
            this.architecture == AARCH64 -> LINUX_ARM64_NATIVES
            this.architecture == ARM || this.architecture == ARM32 || this.architecture.startsWith(ARMV7_PREFIX) -> LINUX_ARM32_NATIVES
            this.architecture == PPC64LE -> LINUX_PPC64LE_NATIVES
            this.architecture == RISCV64 -> LINUX_RISCV64_NATIVES
            else -> null
        }
    }
}
