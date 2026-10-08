plugins {
    id("mcav.java-library")
    id("mcav.formatting")
    id("mcav.lint")
    id("mcav.jcstress")
}

dependencies {
    // Exclude unused JavaCV platform natives: they would add over a gigabyte to the jcstress jar.
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
    implementation(project(":sandbox:plugin")) {
        isTransitive = false
    }
}
