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

import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile

/**
 * Names the setup task of the Node Gradle plugin and locates the executable of the Node.js installation it unpacks.
 *
 * Node's archives for Linux and macOS keep the executable in `bin`; the Windows archive keeps it at its top.
 */
object NodeLayout {

    /**
     * The name of the Node Gradle plugin's task that unpacks the Node.js installation.
     */
    const val SETUP_TASK_NAME: String = "nodeSetup"

    private const val UNIX_EXECUTABLE: String = "bin/node"
    private const val WINDOWS_EXECUTABLE: String = "node.exe"

    /**
     * Returns the Node executable of an installation.
     *
     * @param installation the directory Node is unpacked into
     * @param platform     the platform the installation is for
     * @return the executable
     */
    fun executable(installation: Directory, platform: HostPlatform): RegularFile {
        val path: String = if (platform.isWindows) WINDOWS_EXECUTABLE else UNIX_EXECUTABLE
        return installation.file(path)
    }
}
