import me.brandonli.mcav.gradle.ModuleCoordinates

plugins {
    id("mcav.jcstress")
}

dependencies {
    implementation(project(":mcav-common")) {
        exclude(group = ModuleCoordinates.BYTEDECO_GROUP)
    }
    implementation(libs.bundles.javacv.classes) {
        isTransitive = false
    }
    implementation(project(":mcav-http")) {
        exclude(group = ModuleCoordinates.BYTEDECO_GROUP)
    }
    implementation(project(":mcav-vm")) {
        exclude(group = ModuleCoordinates.BYTEDECO_GROUP)
    }
    implementation(project(":mcav-bukkit")) {
        isTransitive = false
    }
    implementation(project(":mcav-plugin")) {
        isTransitive = false
    }
}
