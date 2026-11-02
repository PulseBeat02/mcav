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

import me.brandonli.mcav.gradle.MCAVPublishingExtension

plugins {
    java
    `maven-publish`
    id("mcav.licensing")
}

val extensionName: String = "mcavPublishing"
val javadocEncoding: String = "UTF-8"

val endToEndRepositoryName: String = "endToEnd"
val endToEndRepositoryPath: String = "e2e-repository"
val snapshotRepositoryName: String = "brandonli"
val snapshotRepositoryUrl: String = "https://repo.brandonli.me/snapshots"

val publicationName: String = "maven"
val projectUrl: String = "https://github.com/PulseBeat02/mcav"
val licenseName: String = "GNU General Public License, version 3 or later"
val licenseUrl: String = "https://www.gnu.org/licenses/gpl-3.0.html"
val licenseDistribution: String = "repo"
val scmConnection: String = "scm:git:https://github.com/PulseBeat02/mcav.git"
val scmDeveloperConnection: String = "scm:git:ssh://git@github.com/PulseBeat02/mcav.git"

val publishedPomProperty: String = "mcav.published.pom"

val settings: MCAVPublishingExtension = extensions.create<MCAVPublishingExtension>(extensionName)
settings.gradleModuleMetadata.convention(true)

java {
    withSourcesJar()
    withJavadocJar()
}

tasks.withType<Javadoc>().configureEach {
    this.options.encoding = javadocEncoding
}

tasks.withType<GenerateModuleMetadata>().configureEach {
    this.enabled = settings.gradleModuleMetadata.get()
}

publishing {
    repositories {
        maven {
            name = endToEndRepositoryName
            url = rootProject.layout.buildDirectory.dir(endToEndRepositoryPath).get().asFile.toURI()
        }
        maven {
            name = snapshotRepositoryName
            url = uri(snapshotRepositoryUrl)
            credentials(PasswordCredentials::class)
            authentication {
                create<BasicAuthentication>("basic")
            }
        }
    }
}

afterEvaluate {
    publishing.publications.create<MavenPublication>(publicationName) {
        groupId = rootProject.group.toString()
        artifactId = project.name
        version = rootProject.version.toString()
        pom {
            name = project.name
            description = "MCAV multimedia library: ${project.name}"
            url = projectUrl
            licenses {
                license {
                    name = licenseName
                    url = licenseUrl
                    distribution = licenseDistribution
                }
            }
            scm {
                connection = scmConnection
                developerConnection = scmDeveloperConnection
                url = projectUrl
            }
        }

        if (settings.bundledJar.isPresent) {
            artifact(settings.bundledJar.get())
            artifact(tasks.named("sourcesJar").get())
            artifact(tasks.named("javadocJar").get())
        } else {
            from(components["java"])
        }

        if (!settings.gradleModuleMetadata.get()) {
            suppressAllPomMetadataWarnings()
        }
    }

    val generatedPom: TaskProvider<GenerateMavenPom> = tasks.named<GenerateMavenPom>("generatePomFileForMavenPublication")
    val publishedPom: String = "-D$publishedPomProperty=" + generatedPom.get().destination.absolutePath
    tasks.named<Test>("test") {
        this.dependsOn(generatedPom)
        this.jvmArgs(publishedPom)
    }

    pluginManager.withPlugin("info.solidsoft.pitest") {
        tasks.named("pitest") {
            this.dependsOn(generatedPom)
        }
    }
}
