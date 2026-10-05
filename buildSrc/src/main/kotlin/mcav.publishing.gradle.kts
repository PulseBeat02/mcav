// Publishes a module as me.brandonli:<module>, with its sources and Javadoc, to the snapshot repository of mcav and to
// the folder the end-to-end test of the sandbox plugin serves to its server. The snapshot repository reads its
// credentials from the Gradle properties brandonliUsername and brandonliPassword. `mcavPublishing` changes what a module
// publishes.

import info.solidsoft.gradle.pitest.PitestPluginExtension
import me.brandonli.mcav.gradle.McavPublishingExtension

plugins {
    java
    `maven-publish`
    id("mcav.licensing")
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
        pom {
            name.set(project.name)
            description.set("MCAV multimedia library: ${project.name}")
            url.set("https://github.com/PulseBeat02/mcav")
            licenses {
                license {
                    name.set("GNU General Public License, version 3 or later")
                    url.set("https://www.gnu.org/licenses/gpl-3.0.html")
                    distribution.set("repo")
                }
            }
            scm {
                connection.set("scm:git:https://github.com/PulseBeat02/mcav.git")
                developerConnection.set("scm:git:ssh://git@github.com/PulseBeat02/mcav.git")
                url.set("https://github.com/PulseBeat02/mcav")
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
    // Gradle stores -D options as Test.systemProperties, so PIT needs its own copy for the forked test JVMs.
    val generatedPom = tasks.named<GenerateMavenPom>("generatePomFileForMavenPublication")
    val publishedPom = "-Dmcav.published.pom=" + generatedPom.get().destination.absolutePath
    tasks.named<Test>("test") {
        dependsOn(generatedPom)
        jvmArgs(publishedPom)
    }
    pluginManager.withPlugin("info.solidsoft.pitest") {
        extensions.configure<PitestPluginExtension> {
            jvmArgs.add(publishedPom)
        }
        tasks.named("pitest") {
            dependsOn(generatedPom)
        }
    }
}
