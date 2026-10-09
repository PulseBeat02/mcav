plugins {
    id("mcav.java-library")
    id("mcav.formatting")
    id("mcav.lint")
    id("mcav.jcstress")
}

dependencies {
    implementation(project(":mcav-common")) {
        exclude(group = "org.bytedeco")
    }
    implementation(libs.bundles.javacv.classes) {
        isTransitive = false
    }
    implementation(project(":mcav-http")) {
        exclude(group = "org.bytedeco")
    }
    implementation(project(":mcav-vm")) {
        exclude(group = "org.bytedeco")
    }
    // Keep Minecraft's runtime out of the standalone map/screen stress harness.
    implementation(project(":mcav-bukkit")) {
        isTransitive = false
    }
    implementation(project(":mcav-plugin")) {
        isTransitive = false
    }
}
