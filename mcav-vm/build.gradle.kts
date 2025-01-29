plugins {
    id("maven-publish")
}

dependencies {
    // provided
    compileOnlyApi(project(":mcav-common"))
    compileOnlyApi(project(":mcav-vnc"))

    // test dependencies
    testImplementation(project(":mcav-common"))
    testImplementation(project(":mcav-vnc"))
}

tasks {
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