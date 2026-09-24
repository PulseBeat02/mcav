plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

// the plugins the convention plugins apply; their versions live here, so the module build files only name them
dependencies {
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(gradleTestKit())
    implementation("com.diffplug.spotless:spotless-plugin-gradle:7.0.0.BETA4")
    implementation("org.checkerframework:checker-framework-gradle-plugin:1.0.2")
    implementation("com.github.node-gradle:gradle-node-plugin:7.1.0")
    implementation("net.ltgt.gradle:gradle-errorprone-plugin:5.1.1")
    implementation("info.solidsoft.gradle.pitest:gradle-pitest-plugin:1.19.0")
}

tasks.test {
    useJUnitPlatform()
}
