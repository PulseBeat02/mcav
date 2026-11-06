import me.brandonli.mcav.gradle.ModuleCoordinates

plugins {
    id("mcav.common")
}

dependencies {
    api(libs.vlcj) {
        exclude(group = ModuleCoordinates.JNA_GROUP, module = ModuleCoordinates.JNA_JPMS_MODULE)
        exclude(group = ModuleCoordinates.JNA_GROUP, module = ModuleCoordinates.JNA_PLATFORM_JPMS_MODULE)
    }
    api(libs.javacv.platform)
    api(libs.guava)
    api(libs.gson)
    api(libs.bundles.jna)
    api(libs.slf4j.api)

    implementation(libs.xz)

    compileOnly(libs.osgi.annotation)

    testImplementation(libs.slf4j.simple)
    testImplementation(libs.jimfs)
}
