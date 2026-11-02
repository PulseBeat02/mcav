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

/**
 * Sets up the environment that uv runs in.
 */
object UvEnvironment {

    internal const val TOOL: String = "uv"

    internal const val PACKAGE_INDEX: String = "https://pypi.org/simple"

    private const val PYTHON_DIRECTORY: String = "python"
    private const val BINARY_DIRECTORY: String = "bin"

    private const val NO_CONFIG_VARIABLE: String = "UV_NO_CONFIG"
    private const val CACHE_VARIABLE: String = "UV_CACHE_DIR"
    private const val PYTHON_CACHE_VARIABLE: String = "UV_PYTHON_CACHE_DIR"
    private const val PYTHON_INSTALL_VARIABLE: String = "UV_PYTHON_INSTALL_DIR"
    private const val PYTHON_BINARY_VARIABLE: String = "UV_PYTHON_BIN_DIR"
    private const val PYTHON_PREFERENCE_VARIABLE: String = "UV_PYTHON_PREFERENCE"
    private const val LINK_MODE_VARIABLE: String = "UV_LINK_MODE"

    private const val ENABLED: String = "1"
    private const val MANAGED_PYTHON_ONLY: String = "only-managed"
    private const val COPY_LINKS: String = "copy"

    /**
     * Returns the environment variables that keep uv inside the build.
     *
     * uv ignores the user's configuration, keeps its caches in [cacheDirectory] and uses only the Python builds that it
     * installs into [installationDirectory], copying files instead of linking them.
     *
     * @param installationDirectory the directory that receives Python
     * @param cacheDirectory        the uv cache directory
     * @return the variables by name
     */
    fun variables(installationDirectory: File, cacheDirectory: File): Map<String, String> {
        return mapOf(
            NO_CONFIG_VARIABLE to ENABLED,
            CACHE_VARIABLE to cacheDirectory.absolutePath,
            PYTHON_CACHE_VARIABLE to cacheDirectory.resolve(PYTHON_DIRECTORY).absolutePath,
            PYTHON_INSTALL_VARIABLE to installationDirectory.resolve(PYTHON_DIRECTORY).absolutePath,
            PYTHON_BINARY_VARIABLE to installationDirectory.resolve(BINARY_DIRECTORY).absolutePath,
            PYTHON_PREFERENCE_VARIABLE to MANAGED_PYTHON_ONLY,
            LINK_MODE_VARIABLE to COPY_LINKS
        )
    }
}
