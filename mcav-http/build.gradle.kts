import me.brandonli.mcav.gradle.ModuleCoordinates

plugins {
    id("mcav.http")
}

dependencies {
    api(platform(libs.jackson.bom))
    api(libs.bundles.spring.boot.web) {
        exclude(group = ModuleCoordinates.SPRING_BOOT_GROUP, module = ModuleCoordinates.SPRING_BOOT_LOGGING_MODULE)
    }
    api(libs.tomcat.embed.core) {
        exclude(group = ModuleCoordinates.TOMCAT_GROUP, module = ModuleCoordinates.TOMCAT_ANNOTATIONS_MODULE)
    }
    api(libs.tomcat.embed.el)
    api(libs.tomcat.embed.websocket) {
        exclude(group = ModuleCoordinates.TOMCAT_GROUP, module = ModuleCoordinates.TOMCAT_ANNOTATIONS_MODULE)
    }
    api(libs.jackson.core)
    api(libs.jackson.databind)

    compileOnlyApi(project(":mcav-common"))

    testImplementation(project(":mcav-common"))
    testImplementation(libs.slf4j.simple)
}
