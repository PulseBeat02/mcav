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

import com.github.jengelman.gradle.plugins.shadow.transformers.ApacheNoticeResourceTransformer
import me.brandonli.mcav.gradle.CatalogUtils
import me.brandonli.mcav.gradle.MCAVPublishingExtension
import me.brandonli.mcav.gradle.libs

plugins {
    id("mcav.module")
    id("mcav.publishing")
    id("com.gradleup.shadow")
}

val mavenResolverSupplierAlias: String = "maven-resolver-supplier"

val plainClassifier: String = "plain"

val noticeFiles: List<String> = listOf("META-INF/NOTICE", "META-INF/NOTICE.txt", "META-INF/NOTICE.md")
val relocatedPackages: List<String> =
    listOf("com.ctc", "com.google", "jakarta.inject", "org.apache", "org.codehaus", "org.eclipse", "org.objectweb", "org.slf4j")
val relocationRoot: String = "me.brandonli.mcav.libs"

val builtJarProperty: String = "mcav.installer.jar"
val builtJarPath: String = "libs/mcav-installer.jar"

dependencies {
    implementation(CatalogUtils.libraryOf(libs, mavenResolverSupplierAlias))
}

extensions.configure<MCAVPublishingExtension> {
    bundledJar = tasks.shadowJar
}

tasks.jar {
    this.archiveClassifier = plainClassifier
}

tasks.shadowJar {
    this.archiveClassifier = ""
    this.mergeServiceFiles()
    this.filesMatching(noticeFiles) {
        this.duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }
    this.transform(ApacheNoticeResourceTransformer::class.java)
    this.inputs.property("noticeDuplicates", DuplicatesStrategy.INCLUDE.name)
    relocatedPackages.forEach { prefix -> this.relocate(prefix, "$relocationRoot.$prefix") }
}

tasks.assemble {
    this.dependsOn(tasks.shadowJar)
}

val builtJar: String = "-D$builtJarProperty=" + layout.buildDirectory.file(builtJarPath).get().asFile.absolutePath
tasks.test {
    this.jvmArgs("--add-opens", "java.base/java.net=ALL-UNNAMED")
    this.dependsOn(tasks.shadowJar)
    this.jvmArgs(builtJar)
}

tasks.named("pitest") {
    this.dependsOn(tasks.shadowJar)
}
