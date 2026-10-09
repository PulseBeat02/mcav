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
import org.junit.jupiter.api.Test

class CatalogPinsTest {

    @Test
    fun readTheDocsUsesTheCatalogPythonMinor() {
        val repository = Path.of("..").toFile()
        val catalog = repository.resolve("gradle/libs.versions.toml").readText()
        val python = Regex("(?m)^docs-python = \"([^\"]+)\"").find(catalog)!!.groupValues[1]
        val readTheDocs = repository.resolve(".readthedocs.yaml").readText()
        val minor = Regex("(?m)^\\s+python: \"([0-9]+\\.[0-9]+)\"").find(readTheDocs)!!.groupValues[1]
        assertEquals(python.split('.').take(2).joinToString("."), minor)
    }
}
