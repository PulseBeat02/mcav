plugins {
    id("maven-publish")
}

dependencies {

    // project dependencies
    compileOnlyApi("de.maxhenkel.voicechat:voicechat-api:2.5.36")

    // provided
    compileOnlyApi(project(":mcav-common"))

    // test dependencies
    testImplementation(project(":mcav-common"))
    testImplementation("de.maxhenkel.voicechat:voicechat-api:2.5.36")
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

