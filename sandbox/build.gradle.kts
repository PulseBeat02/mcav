import xyz.jpenilla.runtask.task.AbstractRun

plugins {
    id("com.gradleup.shadow") version "8.3.6"
    id("xyz.jpenilla.run-paper") version "2.3.1"
    id("xyz.jpenilla.resource-factory-bukkit-convention") version "1.2.1"
}

dependencies {

    // provided api
    compileOnly("org.spigotmc:spigot-api:1.21.5-R0.1-SNAPSHOT")

    // mcav
    implementation("me.brandonli:mcav-installer:1.0.0-SNAPSHOT") { isChanging = true }
    compileOnly("me.brandonli:mcav-minecraft:1.0.0-SNAPSHOT") { isChanging = true }

    // plugin dependencies
    compileOnly("org.incendo:cloud-core:2.0.0")
    compileOnly("org.incendo:cloud-annotations:2.0.0")
    compileOnly("org.incendo:cloud-paper:2.0.0-beta.10")
    compileOnly("org.incendo:cloud-minecraft-extras:2.0.0-beta.10")
    compileOnly("me.lucko:commodore:2.2")
    compileOnly("org.bstats:bstats-bukkit:3.1.0")
    compileOnly("dev.triumphteam:triumph-gui:3.1.12")
    compileOnly("net.kyori:adventure-api:4.21.0")
    compileOnly("net.kyori:adventure-platform-bukkit:4.3.4")
    compileOnly("net.kyori:adventure-text-minimessage:4.21.0")
    compileOnly("net.kyori:adventure-text-serializer-legacy:4.21.0")
    compileOnly("net.kyori:adventure-text-serializer-plain:4.21.0")
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
        prefix = "MCAV Sandbox"
        main = "me.brandonli.mcav.sandbox.MCAV"
        libraries = listOf(
            "org.incendo:cloud-core:2.0.0",
            "org.incendo:cloud-annotations:2.0.0",
            "org.incendo:cloud-paper:2.0.0-beta.10",
            "org.incendo:cloud-minecraft-extras:2.0.0-beta.10",
            "me.lucko:commodore:2.2",
            "org.bstats:bstats-bukkit:3.1.0",
            "dev.triumphteam:triumph-gui:3.1.12",
            "net.kyori:adventure-api:4.21.0",
            "net.kyori:adventure-platform-bukkit:4.3.4",
            "net.kyori:adventure-text-minimessage:4.21.0",
            "net.kyori:adventure-text-serializer-legacy:4.21.0",
            "net.kyori:adventure-text-serializer-plain:4.21.0"
        )
    }

    processResources {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
        filteringCharset = "UTF-8"
    }

    shadowJar {
        archiveBaseName.set("mcav-sandbox")
    }

    assemble {
        dependsOn("shadowJar")
    }

    runServer {
        systemProperty("net.kyori.adventure.text.warnWhenLegacyFormattingDetected", false)
        minecraftVersion("1.21.5")
    }
}

