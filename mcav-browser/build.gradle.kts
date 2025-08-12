plugins {
    id("maven-publish")
}

dependencies {
    // project dependencies
    api("com.microsoft.playwright:playwright:1.54.0")
    api("org.seleniumhq.selenium:selenium-java:4.35.0")
    api("io.github.bonigarcia:webdrivermanager:6.2.0")
    api("org.slf4j:jul-to-slf4j:2.1.0-alpha1")

    // provided
    compileOnlyApi(project(":mcav-common"))

    // test dependencies
    testImplementation("org.seleniumhq.selenium:selenium-java:4.35.0")
    testImplementation("io.github.bonigarcia:webdrivermanager:6.2.0")
    testImplementation(project(":mcav-common"))
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