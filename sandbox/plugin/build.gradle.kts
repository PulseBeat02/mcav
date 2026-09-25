import java.net.ServerSocket
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import xyz.jpenilla.gremlin.gradle.WriteDependencySet
import xyz.jpenilla.resourcefactory.paper.PaperPluginYaml
import xyz.jpenilla.runtask.task.AbstractRun

plugins {
    id("com.gradleup.shadow") version "9.6.1"
    id("xyz.jpenilla.run-paper") version "3.1.0"
    id("xyz.jpenilla.resource-factory-paper-convention") version "1.3.1"
    id("xyz.jpenilla.gremlin-gradle") version "0.0.9"
    // keeps only the native libraries of the platforms a Paper server runs on; see gradle.properties next to this file
    id("org.bytedeco.gradle-javacpp-platform") version "1.5.10"
}

val minecraftVersion = "26.2"

dependencies {
    compileOnly("io.papermc.paper:paper-api:$minecraftVersion.build.+")
    implementation("xyz.jpenilla:gremlin-runtime:0.0.9")

    runtimeDownload("me.brandonli:mcav-bukkit:1.0.0-SNAPSHOT")
    runtimeDownload("me.brandonli:mcav-jda:1.0.0-SNAPSHOT")
    runtimeDownload("me.brandonli:mcav-http:1.0.0-SNAPSHOT")
    runtimeDownload("me.brandonli:mcav-common:1.0.0-SNAPSHOT")
    runtimeDownload("me.brandonli:mcav-vm:1.0.0-SNAPSHOT")
    runtimeDownload("me.brandonli:mcav-vnc:1.0.0-SNAPSHOT")
    runtimeDownload("me.brandonli:mcav-browser:1.0.0-SNAPSHOT")
    implementation("me.brandonli:mcav-svc:1.0.0-SNAPSHOT")

    runtimeDownload("org.incendo:cloud-core:2.1.0")
    runtimeDownload("org.incendo:cloud-annotations:2.1.0")
    runtimeDownload("org.incendo:cloud-paper:2.0.0")
    runtimeDownload("org.incendo:cloud-minecraft-extras:2.0.0")

    runtimeDownload("me.lucko:commodore:2.2")
    runtimeDownload("org.bstats:bstats-bukkit:3.2.1")
    runtimeDownload("net.dv8tion:JDA:6.6.0")
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
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
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
    testImplementation("de.maxhenkel.voicechat:voicechat-api:2.6.20")
    testImplementation("org.slf4j:slf4j-simple:2.0.17")
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
    "e2eTestImplementation"(platform("org.junit:junit-bom:6.1.3"))
    "e2eTestImplementation"("org.junit.jupiter:junit-jupiter")
    "e2eTestRuntimeOnly"("org.junit.platform:junit-platform-launcher")
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
    val launcher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
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
        languageVersion = JavaLanguageVersion.of(25)
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
            url("https://cdn.modrinth.com/data/9eGKb6K1/versions/IhqyykOv/voicechat-bukkit-2.6.23.jar")
            url("https://ci.lucko.me/job/spark/524/artifact/spark-bukkit/build/libs/spark-1.10.172-bukkit.jar")
        }
    }
}
