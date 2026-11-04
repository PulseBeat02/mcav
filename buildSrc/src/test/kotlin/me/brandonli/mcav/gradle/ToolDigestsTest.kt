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
import java.security.MessageDigest
import java.util.HexFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ToolDigestsTest {

    private companion object {

        const val CONTENTS_FILE: String = "contents"

        const val SHORT_CONTENTS: String = "abc"
    }

    @TempDir
    lateinit var directory: Path

    @Test
    fun shouldStreamEmptyShortAndMultipleBufferFilesWithoutChangingTheirDigest(): Unit {
        val file: File = this.directory.resolve(CONTENTS_FILE).toFile()
        file.writeBytes(byteArrayOf())

        val emptyDigest: String = ToolDigests.sha256(file)
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", emptyDigest)

        file.writeText(SHORT_CONTENTS)

        val shortDigest: String = ToolDigests.sha256(file)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", shortDigest)

        val contents: ByteArray = ByteArray(196_625) { (it % 251).toByte() }
        file.writeBytes(contents)

        val expected: String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(contents))
        val longDigest: String = ToolDigests.sha256(file)
        assertEquals(expected, longDigest)
    }

    @Test
    fun shouldCloseTheFileOnceItIsDigested(): Unit {
        OpenFiles.assumeListable()

        val file: File = this.directory.resolve(CONTENTS_FILE).toFile()
        file.writeText(SHORT_CONTENTS)
        ToolDigests.sha256(file)

        val open: Boolean = OpenFiles.isOpen(file)
        assertFalse(open)
    }
}
