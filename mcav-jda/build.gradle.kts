plugins {
    id("maven-publish")
}

dependencies {
    // project dependencies
    api("net.dv8tion:JDA:5.6.1")

    // provided
    compileOnlyApi(project(":mcav-common"))

    // testing
    testImplementation("net.dv8tion:JDA:5.6.1")
    testImplementation(project(":mcav-common"))
    testImplementation("net.java.dev.jna:jna:5.18.0")
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