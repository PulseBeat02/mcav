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
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import me.brandonli.mcav.gradle.BuildMcv2Natives.Work.NativeSystem
import me.brandonli.mcav.gradle.BuildMcv2Natives.Work.NativeTarget
import me.brandonli.mcav.gradle.BuildMcv2Natives.Work.Parameters
import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.process.ExecOperations
import org.gradle.testfixtures.ProjectBuilder
import org.gradle.workers.WorkQueue
import org.gradle.workers.WorkerExecutor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.`when`
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.invocation.InvocationOnMock

class BuildMcv2NativesTest {

    private companion object {

        const val TEST_VERSION: String = "test-version"

        const val ZIG_EXECUTABLE: String = "zig"
        const val VERSION_COMMAND: String = "version"
        const val COMPILE_COMMAND: String = "c++"
        const val LINK_COMMAND: String = "cc"

        const val MACOS: String = "macos"

        const val X86_64: String = "x86_64"

        const val MAIN_SOURCE: String = "mcv2.cpp"
        const val AVX2_SOURCE: String = "level_avx2.cpp"
        const val AVX512_SOURCE: String = "level_avx512.cpp"

        const val STALE_LIBRARY: String = "stale.dll"
        const val NATIVES_PATH: String = "mcav/mcv2/natives"

        const val LINUX_X86_64_LIBRARY: String = "linux-x86_64/libmcv2kernels.so"
        const val LINUX_AARCH64_LIBRARY: String = "linux-aarch64/libmcv2kernels.so"
        const val MACOS_X86_64_LIBRARY: String = "macos-x86_64/libmcv2kernels.dylib"
        const val MACOS_AARCH64_LIBRARY: String = "macos-aarch64/libmcv2kernels.dylib"
        const val WINDOWS_X86_64_LIBRARY: String = "windows-x86_64/mcv2kernels.dll"
        const val WINDOWS_AARCH64_LIBRARY: String = "windows-aarch64/mcv2kernels.dll"

        const val SOURCES_DIRECTORY: String = "sources"
        const val OUTPUT_DIRECTORY: String = "output"
        const val CACHE_DIRECTORY: String = "cache"
        const val CLANG_FORMAT_FILE: String = ".clang-format"
        const val CHECKSUMS_MANIFEST: String = "SHA256SUMS"
        const val SOURCES_MANIFEST: String = "SOURCES"
        const val SVE_SOURCE_PREFIX: String = "level_sve"
    }

    @TempDir
    lateinit var directory: Path

    private lateinit var processes: RecordedProcesses

    private val commands: List<List<String>>
        get() {
            return this.processes.commands.map { it.drop(1) }
        }

    private val submitted: MutableList<RecordingWork> = mutableListOf<RecordingWork>()

    @Test
    fun shouldHandTheCompilesAndLinksToAGradleWorker(): Unit {
        val task: RecordingBuild = this.compiler(TEST_VERSION)
        task.build()
        assertEquals(listOf(listOf(VERSION_COMMAND)), this.commands)

        val staleBeforeWork: Boolean = task.outputDirectory.get().file(STALE_LIBRARY).asFile.exists()
        assertTrue(staleBeforeWork)

        this.submitted.single().execute()

        val compileCount: Int = this.commands.count { it.first() == COMPILE_COMMAND }
        assertEquals(22, compileCount)

        val linkCount: Int = this.commands.count { it.first() == LINK_COMMAND }
        assertEquals(6, linkCount)

        val staleAfterWork: Boolean = task.outputDirectory.get().file(STALE_LIBRARY).asFile.exists()
        assertFalse(staleAfterWork)
    }

    @Test
    fun shouldBuildAllTargetsWithTheirBaselineAndOnlyTheirOwnExtensions(): Unit {
        val task: RecordingBuild = this.compiler(TEST_VERSION)
        this.buildWithWorkers(task)

        val links: List<List<String>> = this.commands.filter { it.first() == LINK_COMMAND }
        val targets: Set<String> = links.map { it[2] }.toSet()
        assertEquals(setOf("x86_64-linux-gnu.2.28", "aarch64-linux-gnu.2.28", "x86_64-windows-gnu",
            "aarch64-windows-gnu", "x86_64-macos.11.0", "aarch64-macos.11.0"), targets)

        val linkCount: Int = links.size
        assertEquals(6, linkCount)

        val shared: Boolean = links.all { "-shared" in it && "-s" in it }
        assertTrue(shared)

        val linuxStacksNotExecutable: Boolean = links.filter { "linux" in it[2] }.all { "-Wl,-z,noexecstack" in it }
        assertTrue(linuxStacksNotExecutable)

        val macosInstallNamed: Boolean = links.filter { MACOS in it[2] }.all { "-Wl,-install_name,@rpath/libmcv2kernels.dylib" in it }
        assertTrue(macosInstallNamed)

        val compiles: List<List<String>> = this.commands.filter { it.first() == COMPILE_COMMAND }
        val compileCount: Int = compiles.size
        assertEquals(22, compileCount)

        for (command in compiles) {
            this.assertCompileFlags(command)
        }

        val reproducible: Boolean = this.processes.environments.drop(1).all { it["SOURCE_DATE_EPOCH"] == "0" }
        assertTrue(reproducible)

        val natives: File = task.outputDirectory.get().asFile.resolve(NATIVES_PATH)
        val expectedPaths: List<String> = listOf(LINUX_AARCH64_LIBRARY, LINUX_X86_64_LIBRARY,
            MACOS_AARCH64_LIBRARY, MACOS_X86_64_LIBRARY,
            WINDOWS_AARCH64_LIBRARY, WINDOWS_X86_64_LIBRARY)
        val sums: List<String> = natives.resolve(CHECKSUMS_MANIFEST).readLines()
        val summedPaths: List<String> = sums.map { it.substringAfter("  ") }
        assertEquals(expectedPaths, summedPaths)

        val summedContents: Boolean = sums.all { it.startsWith("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad  ") }
        assertTrue(summedContents)

        val sourceSums: String = natives.resolve(SOURCES_MANIFEST).readText()
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad  mcv2.cpp\n", sourceSums)

        val staleKept: Boolean = task.outputDirectory.get().file(STALE_LIBRARY).asFile.exists()
        assertFalse(staleKept)

        val importLibraryKept: Boolean = natives.walkTopDown().any { it.extension == "lib" }
        assertFalse(importLibraryKept)

        val poolsShutDown: List<Boolean> = this.submitted.single().pools.map { it.isShutdown }
        assertEquals(listOf(true, true), poolsShutDown)
    }

    @Test
    fun shouldCloseEverySourceAndLibraryDirectoryOnceItIsListed(): Unit {
        OpenFiles.assumeListable()

        val task: RecordingBuild = this.compiler(TEST_VERSION)
        this.buildWithWorkers(task)

        val sources: File = task.sourcesDirectory.get().asFile
        val output: File = task.outputDirectory.get().asFile
        val directories: List<File> = output.walkTopDown().filter { it.isDirectory }.toList() + sources
        val open: List<File> = directories.filter(OpenFiles::isOpen)
        assertEquals(listOf<File>(), open)
    }

    @Test
    fun shouldCompileAsManyObjectsAtOnceAsItHasProcesses(): Unit {
        val bothStarted: CountDownLatch = CountDownLatch(2)
        val running: AtomicInteger = AtomicInteger()
        val mostAtOnce: AtomicInteger = AtomicInteger()
        val task: RecordingBuild = this.compiler(TEST_VERSION, 2) { this.compileAlongside(running, mostAtOnce, bothStarted) }
        this.buildWithWorkers(task)

        val mostCompilesAtOnce: Int = mostAtOnce.get()
        assertEquals(2, mostCompilesAtOnce)

        val compileCount: Int = this.commands.count { it.first() == COMPILE_COMMAND }
        assertEquals(22, compileCount)
    }

    @Test
    fun shouldFailTheBuildOnAFailingCompileBeforeAnyLink(): Unit {
        val failure: IllegalStateException = IllegalStateException("zig: error: unable to compile")
        val task: RecordingBuild = this.compiler(TEST_VERSION) { this.failOnAvx2(it, failure) }
        val thrown: GradleException = assertThrows(GradleException::class.java) { this.buildWithWorkers(task) }
        val message: String? = thrown.message
        assertEquals("Zig could not build MCV2's natives!", message)

        val cause: Throwable? = thrown.cause
        assertSame(failure, cause)

        val linked: Boolean = this.commands.any { it.first() == LINK_COMMAND }
        assertFalse(linked)

        val poolsShutDown: List<Boolean> = this.submitted.single().pools.map { it.isShutdown }
        assertEquals(listOf(true), poolsShutDown)
    }

    @Test
    fun shouldFailOnAWrongCompilerVersionBeforeReplacingAnyOutput(): Unit {
        val task: RecordingBuild = this.compiler("wrong-version")
        val failure: GradleException = assertThrows(GradleException::class.java) { task.build() }
        val zig: String = this.directory.resolve(ZIG_EXECUTABLE).toString()
        val message: String? = failure.message
        assertEquals("$zig reports wrong-version, which is not the zig test-version this build pins!", message)

        val staleKept: Boolean = task.outputDirectory.get().file(STALE_LIBRARY).asFile.exists()
        assertTrue(staleKept)

        val recordedCommands: List<List<String>> = this.processes.commands
        assertEquals(listOf(listOf(zig, VERSION_COMMAND)), recordedCommands)

        val nothingSubmitted: Boolean = this.submitted.isEmpty()
        assertTrue(nothingSubmitted)
    }

    @Test
    fun shouldEqualOnlyATargetOfTheSameSystemAndArchitecture(): Unit {
        val target: NativeTarget = NativeTarget(NativeSystem.LINUX, X86_64)
        val same: NativeTarget = NativeTarget(NativeSystem.LINUX, X86_64)
        val otherArchitecture: NativeTarget = NativeTarget(NativeSystem.LINUX, "aarch64")
        val otherSystem: NativeTarget = NativeTarget(NativeSystem.WINDOWS, X86_64)

        assertEquals(target, target)
        assertEquals(same, target)

        val sameHash: Int = same.hashCode()
        val targetHash: Int = target.hashCode()
        assertEquals(sameHash, targetHash)

        val otherHash: Int = otherArchitecture.hashCode()
        assertNotEquals(otherHash, targetHash)

        assertNotEquals(otherArchitecture, target)
        assertNotEquals(otherSystem, target)
        assertNotEquals(target, X86_64)
    }

    @Test
    fun shouldShowTheSystemAndTheArchitectureOfATarget(): Unit {
        val target: NativeTarget = NativeTarget(NativeSystem.MACOS, X86_64)
        val text: String = target.toString()
        assertEquals("NativeTarget(system=MACOS, architecture=x86_64)", text)
    }

    private fun compiler(version: String, processCount: Int = 4, onCompile: (List<String>) -> Unit = {}): RecordingBuild {
        val project: Project = ProjectBuilder.builder().withProjectDir(this.directory.toFile()).build()
        val task: RecordingBuild = project.tasks.register("natives", RecordingBuild::class.java).get()
        this.configureDirectories(task)
        task.zigVersion.set(TEST_VERSION)
        task.processCount.set(processCount)

        this.processes = RecordedProcesses("$version\n") { this.runZig(it, onCompile) }
        task.recordedProcesses = this.processes.operations
        task.recordedWorkers = this.recordingWorkers(project)

        return task
    }

    private fun configureDirectories(task: RecordingBuild): Unit {
        task.zigExecutable.set(this.directory.resolve(ZIG_EXECUTABLE).toFile())
        task.sourcesDirectory.set(this.directory.resolve(SOURCES_DIRECTORY).toFile().apply { this.mkdirs() })
        task.sourcesDirectory.file(MAIN_SOURCE).get().asFile.writeText("abc")
        task.sourcesDirectory.file(CLANG_FORMAT_FILE).get().asFile.writeText("formatter")
        task.outputDirectory.set(this.directory.resolve(OUTPUT_DIRECTORY).toFile().apply { this.mkdirs() })
        task.outputDirectory.file(STALE_LIBRARY).get().asFile.writeText("stale")
        task.cacheDirectory.set(this.directory.resolve(CACHE_DIRECTORY).toFile())
    }

    private fun runZig(command: List<String>, onCompile: (List<String>) -> Unit): Unit {
        val arguments: List<String> = command.drop(1)
        if (arguments == listOf(VERSION_COMMAND)) {
            return
        }

        if (arguments.first() == COMPILE_COMMAND) {
            onCompile(arguments)
        }

        val output: File = File(arguments[arguments.indexOf("-o") + 1])
        output.writeText("abc")

        if (output.extension == "dll") {
            output.resolveSibling("mcv2kernels.lib").writeText("unused")
        }
    }

    private fun recordingWorkers(project: Project): WorkerExecutor {
        val queue: WorkQueue = mock(WorkQueue::class.java)
        doAnswer { this.submit(project, it) }
            .`when`(queue)
            .submit(any<Class<BuildMcv2Natives.Work>>(), any<Action<Parameters>>())

        val workers: WorkerExecutor = mock(WorkerExecutor::class.java)
        `when`(workers.noIsolation()).thenReturn(queue)
        return workers
    }

    private fun submit(project: Project, invocation: InvocationOnMock): Unit {
        val workClass: Class<*> = invocation.getArgument<Class<*>>(0)
        assertEquals(BuildMcv2Natives.Work::class.java, workClass)

        val parameters: Parameters = project.objects.newInstance(Parameters::class.java)
        val action: Action<Parameters> = invocation.getArgument<Action<Parameters>>(1)
        action.execute(parameters)
        this.submitted.add(RecordingWork(parameters, this.processes.operations))
    }

    private fun buildWithWorkers(task: RecordingBuild): Unit {
        task.build()
        this.submitted.forEach { it.execute() }
    }

    private fun assertCompileFlags(command: List<String>): Unit {
        val baseline: Boolean = "-mcpu=baseline" in command
        assertTrue(baseline)

        val strict: Boolean = "-ffp-contract=off" in command && "-fwrapv" in command && "-fno-strict-aliasing" in command
        assertTrue(strict)

        val hostTuned: Boolean = command.any { "native" == it.substringAfter('=') || it == "-ffast-math" }
        assertFalse(hostTuned)

        val source: String = command[command.indexOf("-c") + 1]
        val avx2: Boolean = "-mavx2" in command
        assertEquals(source == AVX2_SOURCE, avx2)

        val avx512: Boolean = "-mavx512f" in command
        assertEquals(source == AVX512_SOURCE, avx512)

        val sve: Boolean = "-mcpu=baseline+sve" in command
        assertEquals(source.startsWith(SVE_SOURCE_PREFIX), sve)

        val branchesAligned: Boolean = "-mbranches-within-32B-boundaries" in command
        assertEquals(command[2].startsWith(X86_64) && MACOS !in command[2], branchesAligned)
    }

    private fun compileAlongside(running: AtomicInteger, mostAtOnce: AtomicInteger, bothStarted: CountDownLatch): Unit {
        mostAtOnce.accumulateAndGet(running.incrementAndGet(), ::maxOf)
        bothStarted.countDown()
        bothStarted.await(30, TimeUnit.SECONDS)
        running.decrementAndGet()
    }

    private fun failOnAvx2(arguments: List<String>, failure: IllegalStateException): Unit {
        if (AVX2_SOURCE in arguments) {
            throw failure
        }
    }

    abstract class RecordingBuild : BuildMcv2Natives() {

        lateinit var recordedProcesses: ExecOperations
        lateinit var recordedWorkers: WorkerExecutor

        override val processes: ExecOperations
            get() {
                return this.recordedProcesses
            }

        override val workers: WorkerExecutor
            get() {
                return this.recordedWorkers
            }
    }

    class RecordingWork(private val recordedParameters: Parameters, override val processes: ExecOperations) : BuildMcv2Natives.Work() {

        val pools: MutableList<ExecutorService> = mutableListOf<ExecutorService>()

        override fun getParameters(): Parameters {
            return this.recordedParameters
        }

        override fun newPool(threadCount: Int): ExecutorService {
            val pool: ExecutorService = super.newPool(threadCount)
            this.pools.add(pool)
            return pool
        }
    }
}
