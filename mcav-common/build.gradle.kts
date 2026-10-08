plugins {
    id("mcav.module")
    id("mcav.publishing")
}

val unusedJavacvPresets = listOf(
    "flycapture",
    "libdc1394",
    "libfreenect",
    "libfreenect2",
    "librealsense",
    "videoinput",
    "artoolkitplus",
    "flandmark",
    "leptonica",
    "tesseract"
)

dependencies {
    api(libs.vlcj) {
        exclude(group = "net.java.dev.jna", module = "jna-jpms")
        exclude(group = "net.java.dev.jna", module = "jna-platform-jpms")
    }
    api(libs.javacv.platform) {
        unusedJavacvPresets.forEach { preset ->
            exclude(group = "org.bytedeco", module = preset)
            exclude(group = "org.bytedeco", module = "$preset-platform")
        }
    }
    api(libs.guava)
    api(libs.gson)
    api(libs.bundles.jna)
    api(libs.slf4j.api)
    compileOnly(libs.osgi.annotation)
    testImplementation(libs.slf4j.simple)
    testImplementation(libs.jimfs)
}
