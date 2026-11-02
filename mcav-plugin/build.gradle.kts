import me.brandonli.mcav.gradle.ModuleCoordinates

plugins {
    id("mcav.module")
    id("mcav.paper-plugin")
    id("mcav.browser-benchmark")
}

dependencies {
    implementation(libs.gremlin.runtime)
    implementation(ModuleCoordinates.coordinateOf(project, "mcav-voicechat"))

    compileOnly(libs.paper.api)

    runtimeDownload(ModuleCoordinates.coordinateOf(project, "mcav-bukkit"))
    runtimeDownload(ModuleCoordinates.coordinateOf(project, "mcav-discord"))
    runtimeDownload(ModuleCoordinates.coordinateOf(project, "mcav-http"))
    runtimeDownload(ModuleCoordinates.coordinateOf(project, "mcav-common"))
    runtimeDownload(ModuleCoordinates.coordinateOf(project, "mcav-vm"))
    runtimeDownload(ModuleCoordinates.coordinateOf(project, "mcav-vnc"))
    runtimeDownload(ModuleCoordinates.coordinateOf(project, "mcav-browser"))
    runtimeDownload(libs.bundles.cloud)
    runtimeDownload(libs.commodore)
    runtimeDownload(libs.bstats.bukkit)
    runtimeDownload(libs.jda)

    testImplementation(libs.voicechat.api)
    testImplementation(libs.slf4j.simple)
}
