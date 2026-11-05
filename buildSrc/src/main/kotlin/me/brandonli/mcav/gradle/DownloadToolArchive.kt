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
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.FileTree
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import org.tukaani.xz.XZInputStream

/**
 * Downloads a tool archive, checks its pinned SHA-256 and unpacks it into an installation directory.
 *
 * The task reads `.zip`, `.tar.gz` and `.tar.xz` archives and makes the tool's executable runnable. It installs nothing
 * when the digest of the download differs from the pinned one.
 */
@DisableCachingByDefault(because = "Downloaded tools are cached in their installation directory")
abstract class DownloadToolArchive : DefaultTask() {

    private companion object {

        const val CONNECT_TIMEOUT_MILLISECONDS: Int = 30_000
        const val READ_TIMEOUT_MILLISECONDS: Int = 120_000

        const val PATH_SEPARATOR: Char = '/'

        const val ZIP_EXTENSION: String = ".zip"
        const val GZIP_TAR_EXTENSION: String = ".tar.gz"
        const val XZ_TAR_EXTENSION: String = ".tar.xz"

        const val UNPACKED_TAR: String = "archive.tar"

        const val EXECUTABLE_PERMISSIONS: String = "rwxr-xr-x"

        val SHA256_DIGEST: Regex = Regex("[0-9a-f]{64}")
    }

    /**
     * The address of the archive.
     */
    @get:Input
    abstract val archiveUrl: Property<String>

    /**
     * The pinned SHA-256 of the archive, as 64 lowercase hexadecimal digits.
     */
    @get:Input
    abstract val sha256: Property<String>

    /**
     * The path of the tool's executable inside the archive.
     */
    @get:Input
    abstract val executablePath: Property<String>

    /**
     * The directory that the archive is unpacked into.
     */
    @get:OutputDirectory
    abstract val installationDirectory: DirectoryProperty

    /**
     * The service that opens the archive.
     */
    @get:Inject
    protected abstract val archives: ArchiveOperations

    /**
     * The service that copies the archive's contents into the installation directory.
     */
    @get:Inject
    protected abstract val files: FileSystemOperations

    /**
     * Downloads the archive, checks its digest and unpacks it.
     *
     * @throws GradleException          if the download fails, the digest differs or the archive lacks the executable
     * @throws IllegalArgumentException if the pinned digest is malformed or the archive is of another type
     */
    @TaskAction
    fun install(): Unit {
        val address: String = this.archiveUrl.get()
        val expected: String = this.sha256.get()
        if (!expected.matches(SHA256_DIGEST)) {
            val message: String = "Pinned SHA-256 of $address must be 64 lowercase hexadecimal digits!"
            throw IllegalArgumentException(message)
        }

        val archive: File = this.download(address)
        this.verifyChecksum(archive, address, expected)

        val contents: FileTree = this.openArchive(archive, address)
        this.installContents(contents)
        this.verifyExecutable(address)
    }

    private fun download(address: String): File {
        val uri: URI = URI(address)
        val fileName: String = uri.path.substringAfterLast(PATH_SEPARATOR)
        val archive: File = this.temporaryDir.resolve(fileName)
        this.logger.lifecycle("Downloading tool archive {}.", address)

        try {
            this.copyFrom(uri, archive)
        } catch (exception: IOException) {
            val message: String = "Tool archive $address could not be downloaded!"
            throw GradleException(message, exception)
        }

        return archive
    }

    private fun copyFrom(uri: URI, archive: File): Unit {
        val connection: URLConnection = this.openConnection(uri)
        connection.connectTimeout = CONNECT_TIMEOUT_MILLISECONDS
        connection.readTimeout = READ_TIMEOUT_MILLISECONDS
        connection.getInputStream().use { this.copy(it, archive) }
    }

    // hook for the pitest mutants that drop a timeout or the close: tests inspect the connection it returns
    internal open fun openConnection(uri: URI): URLConnection {
        return uri.toURL().openConnection()
    }

    private fun copy(input: InputStream, target: File): Unit {
        target.outputStream().use { input.copyTo(it) }
    }

    private fun verifyChecksum(archive: File, address: String, expected: String): Unit {
        val actual: String = ToolDigests.sha256(archive)
        if (actual != expected) {
            archive.delete()

            val message: String = "Tool archive $address has SHA-256 $actual instead of the pinned $expected, so it was not installed!"
            throw GradleException(message)
        }

        this.logger.lifecycle("Verified SHA-256 {} of {}.", expected, address)
    }

    private fun openArchive(archive: File, address: String): FileTree {
        val name: String = archive.name
        if (name.endsWith(ZIP_EXTENSION)) {
            return this.archives.zipTree(archive)
        }

        if (name.endsWith(GZIP_TAR_EXTENSION)) {
            return this.archives.tarTree(archive)
        }

        if (name.endsWith(XZ_TAR_EXTENSION)) {
            return this.unpackXz(archive)
        }

        val message: String = "Tool archive $address must be a .zip, .tar.gz or .tar.xz file!"
        throw IllegalArgumentException(message)
    }

    private fun unpackXz(archive: File): FileTree {
        val unpacked: File = this.temporaryDir.resolve(UNPACKED_TAR)
        XZInputStream(archive.inputStream()).use { this.copy(it, unpacked) }

        return this.archives.tarTree(unpacked)
    }

    private fun installContents(contents: FileTree): Unit {
        val installation: DirectoryProperty = this.installationDirectory
        val executable: String = this.executablePath.get()
        this.files.sync {
            this.from(contents)
            this.into(installation)
            this.filesMatching(executable) { this.permissions { this.unix(EXECUTABLE_PERMISSIONS) } }
        }
    }

    private fun verifyExecutable(address: String): Unit {
        val executable: String = this.executablePath.get()
        val installed: File = this.installationDirectory.file(executable).get().asFile
        if (!installed.isFile) {
            val message: String = "Tool archive $address must contain $executable to be installed!"
            throw GradleException(message)
        }
    }
}
