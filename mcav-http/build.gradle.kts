plugins {
    id("maven-publish")
}

dependencies {

    // project dependencies
    api("org.springframework.boot:spring-boot-starter-web:4.1.1") {
        exclude(group = "org.springframework.boot", module = "spring-boot-starter-logging")
    }

    api("org.springframework.boot:spring-boot-starter-websocket:4.1.1") {
        exclude(group = "org.springframework.boot", module = "spring-boot-starter-logging")
    }

    // provided
    compileOnlyApi(project(":mcav-common"))

    // testing
    testImplementation(project(":mcav-common"))
    testImplementation("org.slf4j:slf4j-simple:2.0.17")
}

val windows = System.getProperty("os.name").lowercase().contains("windows")

fun getNpmExecutable(): File {
    val npmExec = if (windows) "npm.cmd" else "bin/npm"
    val folder = node.resolvedNodeDir.get()
    val executable = folder.file(npmExec).asFile
    return executable
}

// npm uses an env-node shebang on Unix; put the managed Node beside npm on PATH.
val npmDirectory = getNpmExecutable().parentFile
val nodePath = npmDirectory.absolutePath + File.pathSeparator + System.getenv("PATH")

tasks {

    java {
        withSourcesJar()
        withJavadocJar()
    }

    withType<Javadoc>().configureEach {
        options.encoding = "UTF-8"
    }

    val npmProjectInstall = register<Exec>("npmProjectInstall") {
        group = "build"
        description = "Install npm dependencies for the website"
        dependsOn("nodeSetup")
        workingDir = file("mcav-website")
        executable = getNpmExecutable().absolutePath
        environment("PATH", nodePath)
        // installs exactly what package-lock.json lists, so every machine builds the same website
        setArgs(listOf("ci"))
        inputs.file("mcav-website/package.json")
        inputs.file("mcav-website/package-lock.json")
        // npm's installation receipt detects clean installs without hashing tens of thousands of dependency files.
        // Use --rerun-tasks to repair a dependency directory modified outside npm.
        outputs.file("mcav-website/node_modules/.package-lock.json")
    }

    val buildWebsite = register<Exec>("buildWebsite") {
        group = "build"
        description = "Build the Next.js website"
        dependsOn(npmProjectInstall)
        workingDir = file("mcav-website")
        executable = getNpmExecutable().absolutePath
        environment("PATH", nodePath)
        setArgs(listOf("run", "build"))
        inputs.dir("mcav-website/src")
        inputs.dir("mcav-website/public")
        inputs.file("mcav-website/package.json")
        inputs.file("mcav-website/next.config.ts")
        inputs.file("mcav-website/package-lock.json")
        inputs.file("mcav-website/tsconfig.json")
        inputs.file("mcav-website/postcss.config.mjs")
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
