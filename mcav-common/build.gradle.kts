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
    implementation(libs.xz)
    compileOnly(libs.osgi.annotation)
    testImplementation(libs.slf4j.simple)
    testImplementation(libs.jimfs)
}

val pinGeneratorClass: String = "me.brandonli.mcav.capability.installer.debian.DebianPinGenerator"
val installerResourcePath: String = "src/main/resources/mcav/common/installers"

val installerResources: Directory = layout.projectDirectory.dir(installerResourcePath)
val testSourceSet: SourceSet = sourceSets.test.get()

tasks.register<JavaExec>("generateDebianPins") {
    this.group = "build setup"
    this.description = "Pins the Debian packages that mcav fetches libraries from to the latest Debian archive and security snapshots"
    this.classpath = testSourceSet.runtimeClasspath
    this.mainClass.set(pinGeneratorClass)
    this.args(installerResources.asFile.path)
}
