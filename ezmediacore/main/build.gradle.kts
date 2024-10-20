dependencies {

    // Project modules
    implementation(project(":ezmediacore:v1_21_R1"))
    implementation(project(":ezmediacore:nms-api"))

    // Project dependencies
    implementation("org.bytedeco:javacv-platform:1.5.10")
    implementation("org.jsoup:jsoup:1.18.1")
    implementation("uk.co.caprica:vlcj:4.8.3")
    implementation("uk.co.caprica:vlcj-natives:4.8.3")
    implementation("net.java.dev.jna:jna:5.15.0")
    implementation("net.java.dev.jna:jna-platform:5.15.0")
    implementation("net.dv8tion:JDA:5.1.2")

    // Provided dependencies
    compileOnly("org.spigotmc:spigot-api:1.21.1-R0.1-SNAPSHOT")
    compileOnly("io.netty:netty-all:4.1.87.Final")
    compileOnly("com.google.guava:guava:31.1-jre")
    compileOnly("it.unimi.dsi:fastutil:8.5.15")

    // Test dependencies
    testImplementation("uk.co.caprica:vlcj:4.8.3")
    testImplementation("uk.co.caprica:vlcj-natives:4.8.3")
    testImplementation("com.google.guava:guava:31.1-jre")
}

tasks {

    assemble {
        dependsOn(":ezmediacore::v1_21_R1:reobfJar")
        dependsOn("shadowJar")
    }

    build {
        dependsOn("spotlessApply")
    }
}