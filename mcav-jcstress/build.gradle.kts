plugins {
    id("mcav.jcstress")
}

val bytedecoGroup: String = "org.bytedeco"

dependencies {
    implementation(project(":mcav-common")) {
        exclude(group = bytedecoGroup)
    }
    implementation(libs.bundles.javacv.classes) {
        isTransitive = false
    }
    implementation(project(":mcav-http")) {
        exclude(group = bytedecoGroup)
    }
    implementation(project(":mcav-vm")) {
        exclude(group = bytedecoGroup)
    }
    implementation(project(":mcav-bukkit")) {
        isTransitive = false
    }
    implementation(project(":mcav-plugin")) {
        isTransitive = false
    }
}
