plugins {
    id("com.gradleup.shadow") version "8.3.6"
    id("xyz.jpenilla.run-paper") version "2.3.1"
    id("net.minecrell.plugin-yml.bukkit") version "0.6.0"
}

var runtimeDeps = listOf(
    "net.kyori:adventure-api:4.17.0",
    "net.kyori:adventure-platform-bukkit:4.3.4",
    "net.kyori:adventure-text-minimessage:4.17.0",
    "org.incendo:cloud-annotations:2.0.0",
    "org.incendo:cloud-paper:2.0.0-beta.10",
    "org.incendo:cloud-minecraft-extras:2.0.0-beta.10",
    "me.lucko:commodore:2.2",
    "com.github.stefvanschie.inventoryframework:IF:0.10.17"
);

dependencies {

    // Project modules
    implementation(project(":ezmediacore:main"))

    // Provided dependencies
    compileOnly("org.spigotmc:spigot-api:1.21.1-R0.1-SNAPSHOT")
    compileOnly("com.mojang.authlib:authlib:1.5.26")
    compileOnly("net.dv8tion:JDA:5.1.2")

    // Project dependencies
    implementation("org.bstats:bstats-bukkit:3.1.0")
    runtimeDeps.forEach(::compileOnly)
}

tasks {

    build {
        dependsOn(shadowJar)
    }

    bukkit {
        name = "DeluxeMediaPlugin"
        version = "1.21.1-v1.0.0"
        description = "Pulse's DeluxeMediaPlugin Plugin"
        authors = listOf("PulseBeat_02")
        apiVersion = "1.21"
        prefix = "DeluxeMediaPlugin"
        main = "io.github.pulsebeat02.deluxemediaplugin.DeluxeMediaPluginBootstrap"
        libraries = runtimeDeps
    }

    build {
        dependsOn("spotlessApply")
    }

    processResources {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
        filteringCharset = "UTF-8"
    }

    runServer {
        minecraftVersion("1.21.1")
    }
}