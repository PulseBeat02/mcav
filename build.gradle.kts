plugins {
    id("com.diffplug.spotless")
}

group = "me.brandonli"
version = "1.0.0-SNAPSHOT"
description = "mcav"

repositories {
    mavenCentral()
}

// every module shares the conventions in buildSrc/src/main/kotlin/mcav.java-conventions.gradle.kts; the sandbox folder
// only groups the sandbox plugin and has no build file of its own, so it gets no Java tasks
subprojects {
    if (buildFile.exists()) {
        apply(plugin = "mcav.java-conventions")
    }
}

// the modules format their own sources; this formats the files of the repository around them
spotless {
    format("repository") {
        target(
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
        targetExclude("**/build/**", "**/node_modules/**", "**/out/**")
        trimTrailingWhitespace()
        endWithNewline()
    }
}
