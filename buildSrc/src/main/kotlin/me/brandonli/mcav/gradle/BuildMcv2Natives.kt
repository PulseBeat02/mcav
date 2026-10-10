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
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.LocalState
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

@CacheableTask
abstract class BuildMcv2Natives : DefaultTask() {

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourcesDirectory: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val zigExecutable: RegularFileProperty

    @get:Input
    abstract val zigVersion: Property<String>

    @get:Input
    abstract val toolchainChecksum: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:LocalState
    abstract val cacheDirectory: DirectoryProperty

    @get:Inject
    protected abstract val processes: ExecOperations

    @get:Input
    val compilerFlags = listOf(
        "-std=c++17", "-O2", "-fPIC", "-fno-exceptions", "-fno-rtti", "-fvisibility=hidden",
        "-ffp-contract=off", "-fwrapv", "-fno-strict-aliasing", "-Wall", "-Wextra", "-Werror"
    )

    @TaskAction
    fun build() {
        val versionOutput = ByteArrayOutputStream()
        processes.exec {
            commandLine(zigExecutable.get().asFile, "version")
            standardOutput = versionOutput
        }.assertNormalExitValue()
        val actualVersion = versionOutput.toString(Charsets.UTF_8).trim()
        if (actualVersion != zigVersion.get()) {
            throw GradleException("MCV2 requires Zig ${zigVersion.get()}, but ${zigExecutable.get().asFile} is $actualVersion")
        }
        val output = outputDirectory.get().asFile
        output.deleteRecursively()
        val natives = output.resolve("mcav/mcv2/natives")
        val libraries = mutableListOf<File>()
        val boundary = listOf("-mbranches-within-32B-boundaries")
        val avx512 = listOf("-mavx512f", "-mavx512dq", "-mavx512bw", "-mavx512vl",
            "-mavx512vbmi", "-mavx512vbmi2", "-mavx512vnni", "-mavx512bitalg")
        for (system in listOf("linux", "windows", "macos")) {
            for (architecture in listOf("x86_64", "aarch64")) {
                val platform = "$system-$architecture"
                val target = architecture + when (system) {
                    "linux" -> "-linux-gnu.2.28"
                    "windows" -> "-windows-gnu"
                    else -> "-macos.11.0"
                }
                val units = linkedMapOf("scalar" to emptyList<String>())
                if (architecture == "x86_64") {
                    units["sse2"] = emptyList()
                    units["sse41"] = listOf("-msse4.1")
                    units["avx2"] = listOf("-mavx2")
                    if (system != "macos") {
                        units["avx512"] = avx512
                        units.replaceAll { _, flags -> flags + boundary }
                    }
                } else {
                    units["neon"] = emptyList()
                    if (system == "linux") {
                        for (width in listOf(256, 512)) {
                            units["sve$width"] = listOf("-mcpu=baseline+sve", "-msve-vector-bits=$width")
                        }
                    }
                }
                val objects = units.map { (level, flags) ->
                    val objectFile = temporaryDir.resolve("$platform/level_$level.o")
                    objectFile.parentFile.mkdirs()
                    runZig(listOf("c++", "-target", target, "-mcpu=baseline") + compilerFlags + flags +
                        listOf("-c", "level_$level.cpp", "-o", objectFile.absolutePath))
                    objectFile.absolutePath
                }
                val name = when (system) {
                    "windows" -> "mcv2kernels.dll"
                    "macos" -> "libmcv2kernels.dylib"
                    else -> "libmcv2kernels.so"
                }
                val library = natives.resolve("$platform/$name")
                library.parentFile.mkdirs()
                val linkFlags = when (system) {
                    "linux" -> listOf("-Wl,-z,noexecstack")
                    "macos" -> listOf("-Wl,-install_name,@rpath/libmcv2kernels.dylib")
                    else -> emptyList()
                }
                runZig(listOf("cc", "-target", target, "-shared", "-s") + linkFlags +
                    listOf("-o", library.absolutePath) + objects)
                library.parentFile.listFiles()!!.filter { it.extension == "lib" }.forEach { it.delete() }
                libraries.add(library)
            }
        }
        writeManifest(natives.resolve("SHA256SUMS"), natives, libraries)
        val sources = sourcesDirectory.get().asFile
        writeManifest(natives.resolve("SOURCES"), sources, sources.listFiles()!!.filter { it.name != ".clang-format" })
    }

    private fun runZig(arguments: List<String>) {
        processes.exec {
            workingDir(sourcesDirectory)
            commandLine(listOf(zigExecutable.get().asFile.absolutePath) + arguments)
            environment("SOURCE_DATE_EPOCH", "0")
            environment("ZIG_GLOBAL_CACHE_DIR", cacheDirectory.get().asFile.absolutePath)
        }.assertNormalExitValue()
    }

    private fun writeManifest(manifest: File, root: File, files: List<File>) {
        manifest.writeText(files.sortedBy { it.relativeTo(root).invariantSeparatorsPath }.joinToString("", transform = { file ->
            val digest = ToolDigests.sha256(file)
            "$digest  ${file.relativeTo(root).invariantSeparatorsPath}\n"
        }))
    }
}
