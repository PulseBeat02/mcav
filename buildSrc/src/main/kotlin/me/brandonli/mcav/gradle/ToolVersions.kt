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

import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import org.gradle.api.GradleException
import org.gradle.process.ExecOperations

abstract class ToolVersions {

    @get:Inject
    protected abstract val processes: ExecOperations

    fun requireVersion(executable: Any, tool: String, expected: String, prefix: String = tool, directory: File? = null) {
        verify(processes, executable, tool, expected, prefix, directory)
    }

    companion object {
        fun verify(processes: ExecOperations, executable: Any, tool: String, expected: String, prefix: String = tool, directory: File? = null) {
            val output = ByteArrayOutputStream()
            processes.exec {
                if (directory != null) workingDir(directory)
                commandLine(executable, "--version")
                standardOutput = output
            }
            val reported = output.toString(Charsets.UTF_8).trim()
            val actual = Regex("(?:^|\\s)${Regex.escape(prefix)} (\\S+)").find(reported)?.groupValues?.get(1)
            if (actual != expected) {
                throw GradleException("$executable must be $tool $expected, but it is $reported")
            }
        }
    }
}
