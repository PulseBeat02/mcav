plugins {
    id("mcav.common")
}


dependencies {
    api(libs.vlcj) {
        exclude(group = "net.java.dev.jna", module = "jna-jpms")
        exclude(group = "net.java.dev.jna", module = "jna-platform-jpms")
    }
    api(libs.javacv.platform)
    api(libs.guava)
    api(libs.gson)
    api(libs.bundles.jna)
    api(libs.slf4j.api)
    compileOnly(libs.osgi.annotation)
    testImplementation(libs.slf4j.simple)
    testImplementation(libs.jimfs)
}
