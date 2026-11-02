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

import com.github.gradle.node.NodeExtension
import me.brandonli.mcav.gradle.CatalogUtils
import me.brandonli.mcav.gradle.HostPlatform
import me.brandonli.mcav.gradle.NodeLayout
import me.brandonli.mcav.gradle.libs

plugins {
    java
    id("com.diffplug.spotless")
}

val prettierAlias: String = "prettier"
val prettierJavaPluginAlias: String = "prettier-plugin-java"
val javaParser: String = "java"
val javaTabWidth: Int = 2
val javaPrintWidth: Int = 140
val licenseHeaderPath: String = "HEADER"

val gradleScriptPattern: String = "*.gradle.kts"
val resourcePatterns: List<String> = listOf(
    "src/**/*.json",
    "src/**/*.yml",
    "src/**/*.yaml",
    "src/**/*.properties",
    "src/**/*.astub",
    "coverage-exceptions.txt",
    "*.md"
)
val excludedResourcePatterns: List<String> = listOf("**/build/**", "**/node_modules/**")

val spotlessJavaTaskPrefix: String = "spotlessJava"

val node: NodeExtension = rootProject.extensions.getByType<NodeExtension>()
val nodeSetup: TaskProvider<Task> = rootProject.tasks.named(NodeLayout.SETUP_TASK_NAME)
val host: HostPlatform = HostPlatform.current()
val nodeExecutable: Provider<File> = node.resolvedNodeDir.map { NodeLayout.executable(it, host).asFile }
val prettierVersion: String = CatalogUtils.versionOf(libs, prettierAlias)
val prettierJavaPluginVersion: String = CatalogUtils.versionOf(libs, prettierJavaPluginAlias)

spotless {
    java {
        prettier(mapOf(prettierAlias to prettierVersion, prettierJavaPluginAlias to prettierJavaPluginVersion))
            .config(
                mapOf(
                    "parser" to javaParser,
                    "tabWidth" to javaTabWidth,
                    "plugins" to listOf(prettierJavaPluginAlias),
                    "printWidth" to javaPrintWidth
                )
            )
            .nodeExecutable(nodeExecutable)
        licenseHeaderFile(rootProject.file(licenseHeaderPath))
        importOrder()
        removeUnusedImports()
        formatAnnotations()
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        target(gradleScriptPattern)
        trimTrailingWhitespace()
        endWithNewline()
    }
    format("resources") {
        target(*resourcePatterns.toTypedArray())
        targetExclude(*excludedResourcePatterns.toTypedArray())
        trimTrailingWhitespace()
        endWithNewline()
    }
}

tasks.matching { it.name.startsWith(spotlessJavaTaskPrefix) }.configureEach {
    this.dependsOn(nodeSetup)
}
