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
import java.util.zip.ZipFile
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ModLicensingTest {

    @TempDir
    lateinit var directory: Path

    @Test
    fun everyModArchiveCarriesTheExactLicenseAndNoticesWhileKeepingLoaderResourcesSeparate() {
        val project = directory.toFile()
        project.resolve("settings.gradle").writeText("rootProject.name = 'mcav-mod'\n")
        project.resolve("build.gradle").writeText("plugins { id 'mcav.mod' }\nversion = '1.0.0-SNAPSHOT'\n")
        val catalog = project.resolve("gradle/libs.versions.toml")
        catalog.parentFile.mkdirs()
        Path.of("..", "gradle", "libs.versions.toml").toFile().copyTo(catalog)
        val license = "Fixture license: preserve the whole file.\n"
        val notices = "Fixture third-party notices: preserve the whole file.\n"
        project.resolve("LICENSE").writeText(license)
        project.resolve("THIRD-PARTY-NOTICES.md").writeText(notices)
        project.resolve("src/main/resources/META-INF").mkdirs()
        project.resolve("src/main/resources/fabric.mod.json").writeText("{}\n")
        project.resolve("src/main/resources/META-INF/neoforge.mods.toml").writeText("modId = 'fixture'\n")
        GradleRunner.create()
            .withProjectDir(project)
            .withPluginClasspath()
            .withArguments("jar", "fabricJar", "neoforgeJar", "--no-parallel", "--max-workers=1")
            .build()
        for (classifier in listOf("plain", "fabric", "neoforge")) {
            ZipFile(directory.resolve("build/libs/mcav-mod-1.0.0-SNAPSHOT-$classifier.jar").toFile()).use { archive ->
                for ((name, expected) in mapOf("LICENSE-MCAV" to license, "THIRD-PARTY-NOTICES.md" to notices)) {
                    val entry = archive.getEntry("META-INF/$name")
                    assertNotNull(entry)
                    assertEquals(expected, archive.getInputStream(entry).reader().readText())
                }
                if (classifier == "neoforge") assertNull(archive.getEntry("fabric.mod.json"))
                else assertNotNull(archive.getEntry("fabric.mod.json"))
                if (classifier == "fabric") assertNull(archive.getEntry("META-INF/neoforge.mods.toml"))
                else assertNotNull(archive.getEntry("META-INF/neoforge.mods.toml"))
            }
        }
    }
}
