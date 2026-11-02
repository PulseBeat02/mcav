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

import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import org.gradle.api.GradleException

/**
 * Captures the console of a jcstress run and checks it for skipped tests.
 */
object JcstressConsole {

    private const val TEST_GROUP: Int = 1

    private const val LINE_SEPARATOR: String = "\n"

    private val SKIPPED_LINE: Regex = Regex("""\[SKIPPED]\s+(\S+)""")

    /**
     * Returns a stream that writes to both [console] and a buffered stream of [file].
     *
     * Closing the stream flushes [console] and closes the stream of [file].
     *
     * @param console the console stream
     * @param file    the file that receives a copy of the console
     * @return the stream
     */
    fun tee(console: OutputStream, file: File): OutputStream {
        return Tee(console, BufferedOutputStream(file.outputStream()))
    }

    /**
     * Returns the names of the tests that a jcstress console marks as `[SKIPPED]`, each once and in console order.
     *
     * @param lines the lines of the console
     * @return the names of the skipped tests
     */
    fun skippedTests(lines: List<String>): List<String> {
        return lines.mapNotNull { SKIPPED_LINE.find(it)?.groupValues?.get(TEST_GROUP) }
            .distinct()
    }

    /**
     * Checks that a jcstress console marks no test as skipped.
     *
     * @param console the file that holds the console
     * @throws GradleException if a test was skipped, listing every skipped test
     */
    fun checkNoneSkipped(console: File): Unit {
        val lines: List<String> = console.readLines()
        val skipped: List<String> = this.skippedTests(lines)
        if (skipped.isNotEmpty()) {
            val names: String = skipped.joinToString(LINE_SEPARATOR)
            val message: String =
                "jcstress skipped ${skipped.size} tests that failed their sanity check and never ran!$LINE_SEPARATOR$names"
            throw GradleException(message)
        }
    }

    private class Tee(private val console: OutputStream, private val file: OutputStream) : OutputStream() {

        override fun write(byte: Int): Unit {
            this.console.write(byte)
            this.file.write(byte)
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int): Unit {
            this.console.write(bytes, offset, length)
            this.file.write(bytes, offset, length)
        }

        override fun flush(): Unit {
            this.console.flush()
            this.file.flush()
        }

        override fun close(): Unit {
            this.console.flush()
            this.file.close()
        }
    }
}
