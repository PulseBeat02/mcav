plugins {
    id("io.papermc.paperweight.userdev") version "1.7.7"
}

dependencies {
    compileOnly(project(":ezmediacore:nms-api"))
    paperweight.paperDevBundle("1.21.1-R0.1-SNAPSHOT")
}