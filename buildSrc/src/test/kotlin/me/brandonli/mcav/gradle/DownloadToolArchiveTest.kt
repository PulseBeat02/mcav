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
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URLConnection
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.HexFormat
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.logging.Logger
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DownloadToolArchiveTest {

    private companion object {

        const val TASK_NAME: String = "downloadTool"

        const val ARCHIVE_FILE: String = "tool.zip"
        const val MISSING_ARCHIVE: String = "missing.zip"
        const val EXECUTABLE_PATH: String = "tool/bin/tool"
        const val INSTALLATION_DIRECTORY: String = "installed"
        const val INSTALLED_EXECUTABLE: String = "installed/tool/bin/tool"
        const val MISSING_EXECUTABLE_PATH: String = "missing"
        const val SEVEN_ZIP_ARCHIVE: String = "tool.7z"
        const val XZ_ARCHIVE_FILE: String = "tool.tar.xz"
        const val UNPACKED_TAR_FILE: String = "archive.tar"

        const val GZIP_EXTENSION: String = "tar.gz"
        const val XZ_EXTENSION: String = "tar.xz"

        const val GZIP_ARCHIVE: String =
            "H4sIAAAAAAACA+3NQQqCQAAF0Fl3Cm/gWInn0ZpAkAQbo+M3tQnaF0Hvbf7nb36e56kexnOdSwmfEYuubZ9ZvGeM2/2rP/Ym7romVDF8wXrJ/VLuw3+6pmU8" +
            "jelYpVs6rLkfprQJAAAAAAAAAAAA/Lw7jjgM0wAoAAA="
        const val XZ_ARCHIVE: String =
            "/Td6WFoAAATm1rRGAgAhARYAAAB0L+Wj4Cf/AHNdADob7NgvJU6bwGN6v5a3JejC7neVC0X31phps0q2DkY/2CgXahtiZSmSEfj8sTJH859ZU5IrKQ3BNzSN" +
            "n9Lgm5Pjp01y3THUEfiMj5qL+5NnvL7IiCthCBuqOOi+oVXsi4XON87LGOIoT5AAvVZcrNZukgAAAEjvLyQMjRxeAAGPAYBQAADrwzaQscRn+wIAAAAABFla"

        const val EXECUTABLE_CONTENT: String = "verified executable\n"

        const val DIGEST_ALGORITHM: String = "SHA-256"
    }

    @TempDir
    lateinit var directory: Path

    @Test
    fun shouldInstallTheVerifiedZipAndMakeItsToolExecutable(): Unit {
        val task: DownloadToolArchive = this.zippedTool()
        task.install()

        val installed: File = this.directory.resolve(INSTALLED_EXECUTABLE).toFile()
        val content: String = installed.readText()
        assertEquals(EXECUTABLE_CONTENT, content)

        val executable: Boolean = installed.canExecute()
        assertTrue(executable)
    }

    @Test
    fun shouldReportTheDownloadAndTheVerifiedDigest(): Unit {
        val task: RecordedDownloadToolArchive = this.zippedTool()
        task.install()

        val address: String = task.archiveUrl.get()
        val digest: String = task.sha256.get()
        val messages: List<String> = task.recorder.messages
        assertEquals(listOf("lifecycle: Downloading tool archive $address.", "lifecycle: Verified SHA-256 $digest of $address."), messages)
    }

    @Test
    fun shouldOpenTheDownloadWithAConnectAndAReadTimeout(): Unit {
        val task: RecordedDownloadToolArchive = this.zippedTool()
        task.install()

        val connection: URLConnection = task.connections.single()
        val timeouts: Pair<Int, Int> = connection.connectTimeout to connection.readTimeout
        assertEquals(30_000 to 120_000, timeouts)
    }

    @Test
    fun shouldCloseTheDownloadOnceTheArchiveIsWritten(): Unit {
        val task: RecordedDownloadToolArchive = this.zippedTool()
        task.install()

        val connection: URLConnection = task.connections.single()
        val download: InputStream = connection.getInputStream()
        assertThrows(IOException::class.java) { download.read() }
    }

    @Test
    fun shouldNeitherExtractUntrustedBytesNorReplaceAnExistingToolOnAChecksumMismatch(): Unit {
        val task: DownloadToolArchive = this.zippedTool()
        val installed: File = this.directory.resolve(INSTALLED_EXECUTABLE).toFile()
        installed.parentFile.mkdirs()
        installed.writeText("previous verified tool\n")

        val expected: String = "0".repeat(64)
        val actual: String = task.sha256.get()
        task.sha256.set(expected)

        val failure: GradleException = assertThrows(GradleException::class.java) { task.install() }
        val message: String? = failure.message
        assertEquals(
            "Tool archive ${task.archiveUrl.get()} has SHA-256 $actual instead of the pinned $expected, so it was not installed!",
            message
        )

        val content: String = installed.readText()
        assertEquals("previous verified tool\n", content)

        val archiveKept: Boolean = task.temporaryDir.resolve(ARCHIVE_FILE).exists()
        assertFalse(archiveKept)
    }

    @Test
    fun shouldFailOnAnInvalidChecksumBeforeDownloading(): Unit {
        val task: DownloadToolArchive = this.zippedTool()
        task.sha256.set("missing")

        val failure: IllegalArgumentException = assertThrows(IllegalArgumentException::class.java) { task.install() }
        val message: String? = failure.message
        assertEquals("Pinned SHA-256 of ${task.archiveUrl.get()} must be 64 lowercase hexadecimal digits!", message)

        val installed: Boolean = this.directory.resolve(INSTALLATION_DIRECTORY).toFile().exists()
        assertFalse(installed)
    }

    @Test
    fun shouldNameTheUnavailableArchiveWhenTheDownloadFails(): Unit {
        val task: DownloadToolArchive = this.zippedTool()
        task.archiveUrl.set(this.directory.resolve(MISSING_ARCHIVE).toUri().toString())

        val failure: GradleException = assertThrows(GradleException::class.java) { task.install() }
        val message: String? = failure.message
        assertEquals("Tool archive ${task.archiveUrl.get()} could not be downloaded!", message)

        val installed: Boolean = this.directory.resolve(INSTALLATION_DIRECTORY).toFile().exists()
        assertFalse(installed)
    }

    @Test
    fun shouldRequireTheRequestedExecutableInAVerifiedArchive(): Unit {
        val task: DownloadToolArchive = this.zippedTool()
        task.executablePath.set(MISSING_EXECUTABLE_PATH)

        val failure: GradleException = assertThrows(GradleException::class.java) { task.install() }
        val message: String? = failure.message
        assertEquals("Tool archive ${task.archiveUrl.get()} must contain missing to be installed!", message)
    }

    @Test
    fun shouldNotUnpackAVerifiedArchiveOfAnotherFormat(): Unit {
        val task: DownloadToolArchive = this.zippedTool()
        val archive: File = this.directory.resolve(SEVEN_ZIP_ARCHIVE).toFile()
        this.directory.resolve(ARCHIVE_FILE).toFile().copyTo(archive)
        task.archiveUrl.set(archive.toURI().toString())

        val failure: IllegalArgumentException = assertThrows(IllegalArgumentException::class.java) { task.install() }
        val message: String? = failure.message
        assertEquals("Tool archive ${task.archiveUrl.get()} must be a .zip, .tar.gz or .tar.xz file!", message)

        val installed: Boolean = this.directory.resolve(INSTALLATION_DIRECTORY).toFile().exists()
        assertFalse(installed)
    }

    @Test
    fun shouldExtractGzipAndXzArchivesWithoutAnExternalUnpacker(): Unit {
        val archives: Map<String, String> = mapOf(
            GZIP_EXTENSION to GZIP_ARCHIVE,
            XZ_EXTENSION to XZ_ARCHIVE
        )
        val installed: Map<String, Pair<String, Boolean>> =
            archives.mapValues { (extension, encoded) -> this.installArchive(extension, encoded) }
        val expected: Map<String, Pair<String, Boolean>> = archives.mapValues { EXECUTABLE_CONTENT to true }
        assertEquals(expected, installed)
    }

    @Test
    fun shouldCloseTheXzArchiveAndTheTarItUnpacksOnceTheToolIsInstalled(): Unit {
        OpenFiles.assumeListable()

        val task: DownloadToolArchive = this.installEncodedArchive(XZ_EXTENSION, XZ_ARCHIVE)
        val temporary: File = task.temporaryDir
        val files: List<File> = listOf(temporary.resolve(XZ_ARCHIVE_FILE), temporary.resolve(UNPACKED_TAR_FILE))
        val open: List<File> = files.filter(OpenFiles::isOpen)
        assertEquals(listOf<File>(), open)
    }

    private fun zippedTool(): RecordedDownloadToolArchive {
        val archive: File = this.directory.resolve(ARCHIVE_FILE).toFile()
        ZipOutputStream(archive.outputStream()).use(this::writeExecutable)

        val project: Project = ProjectBuilder.builder().withProjectDir(this.directory.toFile()).build()
        val installation: File = this.directory.resolve(INSTALLATION_DIRECTORY).toFile()
        return this.registerTask(project, archive, installation)
    }

    private fun writeExecutable(output: ZipOutputStream): Unit {
        output.putNextEntry(ZipEntry(EXECUTABLE_PATH))
        output.write(EXECUTABLE_CONTENT.toByteArray())
        output.closeEntry()
    }

    private fun registerTask(project: Project, archive: File, installation: File): RecordedDownloadToolArchive {
        val messageDigest: MessageDigest = MessageDigest.getInstance(DIGEST_ALGORITHM)
        val digest: ByteArray = messageDigest.digest(archive.readBytes())
        val checksum: String = HexFormat.of().formatHex(digest)
        return project.tasks.register(TASK_NAME, RecordedDownloadToolArchive::class.java).get().apply {
            this.archiveUrl.set(archive.toURI().toString())
            this.sha256.set(checksum)
            this.executablePath.set(EXECUTABLE_PATH)
            this.installationDirectory.set(installation)
        }
    }

    private fun installArchive(extension: String, encoded: String): Pair<String, Boolean> {
        this.installEncodedArchive(extension, encoded)

        val tool: File = this.directory.resolve("installed-$extension/tool/bin/tool").toFile()
        return tool.readText() to tool.canExecute()
    }

    private fun installEncodedArchive(extension: String, encoded: String): DownloadToolArchive {
        val archive: File = this.directory.resolve("tool.$extension").toFile()
        val bytes: ByteArray = Base64.getDecoder().decode(encoded)
        archive.writeBytes(bytes)

        val projectDirectory: File = this.directory.resolve(extension).toFile()
        val project: Project = ProjectBuilder.builder().withProjectDir(projectDirectory).build()
        val installation: File = this.directory.resolve("installed-$extension").toFile()
        val task: DownloadToolArchive = this.registerTask(project, archive, installation)
        task.install()

        return task
    }

    abstract class RecordedDownloadToolArchive : DownloadToolArchive() {

        val recorder: RecordingLogger = RecordingLogger()

        val connections: MutableList<URLConnection> = mutableListOf<URLConnection>()

        override fun getLogger(): Logger {
            return this.recorder.logger
        }

        override fun openConnection(uri: URI): URLConnection {
            val connection: URLConnection = super.openConnection(uri)
            this.connections += connection

            return connection
        }
    }
}
