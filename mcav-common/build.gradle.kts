plugins {
    id("maven-publish")
}

dependencies {

    // project dependencies
    api("uk.co.caprica:vlcj:4.11.0")
    api("org.bytedeco:javacv-platform:1.5.11") {
        exclude(group = "org.bytedeco", module = "flycapture")
        exclude(group = "org.bytedeco", module = "flycapture-platform")
        exclude(group = "org.bytedeco", module = "libdc1394")
        exclude(group = "org.bytedeco", module = "libdc1394-platform")
        exclude(group = "org.bytedeco", module = "libfreenect")
        exclude(group = "org.bytedeco", module = "libfreenect-platform")
        exclude(group = "org.bytedeco", module = "libfreenect2")
        exclude(group = "org.bytedeco", module = "libfreenect2-platform")
        exclude(group = "org.bytedeco", module = "librealsense")
        exclude(group = "org.bytedeco", module = "librealsense-platform")
        exclude(group = "org.bytedeco", module = "videoinput")
        exclude(group = "org.bytedeco", module = "videoinput-platform")
        exclude(group = "org.bytedeco", module = "artoolkitplus")
        exclude(group = "org.bytedeco", module = "artoolkitplus-platform")
        exclude(group = "org.bytedeco", module = "flandmark")
        exclude(group = "org.bytedeco", module = "flandmark-platform")
        exclude(group = "org.bytedeco", module = "leptonica")
        exclude(group = "org.bytedeco", module = "leptonica-platform")
        exclude(group = "org.bytedeco", module = "tesseract")
        exclude(group = "org.bytedeco", module = "tesseract-platform")
    }
    api("com.google.guava:guava:33.4.8-jre")
    api("com.google.code.gson:gson:2.13.1")

    // compile only
    compileOnlyApi("org.slf4j:slf4j-simple:2.1.0-alpha1")

    // test dependencies
    testImplementation("org.slf4j:slf4j-simple:2.1.0-alpha1")
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
