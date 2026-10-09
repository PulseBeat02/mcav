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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GeneratedCatalogTest {

    @Test
    fun theGeneratedCatalogPreservesItsPinAndStartsWithTheRepositoryLicense() {
        val repository = Path.of("..").toFile()
        val source = Path.of("build/generated/sources/catalog/kotlin/me/brandonli/mcav/gradle/CatalogVersions.kt").toFile().readText()
        assertTrue(source.startsWith(repository.resolve("HEADER").readText()))
        val catalog = repository.resolve("gradle/libs.versions.toml").readText()
        val version = Regex("(?m)^jacoco = \"([^\"]+)\"").find(catalog)!!.groupValues[1]
        assertEquals(version, CatalogVersions.JACOCO)
        assertEquals(repository.resolve("HEADER").readText().trimEnd(), source.substringBefore("package").trimEnd())
    }
}
