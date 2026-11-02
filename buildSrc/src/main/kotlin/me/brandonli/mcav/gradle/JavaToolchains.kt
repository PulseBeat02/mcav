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
import org.gradle.api.provider.Provider
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.jvm.toolchain.JvmVendorSpec
import org.gradle.kotlin.dsl.getByType

/**
 * Finds the Java toolchain of the version that the catalog pins, and names the compiler options that convention scripts share.
 */
object JavaToolchains {

    /**
     * The javac option that fails the compilation on any warning.
     */
    const val WARNINGS_AS_ERRORS: String = "-Werror"

    private const val JAVA_VERSION_ALIAS: String = "java"

    /**
     * Returns the Java language version that the catalog pins as `java`.
     *
     * @param project the project whose catalog to read
     * @return the language version
     * @throws IllegalArgumentException if the catalog has no `java` version
     */
    fun languageVersion(project: Project): JavaLanguageVersion {
        val version: String = CatalogUtils.versionOf(project.libs, JAVA_VERSION_ALIAS)
        return JavaLanguageVersion.of(version)
    }

    /**
     * Returns a launcher of the pinned Java version.
     *
     * @param project         the project whose catalog and toolchains to use
     * @param requestedVendor the vendor of the toolchain, or `null` for any vendor
     * @return the launcher
     * @throws IllegalArgumentException if the catalog has no `java` version
     */
    fun launcher(project: Project, requestedVendor: JvmVendorSpec? = null): Provider<JavaLauncher> {
        val version: JavaLanguageVersion = this.languageVersion(project)
        val toolchains: JavaToolchainService = project.extensions.getByType<JavaToolchainService>()
        return toolchains.launcherFor {
            this.languageVersion.set(version)

            if (requestedVendor != null) {
                this.vendor.set(requestedVendor)
            }
        }
    }
}
