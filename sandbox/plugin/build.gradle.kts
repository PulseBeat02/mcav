import java.net.ServerSocket
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import xyz.jpenilla.gremlin.gradle.WriteDependencySet
import xyz.jpenilla.resourcefactory.bukkit.Permission
import xyz.jpenilla.resourcefactory.paper.PaperPluginYaml
import xyz.jpenilla.runtask.task.AbstractRun

plugins {
    alias(libs.plugins.shadow)
    alias(libs.plugins.run.paper)
    alias(libs.plugins.resource.factory.paper)
    alias(libs.plugins.gremlin)
    // keeps only the native libraries of the platforms a Paper server runs on; see gradle.properties next to this file
    alias(libs.plugins.javacpp.platform)
}

val minecraftVersion = libs.versions.minecraft.get()
val javaRelease = libs.versions.java.get().toInt()

dependencies {
    compileOnly(libs.paper.api)
    implementation(libs.gremlin.runtime)

    runtimeDownload("me.brandonli:mcav-bukkit:${rootProject.version}")
    runtimeDownload("me.brandonli:mcav-jda:${rootProject.version}")
    runtimeDownload("me.brandonli:mcav-http:${rootProject.version}")
    runtimeDownload("me.brandonli:mcav-common:${rootProject.version}")
    runtimeDownload("me.brandonli:mcav-vm:${rootProject.version}")
    runtimeDownload("me.brandonli:mcav-vnc:${rootProject.version}")
    runtimeDownload("me.brandonli:mcav-browser:${rootProject.version}")
    implementation("me.brandonli:mcav-svc:${rootProject.version}")

    runtimeDownload(libs.cloud.core)
    runtimeDownload(libs.cloud.annotations)
    runtimeDownload(libs.cloud.paper)
    runtimeDownload(libs.cloud.minecraft.extras)

    runtimeDownload(libs.commodore)
    runtimeDownload(libs.bstats.bukkit)
    runtimeDownload(libs.jda)
}

configurations.compileOnly {
    extendsFrom(configurations.runtimeDownload.get())
}

// The browser benchmark measures how many frames per second a browser backend brings onto a wall of maps and how long
// a page change takes to reach the map encoder, through the pipeline of /mcav browser create. It is run by hand, never
// by the build: ./gradlew :sandbox:plugin:browserBenchmark -Pbenchmark.backend=<backend> -Pbenchmark.output=<file>
val benchmarkSourceSet = sourceSets.create("benchmark")
configurations.named("benchmarkImplementation") {
    extendsFrom(configurations.testImplementation.get())
}
configurations.named("benchmarkRuntimeOnly") {
    extendsFrom(configurations.testRuntimeOnly.get())
}
dependencies {
    "benchmarkImplementation"(sourceSets.main.get().output)
}
tasks.register<JavaExec>("browserBenchmark") {
    description = "Measures the frame rate and latency of a browser backend: -Pbenchmark.backend=<backend> -Pbenchmark.output=<file>"
    group = "verification"
    classpath = benchmarkSourceSet.runtimeClasspath
    mainClass = "me.brandonli.mcav.sandbox.benchmark.BrowserBenchmark"
    val backend = providers.gradleProperty("benchmark.backend").orElse("")
    val output = providers.gradleProperty("benchmark.output").orElse(layout.buildDirectory.file("browser-benchmark.md").get().asFile.absolutePath)
    argumentProviders.add(CommandLineArgumentProvider { listOf(backend.get(), output.get()) })
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(javaRelease) }
    maxHeapSize = "4g"
    outputs.upToDateWhen { false }
}

// compile against the modules of this build so API changes show up here before they are published;
// the server still downloads the published snapshots at runtime
val localModules = listOf("mcav-common", "mcav-bukkit", "mcav-jda", "mcav-http", "mcav-vm", "mcav-vnc", "mcav-browser", "mcav-svc")
listOf("compileClasspath", "testCompileClasspath", "testRuntimeClasspath", "benchmarkCompileClasspath", "benchmarkRuntimeClasspath").forEach { name ->
    configurations.named(name) {
        resolutionStrategy.dependencySubstitution {
            localModules.forEach { module ->
                substitute(module("me.brandonli:$module")).using(project(":$module"))
            }
        }
    }
}

// the tests run without a server, so everything the server provides or downloads at runtime is put on their
// classpath, together with the libraries the local modules only compile against
configurations.testImplementation {
    extendsFrom(configurations.compileOnly.get())
}

dependencies {
    testImplementation(libs.voicechat.api)
    testImplementation(libs.slf4j.simple)
}

// the shaded voice chat module comes from this build as well, so the jar holds the code the plugin was compiled with
configurations.named("runtimeClasspath") {
    resolutionStrategy.dependencySubstitution {
        substitute(module("me.brandonli:mcav-svc")).using(project(":mcav-svc"))
    }
}

// The end-to-end test runs the plugin on a real headless Paper server. With -Pmcav.e2e=true the server downloads the
// modules of this build, published into build/e2e-repository first, instead of the published snapshots.
val endToEnd = providers.gradleProperty("mcav.e2e").map { it.toBoolean() }.getOrElse(false)
val endToEndRepositoryDirectory = rootProject.layout.buildDirectory.dir("e2e-repository").get().asFile
// Gremlin downloads the libraries with an HTTP client, which cannot read the folder, so the end-to-end test serves the
// folder on this loopback port while the server runs; nothing listens there at build time, and Gradle never asks it
val endToEndRepositoryPort = if (endToEnd) {
    providers.gradleProperty("mcav.e2e.repositoryPort").orNull?.toInt() ?: ServerSocket(0).use { it.localPort }
} else {
    0
}
if (endToEnd) {
    val endToEndRepository = repositories.maven {
        name = "endToEnd"
        url = endToEndRepositoryDirectory.toURI()
    }
    // Gradle resolves the modules of this build from the folder before it asks the public repositories
    repositories.remove(endToEndRepository)
    repositories.addFirst(endToEndRepository)
    val downloadedModules = localModules.filter { it != "mcav-svc" }
    tasks.named<WriteDependencySet>("writeDependencies") {
        dependsOn(downloadedModules.map { ":$it:publishMavenPublicationToEndToEndRepository" })
        // Gremlin lists only http(s) repositories and tries them in order, so the server asks the repository of the
        // end-to-end test first for the timestamped snapshots of this build, which no public repository has
        val publicRepositories = repositories.withType<MavenArtifactRepository>()
            .filter { it.url.scheme == "http" || it.url.scheme == "https" }
            .map { it.url.toString() }
        repos.set(listOf("http://127.0.0.1:$endToEndRepositoryPort/") + publicRepositories)
    }
}

val e2eTestSourceSet = sourceSets.create("e2eTest")

dependencies {
    "e2eTestImplementation"(platform(libs.junit.bom))
    "e2eTestImplementation"(libs.junit.jupiter)
    "e2eTestRuntimeOnly"(libs.junit.platform.launcher)
}

val e2eTest = tasks.register<Test>("e2eTest") {
    description = "Runs the plugin on a real headless Paper server; needs the internet, -Pmcav.e2e=true and -Pmcav.acceptMinecraftEula=true."
    group = "verification"
    testClassesDirs = e2eTestSourceSet.output.classesDirs
    classpath = e2eTestSourceSet.runtimeClasspath
    shouldRunAfter(tasks.test)
    val pluginJar = tasks.shadowJar.flatMap { it.archiveFile }
    inputs.file(pluginJar)
    outputs.upToDateWhen { false }
    systemProperty("mcav.e2e.cacheDirectory", layout.buildDirectory.dir("e2e-cache").get().asFile.absolutePath)
    systemProperty("mcav.e2e.acceptEula", providers.gradleProperty("mcav.acceptMinecraftEula").getOrElse("false"))
    systemProperty("mcav.e2e.repositoryDirectory", endToEndRepositoryDirectory.absolutePath)
    systemProperty("mcav.e2e.repositoryPort", endToEndRepositoryPort)
    val launcher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(javaRelease) }
    doFirst {
        if (!endToEnd) {
            throw GradleException("Run the end-to-end test with -Pmcav.e2e=true, so the server uses the modules of this build")
        }
        systemProperty("mcav.e2e.pluginJar", pluginJar.get().asFile.absolutePath)
        systemProperty("mcav.e2e.java", launcher.get().executablePath.asFile.absolutePath)
    }
}

version = "1.0.0-v$minecraftVersion"

tasks.withType<AbstractRun>().configureEach {
    javaLauncher.set(javaToolchains.launcherFor {
        vendor = JvmVendorSpec.JETBRAINS
        languageVersion = JavaLanguageVersion.of(javaRelease)
    })
    jvmArgs(
        "-Xms8192m",
        "-Xmx8192m",
        "-XX:+AllowEnhancedClassRedefinition",
        "-XX:+AllowRedefinitionToAddDeleteMethods"
    )
}

paperPluginYaml {
    name = "MCAV"
    version = "${project.version}"
    description = "MCAV Sandbox Plugin"
    authors = listOf("PulseBeat_02")
    apiVersion = minecraftVersion
    prefix = "MCAV Sandbox"
    loader = "me.brandonli.mcav.sandbox.MCAVLoader"
    main = "me.brandonli.mcav.sandbox.MCAVSandbox"
    dependencies.server("voicechat", PaperPluginYaml.Load.BEFORE, false)
    // every permission the commands check, so that permission plugins can list them; each is for operators until
    // granted, and PluginDescriptorTest keeps the list equal to the commands' @Permission annotations
    defaultPermission = Permission.Default.OP
    permissions {
        register("mcav.command.help") { description = "Shows the commands of MCAV and what they do" }
        register("mcav.command.dump") { description = "Uploads a report of the server and its logs, with addresses and other commands masked, for a bug report" }
        register("mcav.command.screen") { description = "Builds a wall of item frames with maps that images, videos, browsers and virtual machines show on" }
        register("mcav.command.hologram.set") { description = "Shows a video or an image as a hologram" }
        register("mcav.command.hologram.disable") { description = "Removes the hologram" }
        register("mcav.command.image.chat") { description = "Shows an image in chat" }
        register("mcav.command.image.block") { description = "Shows an image with blocks" }
        register("mcav.command.image.entity") { description = "Shows an image with the names of entities" }
        register("mcav.command.image.scoreboard") { description = "Shows an image on the scoreboard" }
        register("mcav.command.image.map") { description = "Shows an image on a wall of maps" }
        register("mcav.command.image.release") { description = "Removes the image" }
        register("mcav.command.video.chat") { description = "Plays a video in chat" }
        register("mcav.command.video.block") { description = "Plays a video with blocks" }
        register("mcav.command.video.entity") { description = "Plays a video with the names of entities" }
        register("mcav.command.video.scoreboard") { description = "Plays a video on the scoreboard" }
        register("mcav.command.video.map") { description = "Plays a video on a wall of maps" }
        register("mcav.command.video.mcv2") { description = "Plays a video on a wall of maps through the MCV2 codec, dithered for players without its resource pack" }
        register("mcav.command.video.pause") { description = "Pauses the video" }
        register("mcav.command.video.resume") { description = "Resumes the video" }
        register("mcav.command.video.release") { description = "Stops the video" }
        register("mcav.command.video.seek") { description = "Jumps to a time of the video" }
        register("mcav.command.video.volume") { description = "Sets the volume of the videos" }
        register("mcav.command.video.speed") { description = "Plays the video file faster or slower" }
        register("mcav.command.video.loop") { description = "Makes the videos play again when they end" }
        register("mcav.command.video.device") { description = "Lists the cameras and capture cards of the server, and plays them with the DEVICE player" }
        register("mcav.command.mcv2.play") { description = "Plays, streams and stops pre-encoded MCV2 streams from the plugin's mcv2 folder" }
        register("mcav.command.mcv2.encode") { description = "Pre-encodes a video file into an MCV2 stream in the plugin's mcv2 folder, and cancels that encode" }
        register("mcav.command.browser.create") { description = "Opens a web page on a wall of maps, with its sound in an audio output" }
        register("mcav.browser.release") { description = "Closes the browser" }
        register("mcav.browser.interact") { description = "Clicks and types into the browser: clicks on its screen, and chat with /mcav browser interact" }
        register("mcav.command.vm.create") { description = "Boots a QEMU virtual machine on a wall of maps, with its sound in an audio output" }
        register("mcav.vm.release") { description = "Powers off the virtual machine" }
        register("mcav.vm.interact") { description = "Clicks and types into the virtual machine: clicks on its screen, and chat with /mcav vm interact" }
        register("mcav.command.vnc.create") { description = "Shows the desktop of a VNC server listed in vnc.allowed-hosts on a wall of maps" }
        register("mcav.vnc.release") { description = "Disconnects from the VNC desktop" }
        register("mcav.vnc.interact") { description = "Clicks and types on the VNC desktop: clicks on its screen, and chat with /mcav vnc interact" }
    }
}

tasks {

    shadowJar {
        archiveBaseName.set("mcav-sandbox")
    }

    assemble {
        dependsOn("shadowJar")
    }

    runServer {
        systemProperty("net.kyori.adventure.text.warnWhenLegacyFormattingDetected", false)
        minecraftVersion(minecraftVersion)
        downloadPlugins {
            modrinth("simple-voice-chat", libs.versions.voicechat.plugin.get())
            val spark = libs.versions.spark.asProvider().get()
            url("https://ci.lucko.me/job/spark/${libs.versions.spark.build.get()}/artifact/spark-bukkit/build/libs/spark-$spark-bukkit.jar")
        }
    }
}
