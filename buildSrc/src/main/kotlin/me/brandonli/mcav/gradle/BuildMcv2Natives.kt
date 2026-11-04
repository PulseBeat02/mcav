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
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.LocalState
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkerExecutor

/**
 * Builds MCV2's native kernels with Zig: one shared library for each of Linux, Windows and macOS on x86-64 and aarch64.
 *
 * Each instruction set level that a platform supports, from `scalar` up to `avx512` or `sve512`, compiles from its own
 * `level_<name>.cpp` into an object, and the platform's objects link into its library. Next to the libraries, in the
 * `mcav/mcv2/natives` folder of [outputDirectory], the task writes a `SHA256SUMS` manifest of them and a `SOURCES`
 * manifest of the sources. It checks the Zig version first and hands the build to a Gradle worker.
 */
@CacheableTask
abstract class BuildMcv2Natives : DefaultTask() {

    private companion object {

        const val ZIG_TOOL: String = "zig"
        const val ZIG_VERSION_ARGUMENT: String = "version"

        const val NATIVES_PATH: String = "mcav/mcv2/natives"
        const val CHECKSUMS_MANIFEST: String = "SHA256SUMS"
        const val SOURCES_MANIFEST: String = "SOURCES"
        const val CLANG_FORMAT_FILE: String = ".clang-format"

        const val LEVEL_PREFIX: String = "level_"
        const val SOURCE_SUFFIX: String = ".cpp"
        const val OBJECT_SUFFIX: String = ".o"
        const val IMPORT_LIBRARY_EXTENSION: String = "lib"

        const val LINUX: String = "linux"
        const val WINDOWS: String = "windows"
        const val MACOS: String = "macos"

        const val X86_64: String = "x86_64"
        const val AARCH64: String = "aarch64"

        const val LINUX_TARGET_SUFFIX: String = "-linux-gnu.2.28"
        const val WINDOWS_TARGET_SUFFIX: String = "-windows-gnu"
        const val MACOS_TARGET_SUFFIX: String = "-macos.11.0"

        const val LINUX_LIBRARY: String = "libmcv2kernels.so"
        const val WINDOWS_LIBRARY: String = "mcv2kernels.dll"
        const val MACOS_LIBRARY: String = "libmcv2kernels.dylib"

        const val TARGET_OPTION: String = "-target"
        const val OUTPUT_OPTION: String = "-o"

        const val SOURCE_DATE_VARIABLE: String = "SOURCE_DATE_EPOCH"
        const val REPRODUCIBLE_SOURCE_DATE: String = "0"
        const val ZIG_CACHE_VARIABLE: String = "ZIG_GLOBAL_CACHE_DIR"

        const val MANIFEST_SEPARATOR: String = "  "
        const val LINE_SEPARATOR: String = "\n"

        val SVE_VECTOR_BITS: List<Int> = listOf(256, 512)
    }

    /**
     * The directory of the C++ sources, with one `level_<name>.cpp` for each instruction set level.
     */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourcesDirectory: DirectoryProperty

    /**
     * The Zig executable that compiles and links the kernels.
     */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val zigExecutable: RegularFileProperty

    /**
     * The Zig version that [zigExecutable] must report.
     */
    @get:Input
    abstract val zigVersion: Property<String>

    /**
     * The SHA-256 of the Zig archive, so that another toolchain of the same version builds the natives again.
     */
    @get:Input
    abstract val toolchainChecksum: Property<String>

    /**
     * The directory whose `mcav/mcv2/natives` folder receives the libraries and their manifests.
     */
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    /**
     * The Zig cache directory.
     */
    @get:LocalState
    abstract val cacheDirectory: DirectoryProperty

    /**
     * The number of Zig processes that run at once.
     */
    @get:Internal
    abstract val processCount: Property<Int>

    /**
     * The service that runs the Zig version check.
     */
    @get:Inject
    protected abstract val processes: ExecOperations

    /**
     * The service that hands the build to a Gradle worker.
     */
    @get:Inject
    protected abstract val workers: WorkerExecutor

    /**
     * The compiler flags that every instruction set level shares.
     */
    @get:Input
    val compilerFlags: List<String> = listOf(
        "-std=c++17", "-O2", "-fPIC", "-fno-exceptions", "-fno-rtti", "-fvisibility=hidden",
        "-ffp-contract=off", "-fwrapv", "-fno-strict-aliasing", "-Wall", "-Wextra", "-Werror"
    )

    /**
     * Checks the Zig version and hands the build to a Gradle worker.
     *
     * @throws GradleException if Zig cannot run or reports another version
     */
    @TaskAction
    fun build(): Unit {
        val zig: File = this.zigExecutable.get().asFile
        val version: String = this.zigVersion.get()
        ToolVersions.verify(this.processes, zig, ZIG_TOOL, version, prefix = "", argument = ZIG_VERSION_ARGUMENT)

        val task: BuildMcv2Natives = this
        this.workers.noIsolation().submit(Work::class.java) {
            this.zigExecutable.set(task.zigExecutable)
            this.sourcesDirectory.set(task.sourcesDirectory)
            this.outputDirectory.set(task.outputDirectory)
            this.objectsDirectory.set(task.temporaryDir)
            this.cacheDirectory.set(task.cacheDirectory)
            this.compilerFlags.set(task.compilerFlags)
            this.processCount.set(task.processCount)
        }
    }

    /**
     * Compiles and links the natives of every platform, then writes their manifests.
     */
    abstract class Work : WorkAction<Work.Parameters> {

        /**
         * The service that runs Zig.
         */
        @get:Inject
        protected abstract val processes: ExecOperations

        /**
         * Compiles every instruction set level of every platform, links each platform's library and writes the manifests.
         *
         * @throws GradleException if a compile or a link fails
         */
        override fun execute(): Unit {
            val output: File = this.parameters.outputDirectory.get().asFile
            output.deleteRecursively()

            val natives: File = output.resolve(NATIVES_PATH)
            val targets: List<NativeTarget> = listOf(LINUX, WINDOWS, MACOS).flatMap(this::targetsOf)
            val compiles: List<Runnable> = targets.flatMap(this::planCompiles)
            val links: List<Runnable> = targets.map { this.planLink(natives, it) }
            this.runInParallel(compiles)
            this.runInParallel(links)

            val libraries: List<File> = targets.map { this.libraryOf(natives, it) }
            this.writeManifest(natives.resolve(CHECKSUMS_MANIFEST), natives, libraries)

            val sources: File = this.parameters.sourcesDirectory.get().asFile
            val sourceFiles: List<File> = this.listDirectory(sources).filter { it.name != CLANG_FORMAT_FILE }
            this.writeManifest(natives.resolve(SOURCES_MANIFEST), sources, sourceFiles)
        }

        private fun targetsOf(system: String): List<NativeTarget> {
            return listOf(X86_64, AARCH64).map { NativeTarget(system, it) }
        }

        private fun planCompiles(target: NativeTarget): List<Runnable> {
            val units: Map<String, List<String>> = this.unitsOf(target)
            return units.map { (level, flags) -> this.planCompile(target, level, flags) }
        }

        private fun unitsOf(target: NativeTarget): Map<String, List<String>> {
            val units: MutableMap<String, List<String>> = linkedMapOf("scalar" to emptyList<String>())
            if (target.architecture == X86_64) {
                this.addX86Units(units, target.system)
                this.alignX86Branches(units, target.system)
            } else {
                this.addArmUnits(units, target.system)
            }

            return units
        }

        private fun addX86Units(units: MutableMap<String, List<String>>, system: String): Unit {
            units["sse2"] = emptyList()
            units["sse41"] = listOf("-msse4.1")
            units["avx2"] = listOf("-mavx2")

            if (system == MACOS) {
                return
            }

            units["avx512"] = listOf(
                "-mavx512f", "-mavx512dq", "-mavx512bw", "-mavx512vl",
                "-mavx512vbmi", "-mavx512vbmi2", "-mavx512vnni", "-mavx512bitalg"
            )
        }

        private fun alignX86Branches(units: MutableMap<String, List<String>>, system: String): Unit {
            if (system == MACOS) {
                return
            }

            units.replaceAll { _, flags -> flags + "-mbranches-within-32B-boundaries" }
        }

        private fun addArmUnits(units: MutableMap<String, List<String>>, system: String): Unit {
            units["neon"] = emptyList()

            if (system != LINUX) {
                return
            }

            for (width in SVE_VECTOR_BITS) {
                units["sve$width"] = listOf("-mcpu=baseline+sve", "-msve-vector-bits=$width")
            }
        }

        private fun planCompile(target: NativeTarget, level: String, flags: List<String>): Runnable {
            val objectFile: File = this.objectFileOf(target, level)
            val triple: String = this.tripleOf(target)
            val compilerFlags: List<String> = this.parameters.compilerFlags.get()
            val source: String = "$LEVEL_PREFIX$level$SOURCE_SUFFIX"
            val arguments: List<String> = listOf("c++", TARGET_OPTION, triple, "-mcpu=baseline") + compilerFlags + flags +
                listOf("-c", source, OUTPUT_OPTION, objectFile.absolutePath)
            return Runnable { this.compile(arguments, objectFile) }
        }

        private fun objectFileOf(target: NativeTarget, level: String): File {
            val objectsDirectory: File = this.parameters.objectsDirectory.get().asFile
            return objectsDirectory.resolve(target.platform).resolve("$LEVEL_PREFIX$level$OBJECT_SUFFIX")
        }

        private fun tripleOf(target: NativeTarget): String {
            val suffix: String = when (target.system) {
                LINUX -> LINUX_TARGET_SUFFIX
                WINDOWS -> WINDOWS_TARGET_SUFFIX
                else -> MACOS_TARGET_SUFFIX
            }
            return target.architecture + suffix
        }

        private fun compile(arguments: List<String>, objectFile: File): Unit {
            objectFile.parentFile.mkdirs()
            this.runZig(arguments)
        }

        private fun runZig(arguments: List<String>): Unit {
            val sources: File = this.parameters.sourcesDirectory.get().asFile
            val zig: String = this.parameters.zigExecutable.get().asFile.absolutePath
            val cache: String = this.parameters.cacheDirectory.get().asFile.absolutePath
            this.processes.exec {
                this.workingDir(sources)
                this.commandLine(listOf(zig) + arguments)
                this.environment(SOURCE_DATE_VARIABLE, REPRODUCIBLE_SOURCE_DATE)
                this.environment(ZIG_CACHE_VARIABLE, cache)
            }
        }

        private fun planLink(natives: File, target: NativeTarget): Runnable {
            val library: File = this.libraryOf(natives, target)
            val triple: String = this.tripleOf(target)
            val linkFlags: List<String> = this.linkFlagsOf(target)
            val levels: Set<String> = this.unitsOf(target).keys
            val objects: List<String> = levels.map { this.objectFileOf(target, it).absolutePath }
            val arguments: List<String> = listOf("cc", TARGET_OPTION, triple, "-shared", "-s") + linkFlags +
                listOf(OUTPUT_OPTION, library.absolutePath) + objects
            return Runnable { this.link(arguments, library) }
        }

        private fun libraryOf(natives: File, target: NativeTarget): File {
            val name: String = when (target.system) {
                WINDOWS -> WINDOWS_LIBRARY
                MACOS -> MACOS_LIBRARY
                else -> LINUX_LIBRARY
            }
            return natives.resolve(target.platform).resolve(name)
        }

        private fun linkFlagsOf(target: NativeTarget): List<String> {
            return when (target.system) {
                LINUX -> listOf("-Wl,-z,noexecstack")
                MACOS -> listOf("-Wl,-install_name,@rpath/$MACOS_LIBRARY")
                else -> emptyList()
            }
        }

        private fun link(arguments: List<String>, library: File): Unit {
            library.parentFile.mkdirs()
            this.runZig(arguments)

            val siblings: List<File> = this.listDirectory(library.parentFile)
            val importLibraries: List<File> = siblings.filter { it.extension == IMPORT_LIBRARY_EXTENSION }
            for (importLibrary in importLibraries) {
                importLibrary.delete()
            }
        }

        private fun listDirectory(directory: File): List<File> {
            return Files.list(directory.toPath()).use { paths -> paths.map { it.toFile() }.toList() }
        }

        private fun runInParallel(steps: List<Runnable>): Unit {
            val processCount: Int = this.parameters.processCount.get()
            val pool: ExecutorService = this.newPool(minOf(processCount, steps.size))
            try {
                val tasks: List<Callable<Any?>> = steps.map { Executors.callable(it) }
                val results: List<Future<Any?>> = pool.invokeAll(tasks)
                for (result in results) {
                    this.awaitStep(result)
                }
            } finally {
                pool.shutdown()
            }
        }

        // hook for the pitest mutant that drops the shutdown: a test checks that the pools it returns are shut down
        protected open fun newPool(threadCount: Int): ExecutorService {
            return Executors.newFixedThreadPool(threadCount)
        }

        private fun awaitStep(result: Future<Any?>): Unit {
            try {
                result.get()
            } catch (exception: ExecutionException) {
                val message: String = "Zig could not build MCV2's natives!"
                throw GradleException(message, exception.cause)
            }
        }

        private fun writeManifest(manifest: File, root: File, files: List<File>): Unit {
            val sorted: List<File> = files.sortedBy { this.relativePathOf(root, it) }
            val lines: String = sorted.joinToString("") { this.manifestLine(root, it) }
            manifest.writeText(lines)
        }

        private fun relativePathOf(root: File, file: File): String {
            return file.relativeTo(root).invariantSeparatorsPath
        }

        private fun manifestLine(root: File, file: File): String {
            val digest: String = ToolDigests.sha256(file)
            val path: String = this.relativePathOf(root, file)
            return "$digest$MANIFEST_SEPARATOR$path$LINE_SEPARATOR"
        }

        private class NativeTarget(val system: String, val architecture: String) {

            val platform: String
                get() {
                    return "${this.system}-${this.architecture}"
                }
        }

        /**
         * The parameters that [BuildMcv2Natives] hands to its worker.
         */
        interface Parameters : WorkParameters {
            /**
             * The Zig executable.
             */
            val zigExecutable: RegularFileProperty

            /**
             * The directory of the C++ sources.
             */
            val sourcesDirectory: DirectoryProperty

            /**
             * The directory whose `mcav/mcv2/natives` folder receives the libraries and their manifests.
             */
            val outputDirectory: DirectoryProperty

            /**
             * The directory that the object files are written to.
             */
            val objectsDirectory: DirectoryProperty

            /**
             * The Zig cache directory.
             */
            val cacheDirectory: DirectoryProperty

            /**
             * The compiler flags that every instruction set level shares.
             */
            val compilerFlags: ListProperty<String>

            /**
             * The number of Zig processes that run at once.
             */
            val processCount: Property<Int>
        }
    }
}
