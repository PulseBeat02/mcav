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

plugins {
    id("com.diffplug.spotless")
}

repositories {
    mavenCentral()
}

spotless {
    format("repository") {
        target(
            fileTree(rootDir) {
                include(
                    "*.md",
                    "*.yml",
                    "*.yaml",
                    "gradle/wrapper/gradle-wrapper.properties",
                    "gradle/libs.versions.toml",
                    "*.json",
                    "*.properties",
                    "*.gradle.kts",
                    "HEADER",
                    "LICENSE",
                    ".editorconfig",
                    ".gitattributes",
                    ".gitignore",
                    "gradle/checker-framework/**/*.astub",
                    "buildSrc/**/*.kt",
                    "buildSrc/**/*.kts",
                    "mcav-docs/**/*.md",
                    "mcav-docs/**/*.yml",
                    "mcav-docs/**/*.py",
                    "mcav-docs/**/*.txt",
                    "mcav-docs/*.gradle.kts",
                    "buildSrc/src/main/resources/uv-checksums.properties",
                    "buildSrc/src/main/resources/zig-checksums.properties",
                    "mcav-docs/requirements.lock",
                    ".github/**/*.md",
                    ".github/**/*.yml",
                    ".github/**/*.json",
                    "mcav-http/mcav-website/src/**/*.ts",
                    "mcav-http/mcav-website/src/**/*.tsx",
                    "mcav-http/mcav-website/src/**/*.css",
                    "mcav-http/mcav-website/*.json",
                    "mcav-http/mcav-website/*.mjs",
                    "mcav-http/mcav-website/*.ts"
                )
                exclude("**/build/**", "**/node_modules/**", "**/out/**")
            }
        )
        trimTrailingWhitespace()
        endWithNewline()
    }
}

tasks.register("publishLibraries") {
    description = "Publishes the library modules to the snapshot repository and to build/e2e-repository"
    group = "publishing"
    dependsOn(
        listOf(
            "mcav-browser",
            "mcav-bukkit",
            "mcav-common",
            "mcav-discord",
            "mcav-http",
            "mcav-installer",
            "mcav-lwjgl",
            "mcav-vm",
            "mcav-vnc",
            "mcav-voicechat"
        ).map { ":$it:publish" }
    )
}
