// Publishes a module as me.brandonli:<module>, with its sources and Javadoc, to the snapshot repository of mcav and to
// the folder the end-to-end test of the sandbox plugin serves to its server. The snapshot repository reads its
// credentials from the Gradle properties brandonliUsername and brandonliPassword. `mcavPublishing` changes what a module
// publishes.

import me.brandonli.mcav.gradle.McavPublishingExtension

plugins {
    java
    `maven-publish`
}

val settings = extensions.create<McavPublishingExtension>("mcavPublishing")
settings.gradleModuleMetadata.convention(true)

java {
    withSourcesJar()
    withJavadocJar()
}

tasks.withType<Javadoc>().configureEach {
    options.encoding = "UTF-8"
}

// every published jar names the third-party code mcav's jars bundle and what mcav downloads while it runs
val thirdPartyNotices = rootProject.file("THIRD-PARTY-NOTICES.md")
tasks.withType<Jar>().matching { it.name == "jar" || it.name == "shadowJar" }.configureEach {
    from(thirdPartyNotices) {
        into("META-INF")
    }
}

tasks.withType<GenerateModuleMetadata>().configureEach {
    enabled = settings.gradleModuleMetadata.get()
}

publishing {
    repositories {
        maven {
            name = "endToEnd"
            url = rootProject.layout.buildDirectory.dir("e2e-repository").get().asFile.toURI()
        }
        maven {
            name = "brandonli"
            url = uri("https://repo.brandonli.me/snapshots")
            credentials(PasswordCredentials::class)
            authentication {
                create<BasicAuthentication>("basic")
            }
        }
    }
}

// what is published depends on the module's settings, so the publication is made once its build script has run
afterEvaluate {
    publishing.publications.create<MavenPublication>("maven") {
        groupId = "me.brandonli"
        artifactId = project.name
        version = rootProject.version.toString()
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
}
