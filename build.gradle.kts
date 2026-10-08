plugins {
    id("com.diffplug.spotless")
}

group = "me.brandonli"
version = "1.0.0-SNAPSHOT"
description = "mcav"

repositories {
    mavenCentral()
}

// the modules format their own sources; this formats the files of the repository around them
spotless {
    format("repository") {
        // one tree whose excludes are part of it, so its walk skips those folders: targetExclude would walk a second tree
        // of every build and node_modules folder while nodeSetup and npm write into them ("Could not read path")
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
                    "mcav-docs/**/*.md",
                    "mcav-docs/**/*.yml",
                    "mcav-docs/**/*.py",
                    "mcav-docs/**/*.txt",
                    "mcav-docs/*.gradle.kts",
                    "buildSrc/src/main/resources/uv-checksums.properties",
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
