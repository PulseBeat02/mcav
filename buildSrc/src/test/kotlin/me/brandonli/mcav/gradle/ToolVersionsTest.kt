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
import java.io.OutputStream
import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.process.ExecOperations
import org.gradle.process.ExecResult
import org.gradle.process.ExecSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.RETURNS_SELF

class ToolVersionsTest {

    @Test
    fun uvAcceptsTheExactVersionIncludingItsBuildMetadataAndRejectsOtherVersions() {
        ToolVersions.verify(processes("uv 0.12.23 (build 2026-10-01)\n"), File("uv"), "uv", "0.12.23")
        val failure = assertThrows(GradleException::class.java) {
            ToolVersions.verify(processes("uv 0.12.22\n"), File("uv"), "uv", "0.12.23")
        }
        assertEquals("uv must be uv 0.12.23, but it is uv 0.12.22", failure.message)
    }

    @Test
    fun clangFormatAcceptsTheExactVersionWithAProviderPrefixAndRejectsOtherToolsOrVersions() {
        ToolVersions.verify(processes("Ubuntu clang-format version 18.1.8 (distribution)\n"), "clang-format", "clang-format", "18.1.8", "clang-format version")
        listOf("clang-format version 18.1.7", "clang-format version 18.1.80", "clang version 18.1.8").forEach { reported ->
            val failure = assertThrows(GradleException::class.java) {
                ToolVersions.verify(processes(reported), "clang-format", "clang-format", "18.1.8", "clang-format version")
            }
            assertEquals("clang-format must be clang-format 18.1.8, but it is $reported", failure.message)
        }
    }

    private fun processes(output: String): ExecOperations {
        val processes = mock(ExecOperations::class.java)
        doAnswer { invocation ->
            val specification = mock(ExecSpec::class.java, RETURNS_SELF)
            invocation.getArgument<Action<ExecSpec>>(0).execute(specification)
            val calls = mockingDetails(specification).invocations
            val command = calls.single { it.method.name == "commandLine" }.rawArguments[0] as Array<*>
            assertEquals("--version", command[1])
            (calls.single { it.method.name == "setStandardOutput" }.rawArguments[0] as OutputStream).write(output.toByteArray())
            mock(ExecResult::class.java)
        }.`when`(processes).exec(any())
        return processes
    }
}
