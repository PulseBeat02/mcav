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
import java.util.zip.ZipFile
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ModLicensingTest {

    private companion object {

        const val REPOSITORY_DIRECTORY: String = ".."
        const val CATALOG_PATH: String = "gradle/libs.versions.toml"
        const val NOTICES_FILE: String = "THIRD-PARTY-NOTICES.md"

        const val RESOURCES_META_INF_PATH: String = "src/main/resources/META-INF"
        const val FABRIC_METADATA_PATH: String = "src/main/resources/fabric.mod.json"
        const val NEOFORGE_METADATA_PATH: String = "src/main/resources/META-INF/neoforge.mods.toml"

        const val LICENSE_ENTRY: String = "META-INF/LICENSE-MCAV"
        const val NOTICES_ENTRY: String = "META-INF/THIRD-PARTY-NOTICES.md"
        const val FABRIC_METADATA_ENTRY: String = "fabric.mod.json"
        const val NEOFORGE_METADATA_ENTRY: String = "META-INF/neoforge.mods.toml"

        const val SETTINGS_SCRIPT: String = "settings.gradle"
        const val BUILD_SCRIPT: String = "build.gradle"
        const val LICENSE_FILE: String = "LICENSE"
    }

    @TempDir
    lateinit var directory: Path

    @Test
    fun shouldCarryTheExactLicenseAndNoticesInEveryModArchiveWhileKeepingLoaderResourcesSeparate(): Unit {
        val project: File = this.directory.toFile()
        project.resolve(SETTINGS_SCRIPT).writeText("rootProject.name = 'mcav-mod'\n")
        project.resolve(BUILD_SCRIPT).writeText("plugins { id 'mcav.node'; id 'mcav.mod' }\nversion = '1.0.0-SNAPSHOT'\n")

        val catalog: File = project.resolve(CATALOG_PATH)
        catalog.parentFile.mkdirs()
        Path.of(REPOSITORY_DIRECTORY, CATALOG_PATH).toFile().copyTo(catalog)

        val license: String = "Fixture license: preserve the whole file.\n"
        val notices: String = "Fixture third-party notices: preserve the whole file.\n"
        project.resolve(LICENSE_FILE).writeText(license)
        project.resolve(NOTICES_FILE).writeText(notices)
        project.resolve(RESOURCES_META_INF_PATH).mkdirs()
        project.resolve(FABRIC_METADATA_PATH).writeText("{}\n")
        project.resolve(NEOFORGE_METADATA_PATH).writeText("modId = 'fixture'\n")
        GradleRunner.create()
            .withProjectDir(project)
            .withPluginClasspath()
            .withArguments("jar", "fabricJar", "neoForgeJar", "--no-parallel", "--max-workers=1")
            .build()

        val archives: Map<String, List<Any?>> = listOf("plain", "fabric", "neoforge").associateWith(this::describeArchive)
        val expected: Map<String, List<Any?>> = mapOf(
            "plain" to listOf(license, notices, true, true),
            "fabric" to listOf(license, notices, true, false),
            "neoforge" to listOf(license, notices, false, true)
        )
        assertEquals(expected, archives)
    }

    private fun describeArchive(classifier: String): List<Any?> {
        val file: File = this.directory.resolve("build/libs/mcav-mod-1.0.0-SNAPSHOT-$classifier.jar").toFile()
        return ZipFile(file).use(this::describeEntries)
    }

    private fun describeEntries(archive: ZipFile): List<Any?> {
        val license: String? = this.readEntry(archive, LICENSE_ENTRY)
        val notices: String? = this.readEntry(archive, NOTICES_ENTRY)
        val fabric: Boolean = archive.getEntry(FABRIC_METADATA_ENTRY) != null
        val neoforge: Boolean = archive.getEntry(NEOFORGE_METADATA_ENTRY) != null
        return listOf(license, notices, fabric, neoforge)
    }

    private fun readEntry(archive: ZipFile, name: String): String? {
        return archive.getEntry(name)?.let { archive.getInputStream(it).reader().readText() }
    }
}
