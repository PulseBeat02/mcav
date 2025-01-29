plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
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

include("mcav-http")
project(":mcav-http").name = "mcav-http"

include("mcav-browser")
project(":mcav-browser").name = "mcav-browser"

include("mcav-vnc")
project(":mcav-vnc").name = "mcav-vnc"

include("mcav-vm")
project(":mcav-vm").name = "mcav-vm"

include("sandbox")