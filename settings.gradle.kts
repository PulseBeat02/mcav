plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

rootProject.name = "mcav"

include("mcav-common")
project(":mcav-common").name = "mcav-common"

include("mcav-bukkit")
project(":mcav-bukkit").name = "mcav-bukkit"

include("mcav-installer")
project(":mcav-installer").name = "mcav-installer"

include("mcav-jda")
project(":mcav-jda").name = "mcav-jda"

include("sandbox")
include("mcav-http")