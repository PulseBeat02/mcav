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
import java.nio.file.Path
import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.process.ExecOperations
import org.gradle.process.ExecResult
import org.gradle.process.ExecSpec
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.RETURNS_SELF
import org.mockito.Mockito.`when`

class BuildMcv2NativesTest {

    @TempDir
    lateinit var directory: Path

    abstract class RecordingBuild : BuildMcv2Natives() {
        lateinit var recordedProcesses: ExecOperations
        override val processes: ExecOperations get() = recordedProcesses
    }

    private val commands = mutableListOf<List<String>>()

    @Test
    fun buildsAllTargetsWithTheirBaselineAndOnlyTheirOwnExtensions() {
        val task = compiler("test-version")
        task.build()
        val links = commands.filter { it.first() == "cc" }
        assertEquals(setOf("x86_64-linux-gnu.2.28", "aarch64-linux-gnu.2.28", "x86_64-windows-gnu",
            "aarch64-windows-gnu", "x86_64-macos.11.0", "aarch64-macos.11.0"), links.map { it[2] }.toSet())
        assertEquals(6, links.size)
        assertTrue(links.all { "-shared" in it && "-s" in it })
        assertTrue(links.filter { "linux" in it[2] }.all { "-Wl,-z,noexecstack" in it })
        assertTrue(links.filter { "macos" in it[2] }.all { "-Wl,-install_name,@rpath/libmcv2kernels.dylib" in it })
        val compiles = commands.filter { it.first() == "c++" }
        assertEquals(22, compiles.size)
        for (command in compiles) {
            assertTrue("-mcpu=baseline" in command)
            assertTrue("-ffp-contract=off" in command && "-fwrapv" in command && "-fno-strict-aliasing" in command)
            assertFalse(command.any { "native" == it.substringAfter('=') || it == "-ffast-math" })
            val source = command[command.indexOf("-c") + 1]
            assertEquals(source == "level_avx2.cpp", "-mavx2" in command)
            assertEquals(source == "level_avx512.cpp", "-mavx512f" in command)
            assertEquals(source.startsWith("level_sve"), "-mcpu=baseline+sve" in command)
            assertEquals(command[2].startsWith("x86_64") && "macos" !in command[2], "-mbranches-within-32B-boundaries" in command)
        }
        val natives = task.outputDirectory.get().asFile.resolve("mcav/mcv2/natives")
        val expectedPaths = setOf("linux-x86_64/libmcv2kernels.so", "linux-aarch64/libmcv2kernels.so",
            "macos-x86_64/libmcv2kernels.dylib", "macos-aarch64/libmcv2kernels.dylib",
            "windows-x86_64/mcv2kernels.dll", "windows-aarch64/mcv2kernels.dll")
        val sums = natives.resolve("SHA256SUMS").readLines()
        assertEquals(expectedPaths, sums.map { it.substringAfter("  ") }.toSet())
        assertTrue(sums.all { it.startsWith("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad  ") })
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad  mcv2.cpp\n",
            natives.resolve("SOURCES").readText())
        assertFalse(task.outputDirectory.get().file("stale.dll").asFile.exists())
        assertFalse(natives.walkTopDown().any { it.extension == "lib" })
    }

    @Test
    fun wrongCompilerVersionFailsBeforeReplacingAnyOutput() {
        val task = compiler("wrong-version")
        val failure = assertThrows(GradleException::class.java) { task.build() }
        assertTrue(failure.message!!.contains("MCV2 requires Zig test-version"))
        assertTrue(failure.message!!.endsWith("is wrong-version"))
        assertTrue(task.outputDirectory.get().file("stale.dll").asFile.exists())
        assertEquals(listOf(listOf("version")), commands)
    }

    private fun compiler(version: String): RecordingBuild {
        val project = ProjectBuilder.builder().withProjectDir(directory.toFile()).build()
        val task = project.tasks.register("natives", RecordingBuild::class.java).get()
        task.zigVersion.set("test-version")
        task.zigExecutable.set(directory.resolve("zig").toFile())
        task.sourcesDirectory.set(directory.resolve("sources").toFile().apply { mkdirs() })
        task.sourcesDirectory.file("mcv2.cpp").get().asFile.writeText("abc")
        task.sourcesDirectory.file(".clang-format").get().asFile.writeText("formatter")
        task.outputDirectory.set(directory.resolve("output").toFile().apply { mkdirs() })
        task.outputDirectory.file("stale.dll").get().asFile.writeText("stale")
        task.cacheDirectory.set(directory.resolve("cache").toFile())
        val operations = mock(ExecOperations::class.java)
        doAnswer { invocation ->
            val specification = mock(ExecSpec::class.java, RETURNS_SELF)
            invocation.getArgument<Action<ExecSpec>>(0).execute(specification)
            val calls = mockingDetails(specification).invocations
            val command = calls.single { it.method.name == "commandLine" }.rawArguments.single()
            val arguments = when (command) {
                is List<*> -> command.map { it.toString() }.drop(1)
                else -> (command as Array<*>).map { it.toString() }.drop(1)
            }
            commands.add(arguments)
            if (arguments == listOf("version")) {
                (calls.single { it.method.name == "setStandardOutput" }.rawArguments[0] as OutputStream).write(version.toByteArray())
            } else {
                assertTrue(calls.any { it.method.name == "environment" && it.rawArguments.toList() == listOf("SOURCE_DATE_EPOCH", "0") })
                val output = File(arguments[arguments.indexOf("-o") + 1])
                output.writeText("abc")
                if (output.extension == "dll") output.resolveSibling("mcv2kernels.lib").writeText("unused")
            }
            mock(ExecResult::class.java).also { result -> `when`(result.assertNormalExitValue()).thenReturn(result) }
        }.`when`(operations).exec(any())
        task.recordedProcesses = operations
        return task
    }
}
