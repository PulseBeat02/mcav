plugins {
    id("maven-publish")
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.19"
}

dependencies {

    // project dependencies
    paperweight.paperDevBundle("1.21.8-R0.1-SNAPSHOT")
    api("team.unnamed:creative-api:1.7.3")
    api("team.unnamed:creative-serializer-minecraft:1.7.3")
    api("net.bytebuddy:byte-buddy:1.17.8")
    api("net.bytebuddy:byte-buddy-agent:1.17.7")

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
            artifact(tasks.named("reobfJar"))
            artifact(tasks.named("sourcesJar")) {
                classifier = "sources"
                builtBy(tasks.named("reobfJar"))
            }
            artifact(tasks.named("javadocJar")) {
                classifier = "javadoc"
                builtBy(tasks.named("reobfJar"))
            }
        }
    }
}