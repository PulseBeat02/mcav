plugins {
    id("java")
    id("java-library")
    id("com.github.node-gradle.node") version "7.1.0"
    id("com.diffplug.spotless") version "7.0.0.BETA4"
    id("org.checkerframework") version "0.6.61"
}

group = "me.brandonli"
version = "1.0.0-SNAPSHOT"
description = "mcav"

repositories {
    mavenCentral()
}

val windows = System.getProperty("os.name").lowercase().contains("windows")

fun getNodeExecutable(): File {
    val npmExec = if (windows) "node.exe" else "bin/node"
    val folder = node.resolvedNodeDir.get()
    val executable = folder.file(npmExec).asFile
    return executable
}

subprojects {

    apply(plugin = "java")
    apply(plugin = "java-library")
    apply(plugin = "org.checkerframework")
    apply(plugin = "com.github.node-gradle.node")
    apply(plugin = "com.diffplug.spotless")

    val targetJavaVersion = 21
    java {
        val javaVersion = JavaVersion.toVersion(targetJavaVersion)
        val language = JavaLanguageVersion.of(targetJavaVersion)
        sourceCompatibility = javaVersion
        targetCompatibility = javaVersion
        toolchain.languageVersion.set(language)
    }

    tasks {

        repositories {
            mavenCentral()
            google()
            maven("https://repo.brandonli.me/snapshots")
            maven("https://maven.maxhenkel.de/repository/public")
            maven("https://repo.papermc.io/repository/maven-public/")
            maven("https://oss.sonatype.org/content/repositories/snapshots")
            maven("https://s01.oss.sonatype.org/content/repositories/snapshots/")
            maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
            maven("https://repo.codemc.io/repository/maven-releases/")
            maven {
                url = uri("https://api.modrinth.com/maven")
                content {
                    includeGroup("maven.modrinth")
                }
            }
        }

        withType<Test> {
            failOnNoDiscoveredTests = false
        }

        withType<JavaCompile>().configureEach {
            val set = setOf("-parameters")
            options.release.set(targetJavaVersion)
            options.compilerArgs.addAll(set)
            options.encoding = "UTF-8"
            options.isFork = true
            options.forkOptions.memoryMaximumSize = "4g"
            options.forkOptions.jvmArgs = (options.forkOptions.jvmArgs ?: mutableListOf()).apply {
                // Checker Framework Gradle Plugin forgot to include this argument for latest Checker Framework versions
                // See https://github.com/typetools/checker-framework/issues/7241
                // See https://github.com/kelloggm/checkerframework-gradle-plugin/blob/fe79a94a8399d097cf3e2e3e2ab0626e46bfbd4f/src/main/groovy/org/checkerframework/gradle/plugin/CheckerFrameworkPlugin.groovy#L384
                add("--add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED")
            }
        }

        processResources {
            duplicatesStrategy = DuplicatesStrategy.INCLUDE
            filteringCharset = "UTF-8"
        }

        build {
            dependsOn("npmInstall")
            dependsOn("spotlessApply")
        }

        configurations.all {
            resolutionStrategy.cacheChangingModulesFor(0, "seconds")
        }

        spotless {
            java {
                prettier(mapOf("prettier" to "3.3.3", "prettier-plugin-java" to "2.6.4"))
                    .config(
                        mapOf(
                            "parser" to "java",
                            "tabWidth" to 2,
                            "plugins" to listOf("prettier-plugin-java"),
                            "printWidth" to 140
                        )
                    )
                    .nodeExecutable(provider { getNodeExecutable() })
                val file = rootProject.file("HEADER")
                licenseHeaderFile(file)
                importOrder()
                removeUnusedImports()
                formatAnnotations()
            }
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
                "-AsuppressWarnings=uninitialized,type.anno.before.modifier",
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