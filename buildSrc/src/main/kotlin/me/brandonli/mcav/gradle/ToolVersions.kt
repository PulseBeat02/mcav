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
import org.gradle.api.GradleException
import org.gradle.process.ExecOperations

/**
 * Checks the version that a tool reports against the version this build pins.
 */
object ToolVersions {

    private const val VERSION_ARGUMENT: String = "--version"

    private const val WORD_START: String = "(?:^|\\s)"
    private const val VERSION_WORD: String = "(\\S+)"
    private const val VERSION_GROUP: Int = 1

    /**
     * Checks that [executable] reports [expected] as its version.
     *
     * The executable runs with [argument], and its version is the first word after [prefix] in its output.
     *
     * @param processes  the service that runs the executable
     * @param executable the executable to run, as a path or a file
     * @param tool       the name of the tool, for the failure message
     * @param expected   the version that this build pins
     * @param prefix     the text before the version, the tool name and a space unless given
     * @param argument   the argument that prints the version, `--version` unless given
     * @throws GradleException if the executable cannot run or reports another version
     */
    fun verify(
        processes: ExecOperations,
        executable: Any,
        tool: String,
        expected: String,
        prefix: String = "$tool ",
        argument: String = VERSION_ARGUMENT
    ): Unit {
        val output: ByteArrayOutputStream = ByteArrayOutputStream()
        processes.exec {
            this.commandLine(executable, argument)
            this.standardOutput = output
        }

        val reported: String = output.toString(Charsets.UTF_8).trim()
        val actual: String? = this.findVersion(reported, prefix)
        if (actual != expected) {
            val message: String = "$executable reports $reported, which is not the $tool $expected this build pins!"
            throw GradleException(message)
        }
    }

    private fun findVersion(reported: String, prefix: String): String? {
        val escapedPrefix: String = Regex.escape(prefix)
        val version: Regex = Regex("$WORD_START$escapedPrefix$VERSION_WORD")
        val match: MatchResult? = version.find(reported)
        return match?.groupValues?.get(VERSION_GROUP)
    }
}
