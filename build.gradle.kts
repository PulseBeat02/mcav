plugins {
    id("com.diffplug.spotless")
}

group = "me.brandonli"
version = "1.0.0-SNAPSHOT"
description = "mcav"

repositories {
    mavenCentral()
}

spotless {
    format("repository") {
        // targetExclude walks a second tree and can race with Node writes in build/node_modules ("Could not read path").
        target(
            fileTree(rootDir) {
                include(
                    "*.md",
                    "*.yml",
                    "*.yaml",
                    "gradle/wrapper/gradle-wrapper.properties",
                    "*.json",
                    "*.properties",
                    "*.gradle.kts",
                    "HEADER",
                    "LICENSE",
                    ".editorconfig",
                    ".gitattributes",
                    ".gitignore",
                    "checker-framework/*.astub",
                    "buildSrc/**/*.kt",
                    "buildSrc/**/*.kts",
                    "docs/**/*.md",
                    "docs/**/*.yml",
                    "docs/**/*.py",
                    "docs/**/*.txt",
                    ".github/**/*.md",
                    ".github/**/*.yml",
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
