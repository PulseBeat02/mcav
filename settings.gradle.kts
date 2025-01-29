plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

rootProject.name = "mcav"

include("mcav-common")
project(":mcav-common").name = "mcav-common"

include("mcav-minecraft")
project(":mcav-minecraft").name = "mcav-minecraft"

include("mcav-installer")
project(":mcav-installer").name = "mcav-installer"

include("sandbox")
