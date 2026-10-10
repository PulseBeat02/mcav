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

import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.HexFormat
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DownloadToolArchiveTest {

    @TempDir
    lateinit var directory: Path

    @Test
    fun installsTheVerifiedZipAndMakesItsToolExecutable() {
        val task = zippedTool()
        task.install()
        val installed = directory.resolve("installed/tool/bin/tool").toFile()
        assertEquals("verified executable\n", installed.readText())
        assertTrue(installed.canExecute())
    }

    @Test
    fun checksumMismatchDoesNotExtractUntrustedBytesOrReplaceAnExistingTool() {
        val task = zippedTool()
        val installed = directory.resolve("installed/tool/bin/tool").toFile()
        installed.parentFile.mkdirs()
        installed.writeText("previous verified tool\n")
        val expected = "0".repeat(64)
        val actual = task.sha256.get()
        task.sha256.set(expected)
        val failure = assertThrows(GradleException::class.java) { task.install() }
        assertEquals(
            "SHA-256 mismatch for ${task.archiveUrl.get()}: expected $expected, got $actual; archive was not installed",
            failure.message
        )
        assertEquals("previous verified tool\n", installed.readText())
        assertFalse(task.temporaryDir.resolve("tool.zip").exists())
    }

    @Test
    fun invalidChecksumFailsBeforeDownloading() {
        val task = zippedTool()
        task.sha256.set("missing")
        val failure = assertThrows(GradleException::class.java) { task.install() }
        assertEquals(
            "Invalid pinned SHA-256 for ${task.archiveUrl.get()}: expected 64 lowercase hexadecimal digits",
            failure.message
        )
        assertFalse(directory.resolve("installed").toFile().exists())
    }

    @Test
    fun downloadFailureIdentifiesTheUnavailableArchive() {
        val task = zippedTool()
        task.archiveUrl.set(directory.resolve("missing.zip").toUri().toString())
        val failure = assertThrows(GradleException::class.java) { task.install() }
        assertTrue(failure.message!!.startsWith("Could not download tool archive from ${task.archiveUrl.get()}:"))
        assertFalse(directory.resolve("installed").toFile().exists())
    }

    @Test
    fun aVerifiedArchiveMustContainTheRequestedExecutable() {
        val task = zippedTool()
        task.executablePath.set("missing")
        val failure = assertThrows(GradleException::class.java) { task.install() }
        assertEquals("Tool archive ${task.archiveUrl.get()} does not contain missing", failure.message)
    }

    @Test
    fun extractsGzipAndXzArchivesWithoutAnExternalUnpacker() {
        val archives = mapOf(
            "tar.gz" to "H4sIAAAAAAACA+3NQQqCQAAF0Fl3Cm/gWInn0ZpAkAQbo+M3tQnaF0Hvbf7nb36e56kexnOdSwmfEYuubZ9ZvGeM2/2rP/Ym7romVDF8wXrJ/VLuw3+6pmU8jelYpVs6rLkfprQJAAAAAAAAAAAA/Lw7jjgM0wAoAAA=",
            "tar.xz" to "/Td6WFoAAATm1rRGAgAhARYAAAB0L+Wj4Cf/AHNdADob7NgvJU6bwGN6v5a3JejC7neVC0X31phps0q2DkY/2CgXahtiZSmSEfj8sTJH859ZU5IrKQ3BNzSNn9Lgm5Pjp01y3THUEfiMj5qL+5NnvL7IiCthCBuqOOi+oVXsi4XON87LGOIoT5AAvVZcrNZukgAAAEjvLyQMjRxeAAGPAYBQAADrwzaQscRn+wIAAAAABFla"
        )
        archives.forEach { (extension, encoded) ->
            val archive = directory.resolve("tool.$extension").toFile()
            archive.writeBytes(Base64.getDecoder().decode(encoded))
            val project = ProjectBuilder.builder().withProjectDir(directory.resolve(extension).toFile()).build()
            val task = project.tasks.register("downloadTool", DownloadToolArchive::class.java).get()
            task.archiveUrl.set(archive.toURI().toString())
            task.sha256.set(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(archive.readBytes())))
            task.executablePath.set("tool/bin/tool")
            task.installationDirectory.set(directory.resolve("installed-$extension").toFile())
            task.install()
            val installed = directory.resolve("installed-$extension/tool/bin/tool").toFile()
            assertEquals("verified executable\n", installed.readText())
            assertTrue(installed.canExecute())
        }
    }

    private fun zippedTool(): DownloadToolArchive {
        val archive = directory.resolve("tool.zip").toFile()
        ZipOutputStream(archive.outputStream()).use { output ->
            output.putNextEntry(ZipEntry("tool/bin/tool"))
            output.write("verified executable\n".toByteArray())
            output.closeEntry()
        }
        val project = ProjectBuilder.builder().withProjectDir(directory.toFile()).build()
        return project.tasks.register("downloadTool", DownloadToolArchive::class.java).get().apply {
            archiveUrl.set(archive.toURI().toString())
            sha256.set(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(archive.readBytes())))
            executablePath.set("tool/bin/tool")
            installationDirectory.set(directory.resolve("installed").toFile())
        }
    }
}
