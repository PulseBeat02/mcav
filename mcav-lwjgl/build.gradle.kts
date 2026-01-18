plugins {
    id("maven-publish")
}

dependencies {

    // project dependencies
    api("org.lwjgl:lwjgl:3.3.6")
    api("org.lwjgl:lwjgl-opengl:3.4.0")

    // provided
    compileOnlyApi(project(":mcav-common"))
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

