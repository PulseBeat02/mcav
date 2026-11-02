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

/**
 * Finds the log calls in Java source whose message is built by string concatenation.
 *
 * A log call is a `trace`, `debug`, `info`, `warn` or `error` call on a receiver named like a logger, such as `LOGGER`
 * or `log`. It concatenates when one of its arguments joins a string literal with `+` outside any parentheses.
 */
object LogMessages {

    private const val RECEIVER_GROUP: Int = 1

    private const val CALL_START: Char = '('
    private const val ARGUMENT_SEPARATOR: Char = ','
    private const val CONCATENATION: Char = '+'
    private const val STRING_QUOTE: Char = '"'
    private const val NEW_LINE: Char = '\n'

    private const val FIRST_LINE_NUMBER: Int = 1

    private val CALL: Regex = Regex("""\b([A-Za-z_][A-Za-z0-9_]*)\s*\.\s*(trace|debug|info|warn|error)\s*\(""")

    private val LOGGER: Regex = Regex("""(?:[A-Z][A-Z0-9_]*_)?LOG(?:GER)?|log(?:ger)?|[a-z][A-Za-z0-9]*Logger""")

    private val OPENING: Set<Char> = setOf('(', '[', '{')
    private val CLOSING: Set<Char> = setOf(')', ']', '}')

    /**
     * Returns the log calls in [source] that build their message by concatenation, in source order.
     *
     * @param source the Java source
     * @return the concatenating log calls
     */
    fun find(source: String): List<ConcatenatedLogMessage> {
        val code: String = JavaSource.mask(source)
        val text: String = JavaSource.withoutComments(source)
        return CALL.findAll(code)
            .filter { LOGGER.matches(it.groupValues[RECEIVER_GROUP]) }
            .filter { call -> this.isConcatenated(text, code, call) }
            .map { call -> this.messageOf(source, call) }
            .toList()
    }

    private fun isConcatenated(text: String, code: String, call: MatchResult): Boolean {
        val argumentsStart: Int = call.range.last + 1
        val arguments: List<Argument> = this.arguments(text, code, argumentsStart)
        return arguments.any(Argument::joinsStrings)
    }

    private fun arguments(source: String, code: String, start: Int): List<Argument> {
        val ends: List<Int> = this.findArgumentEnds(code, start)
        val starts: List<Int> = listOf(start) + ends.map { it + 1 }
        val arguments: List<Argument> =
            starts.zip(ends) { first, end -> Argument(source.substring(first, end), code.substring(first, end)) }
        val noArguments: Boolean = arguments.size == 1 && arguments[0].text.isBlank()
        return if (noArguments) emptyList() else arguments
    }

    private fun findArgumentEnds(code: String, start: Int): List<Int> {
        val ends: MutableList<Int> = mutableListOf<Int>()
        var depth: Int = 0
        for (index in start until code.length) {
            val character: Char = code[index]
            val closesCall: Boolean = character in CLOSING && depth == 0
            val separatesArguments: Boolean = character == ARGUMENT_SEPARATOR && depth == 0
            when {
                character in OPENING -> depth++
                character in CLOSING && depth > 0 -> depth--
                closesCall || separatesArguments -> ends += index
            }

            if (closesCall) {
                break
            }
        }

        return ends
    }

    private fun messageOf(source: String, call: MatchResult): ConcatenatedLogMessage {
        val line: Int = this.lineOf(source, call.range.first)
        val method: String = call.value.substringBefore(CALL_START)
        return ConcatenatedLogMessage(line, method)
    }

    private fun lineOf(source: String, offset: Int): Int {
        val before: String = source.substring(0, offset)
        val newLines: Int = before.count { it == NEW_LINE }
        return newLines + FIRST_LINE_NUMBER
    }

    private class Argument(val text: String, private val code: String) {

        fun joinsStrings(): Boolean {
            var depth: Int = 0
            var plus: Boolean = false
            var literal: Boolean = false
            for ((index, character) in this.code.withIndex()) {
                when {
                    character in OPENING -> depth++
                    character in CLOSING -> depth--
                    depth > 0 -> Unit
                    character == CONCATENATION -> plus = true
                    this.text[index] == STRING_QUOTE -> literal = true
                }
            }

            return plus && literal
        }
    }
}
