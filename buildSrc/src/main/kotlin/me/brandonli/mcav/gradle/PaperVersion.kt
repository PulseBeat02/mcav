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

import java.util.Objects

internal class PaperVersion(val minecraft: String, val build: Int) {

    companion object {

        private const val FILE_PREFIX: String = "paper-"
        private const val NAME_SEPARATOR: Char = '-'
        private const val JAR_EXTENSION: String = ".jar"

        private const val MINECRAFT_GROUP: Int = 1
        private const val BUILD_GROUP: Int = 2

        private val VERSION: Regex = Regex("(.+)\\.build\\.([0-9]+)(?:-.+)?")

        fun parse(version: String): PaperVersion {
            val message: String = "Paper version $version must name a build!"
            val match: MatchResult = VERSION.matchEntire(version) ?: throw IllegalArgumentException(message)
            val build: Int = match.groupValues[BUILD_GROUP].toIntOrNull() ?: throw IllegalArgumentException(message)
            val minecraft: String = match.groupValues[MINECRAFT_GROUP]
            return PaperVersion(minecraft, build)
        }
    }

    val fileName: String
        get() {
            return "$FILE_PREFIX${this.minecraft}$NAME_SEPARATOR${this.build}$JAR_EXTENSION"
        }

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }

        if (other !is PaperVersion) {
            return false
        }

        return this.minecraft == other.minecraft && this.build == other.build
    }

    override fun hashCode(): Int {
        return Objects.hash(this.minecraft, this.build)
    }

    override fun toString(): String {
        return "PaperVersion(minecraft=${this.minecraft}, build=${this.build})"
    }
}
