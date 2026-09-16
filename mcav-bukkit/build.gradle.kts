plugins {
    id("maven-publish")
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.23"
}

dependencies {

    // project dependencies
    paperweight.paperDevBundle("26.2.build.+")

    // provided
    compileOnlyApi(project(":mcav-common"))
    compileOnlyApi("io.netty:netty-all:4.2.15.Final")
    compileOnlyApi("com.google.guava:guava:33.4.8-jre")
    compileOnlyApi("com.google.code.gson:gson:2.14.0")

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

    withType<GenerateModuleMetadata>().configureEach {
        enabled = false
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
            from(components["java"])
            groupId = "me.brandonli"
            artifactId = project.name
            version = rootProject.version.toString()
            suppressAllPomMetadataWarnings()
        }
    }
}
