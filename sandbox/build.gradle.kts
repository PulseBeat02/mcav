import xyz.jpenilla.runtask.task.AbstractRun

plugins {
    id("com.gradleup.shadow") version "8.3.8"
    id("xyz.jpenilla.run-paper") version "2.3.1"
    id("xyz.jpenilla.resource-factory-paper-convention") version "1.3.0"
    id("xyz.jpenilla.gremlin-gradle") version "0.0.8"
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.7-R0.1-SNAPSHOT")
    implementation("xyz.jpenilla:gremlin-runtime:0.0.8")

    runtimeDownload("me.brandonli:mcav-bukkit:1.0.0-SNAPSHOT")
    runtimeDownload("me.brandonli:mcav-jda:1.0.0-SNAPSHOT")
    runtimeDownload("me.brandonli:mcav-http:1.0.0-SNAPSHOT")
    runtimeDownload("me.brandonli:mcav-common:1.0.0-SNAPSHOT")
    runtimeDownload("me.brandonli:mcav-vm:1.0.0-SNAPSHOT")
    runtimeDownload("me.brandonli:mcav-vnc:1.0.0-SNAPSHOT")
    runtimeDownload("me.brandonli:mcav-browser:1.0.0-SNAPSHOT")

    runtimeDownload("org.incendo:cloud-core:2.0.0")
    runtimeDownload("org.incendo:cloud-annotations:2.0.0")
    runtimeDownload("org.incendo:cloud-paper:2.0.0-beta.11")
    runtimeDownload("org.incendo:cloud-minecraft-extras:2.0.0-beta.10")

    runtimeDownload("me.lucko:commodore:2.2")
    runtimeDownload("org.bstats:bstats-bukkit:3.1.0")
    runtimeDownload("net.dv8tion:JDA:5.6.1")
    runtimeDownload("io.javalin:javalin:6.7.0")
}

configurations.compileOnly {
    extendsFrom(configurations.runtimeDownload.get())
}

version = "1.0.0-v1.21.6"

tasks.withType<AbstractRun>().configureEach {
    javaLauncher.set(javaToolchains.launcherFor {
        vendor = JvmVendorSpec.JETBRAINS
        languageVersion = JavaLanguageVersion.of(21)
    })
    jvmArgs(
        "-Xms8192m",
        "-Xmx8192m",
        "-XX:+AllowEnhancedClassRedefinition",
        "-XX:+AllowRedefinitionToAddDeleteMethods"
    )
}

paperPluginYaml {
    name = "MCAV"
    version = "${project.version}"
    description = "MCAV Sandbox Plugin"
    authors = listOf("PulseBeat_02")
    apiVersion = "1.21"
    prefix = "MCAV Sandbox"
    loader = "me.brandonli.mcav.sandbox.MCAVLoader"
    main = "me.brandonli.mcav.sandbox.MCAVSandbox"
}

tasks {

    shadowJar {
        archiveBaseName.set("mcav-sandbox")
    }

    assemble {
        dependsOn("shadowJar")
    }

    runServer {
        systemProperty("net.kyori.adventure.text.warnWhenLegacyFormattingDetected", false)
        minecraftVersion("1.21.7")
    }
}