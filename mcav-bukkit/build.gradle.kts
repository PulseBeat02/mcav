plugins {
    id("maven-publish")
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.19"
}

dependencies {

    // project dependencies
    paperweight.paperDevBundle("1.21.11-R0.1-SNAPSHOT")
    api("team.unnamed:creative-api:1.7.3")
    api("team.unnamed:creative-serializer-minecraft:1.7.3")
    api("net.bytebuddy:byte-buddy:1.18.5")
    api("net.bytebuddy:byte-buddy-agent:1.18.5")
    api("net.openhft:zero-allocation-hashing:0.27ea1")

    // provided
    compileOnlyApi(project(":mcav-common"))
    compileOnlyApi("io.netty:netty-all:4.1.97.Final")
    compileOnlyApi("com.google.guava:guava:33.4.8-jre")
    compileOnlyApi("com.google.code.gson:gson:2.13.2")
    compileOnlyApi("net.java.dev.jna:jna:5.18.1")

    // testing
    testImplementation(project(":mcav-common"))
}

tasks {

    java {
        withSourcesJar()
        withJavadocJar()
    }

    assemble {
        dependsOn("reobfJar")
    }

    withType<Javadoc>().configureEach {
        options.encoding = "UTF-8"
    }

    withType<GenerateModuleMetadata>().configureEach {
        enabled = false
    }
}

publishing {
    repositories {
        maven {
            name = "brandonli"
            url = uri("https://repo.brandonli.me/snapshots")
            credentials(PasswordCredentials::class)
            authentication {
                create<BasicAuthentication>("basic")
            }
        }
    }
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            groupId = "me.brandonli"
            artifactId = project.name
            version = rootProject.version.toString()
            artifacts.removeIf { it.extension == "jar" && it.classifier == null }
            artifact(tasks.named("reobfJar")) {
                classifier = null
            }
            suppressAllPomMetadataWarnings()
        }
    }
}