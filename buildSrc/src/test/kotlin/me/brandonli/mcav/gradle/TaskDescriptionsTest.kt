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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TaskDescriptionsTest {

    private companion object {

        const val CONVENTIONS_DIRECTORY: String = "src/main/kotlin"
        const val SCRIPT_EXTENSION: String = ".gradle.kts"
        const val BUILD_SCRIPT: String = "build.gradle.kts"

        const val TASK_NAME: String = """("\w+"|[\w.]+(?:\(\))?)"""
        const val DESCRIPTION: String = """"[A-Z][a-z]+s [^"\n]*[^."]""""

        val TASK_REGISTRATION: Regex = Regex("""tasks\.register\b""")
        val DESCRIBED_REGISTRATION: Regex = Regex(
            """tasks\.register(<\w+>)?\($TASK_NAME\) \{\n( +)(this\.|)group = "[a-z ]+"\n\3\4description = $DESCRIPTION\n"""
        )
    }

    @Test
    fun shouldStartEveryRegisteredTaskWithItsGroupAndAOneLineDescriptionInTheThirdPerson(): Unit {
        val conventions: List<File> = File(CONVENTIONS_DIRECTORY).listFiles { it.name.endsWith(SCRIPT_EXTENSION) }!!.toList()
        val scripts: List<File> = conventions + File(BUILD_SCRIPT)
        val undescribed: List<String> = scripts.sorted()
            .filter(this::hasUndescribedTask)
            .map { it.path }
        assertEquals(listOf<String>(), undescribed)
    }

    private fun hasUndescribedTask(script: File): Boolean {
        val text: String = script.readText()
        val registrations: Int = TASK_REGISTRATION.findAll(text).count()
        val described: Int = DESCRIBED_REGISTRATION.findAll(text).count()
        return registrations != described
    }
}
