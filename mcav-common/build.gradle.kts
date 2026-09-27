plugins {
    id("maven-publish")
}

dependencies {

    // project dependencies
    api(libs.vlcj)
    api(libs.javacv.platform) {
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
    api(libs.guava)
    api(libs.gson)
    api(libs.jna)
    api(libs.jna.platform)

    // logging: the library logs through the SLF4J API and leaves the binding to the application
    api(libs.slf4j.api)

    // JavaCPP declares these annotations as provided; without them javac cannot read its package-info
    compileOnly(libs.osgi.annotation)

    // test dependencies
    testImplementation(libs.slf4j.simple)
    testImplementation(libs.jimfs)
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
