plugins {
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
    implementation(project(":mcav-bukkit")) {
        isTransitive = false
    }
    implementation(project(":mcav-plugin")) {
        isTransitive = false
    }
}
