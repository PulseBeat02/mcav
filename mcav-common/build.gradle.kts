plugins {
    id("maven-publish")
}

dependencies {
    api("org.pf4j:pf4j:3.13.0")

    api("uk.co.caprica:vlcj:4.11.0")
    api("org.bytedeco:javacv-platform:1.5.11")

    api("com.google.guava:guava:33.4.8-jre")
    api("com.google.code.gson:gson:2.13.1")
}

tasks {

    register<Copy>("copyCppOutput") {
        from("${projectDir}/../cpp-src/output")
        into(layout.buildDirectory.dir("resources/main"))
        includeEmptyDirs = false
    }

    register<Copy>("copyDummyOutput") {
        from("${projectDir}/../dummy/output")
        into(layout.buildDirectory.dir("resources/main"))
        includeEmptyDirs = false
    }

    processResources {
        dependsOn("copyCppOutput")
        dependsOn("copyDummyOutput")
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
