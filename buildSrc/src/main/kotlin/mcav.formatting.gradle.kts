import me.brandonli.mcav.gradle.isWindows
import me.brandonli.mcav.gradle.libs
import me.brandonli.mcav.gradle.versionOf

plugins {
    java
    id("com.github.node-gradle.node")
    id("com.diffplug.spotless")
}

node {
    download = true
    version = libs.versionOf("node")
    workDir = layout.buildDirectory.dir("nodejs")
}

val nodeExecutable = node.resolvedNodeDir.map { it.file(if (isWindows) "node.exe" else "bin/node").asFile }

spotless {
    java {
        prettier(mapOf("prettier" to libs.versionOf("prettier"), "prettier-plugin-java" to libs.versionOf("prettier-plugin-java")))
            .config(mapOf("parser" to "java", "tabWidth" to 2, "plugins" to listOf("prettier-plugin-java"), "printWidth" to 140))
            .nodeExecutable(nodeExecutable)
        licenseHeaderFile(rootProject.file("HEADER"))
        importOrder()
        removeUnusedImports()
        formatAnnotations()
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        target("*.gradle.kts")
        trimTrailingWhitespace()
        endWithNewline()
    }
    format("resources") {
        target(
            "src/**/*.json",
            "src/**/*.yml",
            "src/**/*.yaml",
            "src/**/*.properties",
            "src/**/*.astub",
            "checker-framework/**/*.astub",
            "coverage-exceptions.txt",
            "*.md"
        )
        targetExclude("**/build/**", "**/node_modules/**")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

tasks.matching { it.name.startsWith("spotlessJava") }.configureEach {
    dependsOn("nodeSetup")
}
