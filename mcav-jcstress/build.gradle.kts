// Concurrency tests of mcav. A test harness, not product code: its code only ever runs inside jcstress, so it is not
// published, and neither the coverage lint nor PIT measures it.

plugins {
    id("mcav.java-library")
    id("mcav.formatting")
    id("mcav.lint")
    id("mcav.jcstress")
}

dependencies {
    // The code under test. The tests run Java code alone, so the native libraries of every platform, which the media
    // modules pull in through the JavaCV platform artifacts, stay out: they would put more than a gigabyte into the jar.
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
    // the map encoder and the screens of the sandbox need none of the Minecraft server the two modules compile against
    implementation(project(":mcav-bukkit")) {
        isTransitive = false
    }
    implementation(project(":sandbox:plugin")) {
        isTransitive = false
    }
}
