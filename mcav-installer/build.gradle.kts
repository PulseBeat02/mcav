plugins {
    id("maven-publish")
}

dependencies {
    implementation("org.apache.maven.resolver:maven-resolver-impl:2.0.8")
    implementation("org.apache.maven.resolver:maven-resolver-supplier:2.0.0-alpha-8")
    implementation("org.slf4j:slf4j-nop:2.1.0-alpha1")
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