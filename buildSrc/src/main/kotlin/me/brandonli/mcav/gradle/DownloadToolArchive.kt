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

import java.io.IOException
import java.net.URI
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import org.tukaani.xz.XZInputStream

@DisableCachingByDefault(because = "Downloaded tools are cached in their installation directory")
abstract class DownloadToolArchive : DefaultTask() {

    private companion object {
        const val DOWNLOADING = "Downloading tool archive {}"
        const val VERIFIED = "Verified SHA-256 {} for {}"
    }

    @get:Input
    abstract val archiveUrl: Property<String>

    @get:Input
    abstract val sha256: Property<String>

    @get:Input
    abstract val executablePath: Property<String>

    @get:OutputDirectory
    abstract val installationDirectory: DirectoryProperty

    @get:Inject
    protected abstract val archives: ArchiveOperations

    @get:Inject
    protected abstract val files: FileSystemOperations

    @TaskAction
    fun install() {
        val address = archiveUrl.get()
        val expected = sha256.get()
        if (!expected.matches(Regex("[0-9a-f]{64}"))) {
            throw GradleException("Invalid pinned SHA-256 for $address: expected 64 lowercase hexadecimal digits")
        }
        val archive = temporaryDir.resolve(URI(address).path.substringAfterLast('/'))
        logger.lifecycle(DOWNLOADING, address)
        try {
            val connection = URI(address).toURL().openConnection()
            connection.connectTimeout = 30_000
            connection.readTimeout = 120_000
            connection.getInputStream().use { input -> archive.outputStream().use { output -> input.copyTo(output) } }
        } catch (failure: IOException) {
            throw GradleException("Could not download tool archive from $address: ${failure.message}", failure)
        }
        val actual = ToolDigests.sha256(archive)
        if (actual != expected) {
            archive.delete()
            throw GradleException("SHA-256 mismatch for $address: expected $expected, got $actual; archive was not installed")
        }
        logger.lifecycle(VERIFIED, expected, address)
        val contents = when {
            archive.name.endsWith(".zip") -> archives.zipTree(archive)
            archive.name.endsWith(".tar.gz") -> archives.tarTree(archive)
            archive.name.endsWith(".tar.xz") -> {
                val unpacked = temporaryDir.resolve("archive.tar")
                XZInputStream(archive.inputStream()).use { input -> unpacked.outputStream().use { output -> input.copyTo(output) } }
                archives.tarTree(unpacked)
            }
            else -> throw GradleException("Unsupported tool archive format: $address (expected .zip, .tar.gz or .tar.xz)")
        }
        files.sync {
            from(contents)
            into(installationDirectory)
        }
        val executable = installationDirectory.file(executablePath).get().asFile
        if (!executable.isFile) {
            throw GradleException("Tool archive $address does not contain ${executablePath.get()}")
        }
        if (!executable.canExecute() && !executable.setExecutable(true)) {
            throw GradleException("Cannot make downloaded tool executable: $executable")
        }
    }
}
