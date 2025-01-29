import xyz.jpenilla.runtask.task.AbstractRun

plugins {
    id("com.gradleup.shadow") version "8.3.6"
    id("xyz.jpenilla.run-paper") version "2.3.1"
    id("xyz.jpenilla.resource-factory-bukkit-convention") version "1.2.1"
}

dependencies {
    compileOnly("org.spigotmc:spigot-api:1.21.5-R0.1-SNAPSHOT")
    implementation("com.alessiodp.libby:libby-bukkit:2.0.0-SNAPSHOT")
    compileOnly("me.brandonli:mcav-minecraft:1.0.0-SNAPSHOT")
    compileOnly("org.incendo:cloud-core:2.0.0")
    compileOnly("org.incendo:cloud-annotations:2.0.0")
    compileOnly("org.incendo:cloud-paper:2.0.0-beta.10")
    compileOnly("org.incendo:cloud-minecraft-extras:2.0.0-beta.10")
    compileOnly("me.lucko:commodore:2.2")
    compileOnly("org.bstats:bstats-bukkit:3.1.0")
    compileOnly("dev.triumphteam:triumph-gui:3.1.12")
    compileOnly("net.kyori:adventure-api:4.20.0")
    compileOnly("net.kyori:adventure-platform-bukkit:4.3.4")
    compileOnly("net.kyori:adventure-text-minimessage:4.20.0")
    compileOnly("net.kyori:adventure-text-serializer-legacy:4.20.0")
    compileOnly("net.kyori:adventure-text-serializer-plain:4.20.0")
    compileOnly("io.github.classgraph:classgraph:4.8.179")
}

version = "1.0.0-v1.21.5"

val targetJavaVersion = 21
java {
    val javaVersion = JavaVersion.toVersion(targetJavaVersion)
    val language = JavaLanguageVersion.of(targetJavaVersion)
    sourceCompatibility = javaVersion
    targetCompatibility = javaVersion
    toolchain.languageVersion.set(language)
}

tasks.withType<AbstractRun>().configureEach {
    javaLauncher.set(javaToolchains.launcherFor {
        vendor = JvmVendorSpec.JETBRAINS
        languageVersion = JavaLanguageVersion.of(targetJavaVersion)
    })
    jvmArgs("-Xmx2040m", "-XX:+AllowEnhancedClassRedefinition", "-XX:+AllowRedefinitionToAddDeleteMethods")
}

tasks {

    register("writeDependenciesToFiles") {
        doLast {
            val dependenciesList = configurations.compileOnly.get().dependencies
                .filter { dep ->
                    !(dep.group == "org.spigotmc" && dep.name == "spigot-api") &&
                            !(dep.group == "com.alessiodp.libby" && dep.name == "libby-bukkit")
                }
                .map { "${it.group}:${it.name}:${it.version}" }
                .sorted()
                .joinToString("\n")
            file("dependencies.txt").writeText(dependenciesList)
            val allRepos = mutableListOf<String>()
            project.repositories.forEach { repo ->
                if (repo is MavenArtifactRepository) {
                    allRepos.add("${repo.url}")
                }
            }
            rootProject.repositories.forEach { repo ->
                if (repo is MavenArtifactRepository) {
                    allRepos.add("${repo.url}")
                }
            }
            file("repositories.txt").writeText(allRepos.sorted().joinToString("\n"))
        }
    }

    withType<JavaCompile>().configureEach {
        val set = setOf("-parameters", "-Xlint:deprecation", "-Xlint:unchecked")
        options.compilerArgs.addAll(set)
        options.encoding = "UTF-8"
        options.release.set(targetJavaVersion)
        options.isFork = true
        options.forkOptions.memoryMaximumSize = "4g"
    }

    bukkitPluginYaml {
        name = "MCAV"
        version = "${project.version}"
        description = "MCAV Sandbox Plugin"
        authors = listOf("PulseBeat_02")
        apiVersion = "1.21"
        prefix = "MCAV"
        main = "me.brandonli.mcav.sandbox.MCAV"
    }

    processResources {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
        filteringCharset = "UTF-8"
        dependsOn("writeDependenciesToFiles")
        from(file("dependencies.txt"))
        from(file("repositories.txt"))
    }

    shadowJar {
        archiveBaseName.set("mcav-sandbox")
        relocate("com.alessiodp.libby", "me.brandonli.mcav.sandbox.lib.libby")
    }

    assemble {
        dependsOn("shadowJar")
    }

    runServer {
        systemProperty("net.kyori.adventure.text.warnWhenLegacyFormattingDetected", false)
        minecraftVersion("1.21.5")
    }
}

