plugins {
    id("maven-publish")
}

dependencies {

    // project dependencies
    api("org.springframework.boot:spring-boot-starter-web:3.5.3") {
        exclude(group = "org.springframework.boot", module = "spring-boot-starter-logging")
    }
    api("org.springframework.boot:spring-boot-starter-websocket:3.5.3") {
        exclude(group = "org.springframework.boot", module = "spring-boot-starter-logging")
    }

    // provided
    compileOnlyApi(project(":mcav-common"))

    // testing
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