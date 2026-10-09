pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "mcav"

include(
    "mcav-common",
    "mcav-docs",
    "mcav-bukkit",
    "mcav-installer",
    "mcav-discord",
    "mcav-http",
    "mcav-browser",
    "mcav-vnc",
    "mcav-vm",
    "mcav-lwjgl",
    "mcav-voicechat",
    "mcav-mod",
    "mcav-jcstress",
    "mcav-plugin",
)
