plugins {
    id("maven-publish")
}

dependencies {
    // project dependencies
    api("io.javalin:javalin:6.6.0")

    // provided
    compileOnlyApi(project(":mcav-common"))

    // testing
    testImplementation("io.javalin:javalin:6.7.0")
    testImplementation(project(":mcav-common"))
    testImplementation("org.slf4j:slf4j-simple:2.1.0-alpha1")
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