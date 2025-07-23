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

val windows = System.getProperty("os.name").lowercase().contains("windows")

fun getNpmExecutable(): File {
    val npmExec = if (windows) "npm.cmd" else "bin/npm"
    val folder = node.resolvedNodeDir.get()
    val executable = folder.file(npmExec).asFile
    return executable
}

tasks {

    java {
        withSourcesJar()
        withJavadocJar()
    }

    withType<Javadoc>().configureEach {
        options.encoding = "UTF-8"
    }

    val npmProjectInstall by registering(Exec::class) {
        group = "build"
        description = "Install npm dependencies for the website"
        workingDir = file("mcav-website")
        executable = getNpmExecutable().absolutePath
        setArgs(listOf("install"))
        inputs.file("mcav-website/package.json")
        inputs.file("mcav-website/package-lock.json")
        outputs.dir("mcav-website/node_modules")
    }

    val buildWebsite by registering(Exec::class) {
        group = "build"
        description = "Build the Next.js website"
        dependsOn(npmProjectInstall)
        workingDir = file("mcav-website")
        executable = getNpmExecutable().absolutePath
        setArgs(listOf("run", "build"))
        inputs.dir("mcav-website/src")
        inputs.dir("mcav-website/public")
        inputs.file("mcav-website/package.json")
        inputs.file("mcav-website/next.config.ts")
        outputs.dir("mcav-website/out")
        outputs.cacheIf { false }
        environment("NODE_OPTIONS", "--max-old-space-size=4096")
    }

    jar {
        dependsOn(buildWebsite)
        from("mcav-website/out") {
            into("static")
        }
    }

    named<Jar>("sourcesJar") {
        dependsOn(buildWebsite)
        from("mcav-website/out") {
            into("static")
        }
    }

    build {
        dependsOn(buildWebsite)
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