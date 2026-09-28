plugins {
    id("mcav.module")
    id("mcav.publishing")
}

// the platform artifacts JavaCV lists for camera SDKs, augmented reality, face landmarks and text recognition, which
// mcav never loads
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
    api(libs.vlcj)
    api(libs.javacv.platform) {
        unusedJavacvPresets.forEach { preset ->
            exclude(group = "org.bytedeco", module = preset)
            exclude(group = "org.bytedeco", module = "$preset-platform")
        }
    }
    api(libs.guava)
    api(libs.gson)
    api(libs.bundles.jna)
    // the library logs through the SLF4J API and leaves the binding to the application
    api(libs.slf4j.api)
    // JavaCPP declares these annotations as provided; without them javac cannot read its package-info
    compileOnly(libs.osgi.annotation)
    testImplementation(libs.slf4j.simple)
    testImplementation(libs.jimfs)
}
