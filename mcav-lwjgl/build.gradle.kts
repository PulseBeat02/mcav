plugins {
    id("maven-publish")
}

// the tests render into a hidden GLFW window, which needs the natives of the machine that runs them; on a machine
// LWJGL has no natives for, none are added and the tests skip themselves
val lwjglNatives: String? = run {
    val osName = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val x64 = arch == "amd64" || arch == "x86_64"
    val arm64 = arch == "aarch64" || arch == "arm64"
    when {
        // "darwin" contains "win", so macOS is recognized first
        osName.contains("mac") || osName.contains("darwin") -> when {
            arm64 -> "natives-macos-arm64"
            x64 -> "natives-macos"
            else -> null
        }
        osName.contains("win") -> when {
            arm64 -> "natives-windows-arm64"
            x64 -> "natives-windows"
            arch == "x86" || arch == "i386" || arch == "i686" -> "natives-windows-x86"
            else -> null
        }
        osName.contains("freebsd") -> if (x64) "natives-freebsd" else null
        osName.contains("linux") -> when {
            x64 -> "natives-linux"
            arm64 -> "natives-linux-arm64"
            arch == "arm" || arch == "arm32" || arch.startsWith("armv7") -> "natives-linux-arm32"
            arch == "ppc64le" -> "natives-linux-ppc64le"
            arch == "riscv64" -> "natives-linux-riscv64"
            else -> null
        }
        else -> null
    }
}

dependencies {

    // project dependencies
    api("org.lwjgl:lwjgl:3.4.3")
    api("org.lwjgl:lwjgl-opengl:3.4.3")

    // provided
    compileOnlyApi(project(":mcav-common"))

    // test dependencies
    testImplementation(project(":mcav-common"))
    testImplementation("org.lwjgl:lwjgl-glfw:3.4.3")
    if (lwjglNatives != null) {
        testRuntimeOnly("org.lwjgl:lwjgl:3.4.3:$lwjglNatives")
        testRuntimeOnly("org.lwjgl:lwjgl-opengl:3.4.3:$lwjglNatives")
        testRuntimeOnly("org.lwjgl:lwjgl-glfw:3.4.3:$lwjglNatives")
    }
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
