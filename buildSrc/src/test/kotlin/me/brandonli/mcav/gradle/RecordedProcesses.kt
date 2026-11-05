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

import java.io.OutputStream
import org.gradle.api.Action
import org.gradle.process.ExecOperations
import org.gradle.process.ExecResult
import org.gradle.process.ExecSpec
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.RETURNS_SELF
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.mockito.invocation.Invocation
import org.mockito.invocation.InvocationOnMock

class RecordedProcesses(private val versionOutput: String, private val onCommand: (List<String>) -> Unit = {}) {

    val commands: MutableList<List<String>> = mutableListOf<List<String>>()

    val environments: MutableList<Map<String, Any?>> = mutableListOf<Map<String, Any?>>()

    val workingDirectories: MutableList<Any?> = mutableListOf<Any?>()

    val operations: ExecOperations = mock(ExecOperations::class.java)

    init {
        doAnswer { this.exec(it) }.`when`(this.operations).exec(any())
    }

    private fun exec(invocation: InvocationOnMock): ExecResult {
        val specification: ExecSpec = mock(ExecSpec::class.java, RETURNS_SELF)
        val action: Action<ExecSpec> = invocation.getArgument<Action<ExecSpec>>(0)
        action.execute(specification)

        val calls: Collection<Invocation> = mockingDetails(specification).invocations
        val command: List<String> = this.commandOf(calls)
        val environment: Map<String, Any?> = this.environmentOf(calls)
        val workingDirectory: Any? = calls.singleOrNull { it.method.name == "workingDir" }?.rawArguments?.get(0)
        this.record(command, environment, workingDirectory)
        this.writeVersion(calls, command)
        this.onCommand(command)

        return mock(ExecResult::class.java)
    }

    private fun commandOf(calls: Collection<Invocation>): List<String> {
        val arguments: Any? = this.argumentsOf(calls, "commandLine")[0]
        val values: List<Any?> = when (arguments) {
            is Array<*> -> arguments.toList()
            else -> (arguments as Iterable<*>).toList()
        }
        return values.map { it.toString() }
    }

    private fun argumentsOf(calls: Collection<Invocation>, method: String): Array<out Any?> {
        val call: Invocation = calls.single { it.method.name == method }
        return call.rawArguments
    }

    private fun environmentOf(calls: Collection<Invocation>): Map<String, Any?> {
        val environment: MutableMap<String, Any?> = mutableMapOf<String, Any?>()
        val environmentCalls: List<Invocation> = calls.filter { it.method.name == "environment" }
        environmentCalls.forEach { this.addEnvironment(environment, it.rawArguments) }

        return environment
    }

    private fun addEnvironment(environment: MutableMap<String, Any?>, arguments: Array<out Any?>): Unit {
        when (arguments.size) {
            1 -> (arguments[0] as Map<*, *>).forEach { (name, value) -> environment[name as String] = value }
            else -> environment[arguments[0] as String] = arguments[1]
        }
    }

    @Synchronized
    private fun record(command: List<String>, environment: Map<String, Any?>, workingDirectory: Any?): Unit {
        this.commands += command
        this.environments += environment
        this.workingDirectories += workingDirectory
    }

    private fun writeVersion(calls: Collection<Invocation>, command: List<String>): Unit {
        val last: String = command.last()
        if (last != "--version" && last != "version") {
            return
        }

        val output: OutputStream = this.argumentsOf(calls, "setStandardOutput")[0] as OutputStream
        output.write(this.versionOutput.toByteArray())
    }
}
