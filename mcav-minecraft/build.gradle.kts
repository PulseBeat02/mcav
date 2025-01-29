plugins {
    id("maven-publish")
}

dependencies {
    api(project(":mcav-common"))
    api("com.github.retrooper:packetevents-spigot:2.7.0")
    api("team.unnamed:creative-api:1.7.3")
    api("team.unnamed:creative-serializer-minecraft:1.7.3")
    compileOnlyApi("io.netty:netty-all:4.1.97.Final")
    compileOnlyApi("com.google.guava:guava:33.4.8-jre")
    compileOnlyApi("com.google.code.gson:gson:2.13.1")
    compileOnlyApi("net.java.dev.jna:jna:5.17.0")
}

tasks {

    register<Copy>("copyCppOutput") {
        from("${projectDir}/../cpp-src/output")
        into(layout.buildDirectory.dir("resources/main"))
        includeEmptyDirs = false
    }

    processResources {
        dependsOn("copyCppOutput")
    }

    java {
        withSourcesJar()
        withJavadocJar()
    }

    withType<Javadoc>().configureEach {
        options.encoding = "UTF-8"
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
            groupId = "me.brandonli"
            artifactId = project.name
            version = "${rootProject.version}"
            from(components["java"])
        }
    }
}