plugins {
    id("java")
    id("java-library")
    id("com.github.node-gradle.node") version "7.1.0"
    id("com.diffplug.spotless") version "7.0.0.BETA4"
    id("org.checkerframework") version "0.6.53"
    id("maven-publish")
}

group = "me.brandonli"
version = "1.0.0-SNAPSHOT"
description = "mcav"

repositories {
    mavenCentral()
}

subprojects {

    apply(plugin = "java")
    apply(plugin = "java-library")
    apply(plugin = "org.checkerframework")
    apply(plugin = "com.github.node-gradle.node")
    apply(plugin = "com.diffplug.spotless")
    apply(plugin = "maven-publish")

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

    java {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        toolchain.languageVersion.set(JavaLanguageVersion.of(11))
    }

    tasks {

        repositories {
            mavenCentral()
            google()
            maven("https://repo.brandonli.me/snapshots")
            maven("https://repo.papermc.io/repository/maven-public/")
            maven("https://oss.sonatype.org/content/repositories/snapshots")
            maven("https://s01.oss.sonatype.org/content/repositories/snapshots/")
            maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
            maven("https://repo.codemc.io/repository/maven-releases/")
        }

        withType<JavaCompile>().configureEach {
            val set = setOf("-parameters", "-Xlint:deprecation", "-Xlint:unchecked")
            options.compilerArgs.addAll(set)
            options.encoding = "UTF-8"
            options.isFork = true
            options.forkOptions.memoryMaximumSize = "4g"
        }

        processResources {
            duplicatesStrategy = DuplicatesStrategy.INCLUDE
            filteringCharset = "UTF-8"
        }

        build {
            dependsOn("spotlessApply")
        }

        spotless {
            java {
                importOrder()
                removeUnusedImports()
                prettier(mapOf("prettier" to "3.3.3", "prettier-plugin-java" to "2.6.4"))
                    .config(
                        mapOf(
                            "parser" to "java",
                            "tabWidth" to 2,
                            "plugins" to listOf("prettier-plugin-java"),
                            "printWidth" to 140
                        )
                    )
                    .nodeExecutable(provider { setupNodeEnvironment() })
                val file = rootProject.file("HEADER")
                licenseHeaderFile(file)
            }
        }

        afterEvaluate {
            tasks.findByName("spotlessInternalRegisterDependencies")?.dependsOn("nodeSetup", "npmSetup")
        }

        checkerFramework {
            checkers = listOf("org.checkerframework.checker.nullness.NullnessChecker")
            val file = project.file("checker-framework")
            if (!file.exists()) {
                file.mkdirs()
            }
            val rootFile = rootProject.file("checker-framework")
            if (!rootFile.exists()) {
                rootFile.mkdirs()
            }
            extraJavacArgs = listOf(
                "-AsuppressWarnings=uninitialized",
                "-Astubs=${file}",
                "-Astubs=${rootFile}"
            )
        }

        node {
            download = true
            version = "22.12.0"
            workDir = file("build/nodejs")
        }
    }
}

val windows = System.getProperty("os.name").lowercase().contains("windows")

fun setupNodeEnvironment(): File {
    val npmExec = if (windows) "node.exe" else "bin/node"
    val folder = node.resolvedNodeDir.get()
    val executable = folder.file(npmExec).asFile
    return executable
}