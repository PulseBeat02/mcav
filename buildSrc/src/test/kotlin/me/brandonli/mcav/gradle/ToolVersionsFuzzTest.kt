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

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.code_intelligence.jazzer.junit.FuzzTest
import java.io.OutputStream
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.process.ExecOperations
import org.gradle.process.ExecResult
import org.gradle.process.ExecSpec
import org.gradle.process.JavaExecSpec
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag

// A tool prints its version as it likes, so any output must either pass or fail as a GradleException.
@Tag("fuzz")
class ToolVersionsFuzzTest {

    private companion object {

        const val TOOL: String = "tool"
        const val PREFIX: String = "tool "

        const val MAXIMUM_VERSION_LENGTH: Int = 16
        const val MAXIMUM_OUTPUT_LENGTH: Int = 256

        const val LINE_BREAK: String = "\n"
    }

    @FuzzTest
    fun shouldPassOnlyAnOutputThatHoldsTheExpectedVersion(data: FuzzedDataProvider): Unit {
        val versionText: String = data.consumeString(MAXIMUM_VERSION_LENGTH)
        val deliveredVersion: String = this.deliver(versionText)
        val expected: String = deliveredVersion.filterNot { it.isWhitespace() }
        if (expected.isEmpty()) {
            return
        }

        val output: String = data.consumeString(MAXIMUM_OUTPUT_LENGTH)
        val delivered: String = this.deliver(output)
        val passed: Boolean = this.verifies(delivered, expected)
        val held: Boolean = !passed || delivered.contains(PREFIX + expected)
        assertTrue(held, "An output that passes must hold the expected version!")

        val leading: String = PREFIX + expected + LINE_BREAK + delivered
        val leadingPassed: Boolean = this.verifies(leading, expected)
        assertTrue(leadingPassed, "An output that starts with the expected version must pass!")
    }

    // a process hands over bytes, so text that UTF-8 cannot carry, such as a lone surrogate, arrives replaced
    private fun deliver(text: String): String {
        val bytes: ByteArray = text.toByteArray(Charsets.UTF_8)
        return String(bytes, Charsets.UTF_8)
    }

    private fun verifies(output: String, expected: String): Boolean {
        val processes: ExecOperations = PrintingProcesses(output)
        try {
            ToolVersions.verify(processes, TOOL, TOOL, expected, PREFIX)
        } catch (exception: GradleException) {
            return false
        }

        return true
    }

    // Mockito's inline mocks crash the fuzzing JVM under Jazzer's agent, so a JDK proxy stands in for the process
    private class PrintingProcesses(private val output: String) : ExecOperations {

        override fun exec(action: Action<in ExecSpec>): ExecResult {
            val streams: MutableList<OutputStream> = mutableListOf()
            val loader: ClassLoader = ExecSpec::class.java.classLoader
            val interfaces: Array<Class<*>> = arrayOf(ExecSpec::class.java)
            val handler: InvocationHandler =
                InvocationHandler { proxy, method, arguments -> this.record(proxy, method, arguments, streams) }
            val specification: ExecSpec = Proxy.newProxyInstance(loader, interfaces, handler) as ExecSpec
            action.execute(specification)

            val bytes: ByteArray = this.output.toByteArray(Charsets.UTF_8)
            streams.forEach { it.write(bytes) }
            return ExitedNormally
        }

        override fun javaexec(action: Action<in JavaExecSpec>): ExecResult {
            val message: String = "Tool versions never start a Java process!"
            throw UnsupportedOperationException(message)
        }

        private fun record(proxy: Any, method: Method, arguments: Array<Any?>?, streams: MutableList<OutputStream>): Any? {
            if (method.name == "setStandardOutput") {
                streams += arguments!![0] as OutputStream
            }

            return if (method.returnType.isInstance(proxy)) proxy else null
        }
    }

    private object ExitedNormally : ExecResult {

        override fun getExitValue(): Int {
            return 0
        }

        override fun assertNormalExitValue(): ExecResult {
            return this
        }

        override fun rethrowFailure(): ExecResult {
            return this
        }
    }
}
