// A Paper plugin built on the modules of mcav: a shadow jar with a paper-plugin.yml, whose server downloads the
// libraries in `runtimeDownload` at startup through Gremlin, a development server (`runServer`), and an end-to-end test
// (`e2eTest`) that runs the plugin on a real headless Paper server. The plugin compiles and tests against the modules of
// this build, so API changes show up before they are published; the server still downloads the published snapshots,
// unless -Pmcav.e2e=true makes it download the modules of this build, which are published into build/e2e-repository
// first and served on a loopback port while the end-to-end test runs.

import java.net.ServerSocket
import me.brandonli.mcav.gradle.libraryOf
import me.brandonli.mcav.gradle.libs
import me.brandonli.mcav.gradle.versionOf
import org.gradle.api.artifacts.component.ModuleComponentSelector
import xyz.jpenilla.gremlin.gradle.WriteDependencySet
import xyz.jpenilla.runtask.task.AbstractRun

plugins {
    java
    id("com.gradleup.shadow")
    id("xyz.jpenilla.run-paper")
    id("xyz.jpenilla.resource-factory-paper-convention")
    id("xyz.jpenilla.gremlin-gradle")
    // keeps only the native libraries of the platforms a Paper server runs on, which the gradle.properties of the
    // plugin's project lists
    id("org.bytedeco.gradle-javacpp-platform")
}

val minecraftVersion = libs.versionOf("minecraft")
val javaRelease = JavaLanguageVersion.of(libs.versionOf("java"))

configurations.compileOnly {
    extendsFrom(configurations.runtimeDownload.get())
}

// the tests run without a server, so everything the server provides or downloads is on their class path, with the
// libraries the modules of this build only compile against
configurations.testImplementation {
    extendsFrom(configurations.compileOnly.get())
}

// the server records the hashes of the snapshots it downloads, which must be current
configurations.runtimeDownload {
    resolutionStrategy.cacheChangingModulesFor(0, "seconds")
}

// on every class path, a module of this build comes from its project instead of the published snapshot
configurations.matching { it.name.endsWith("Classpath") }.configureEach {
    resolutionStrategy.dependencySubstitution.all {
        val module = requested as? ModuleComponentSelector ?: return@all
        val project = rootProject.findProject(":${module.module}")
        if (module.group == "me.brandonli" && project != null) {
            useTarget(project)
        }
    }
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}

paperPluginYaml {
    apiVersion = minecraftVersion
}

tasks.runServer {
    minecraftVersion(minecraftVersion)
}

// a JetBrains runtime, whose enhanced class redefinition lets a debugger swap changed classes into the running server
tasks.withType<AbstractRun>().configureEach {
    javaLauncher = javaToolchains.launcherFor {
        vendor = JvmVendorSpec.JETBRAINS
        languageVersion = javaRelease
    }
    jvmArgs("-Xms8192m", "-Xmx8192m", "-XX:+AllowEnhancedClassRedefinition", "-XX:+AllowRedefinitionToAddDeleteMethods")
}

val endToEnd = providers.gradleProperty("mcav.e2e").map { it.toBoolean() }.getOrElse(false)
val endToEndRepository = rootProject.layout.buildDirectory.dir("e2e-repository").get().asFile
// Gremlin downloads with an HTTP client, which cannot read a folder, so the test serves the folder on this port while
// the server runs; nothing listens there at build time, and Gradle never asks it
val endToEndPort = if (endToEnd) {
    providers.gradleProperty("mcav.e2e.repositoryPort").orNull?.toInt() ?: ServerSocket(0).use { it.localPort }
} else {
    0
}

if (endToEnd) {
    val repository = repositories.maven {
        name = "endToEnd"
        url = endToEndRepository.toURI()
    }
    // Gradle resolves the modules of this build from the folder before it asks the public repositories
    repositories.remove(repository)
    repositories.addFirst(repository)
    tasks.named<WriteDependencySet>("writeDependencies") {
        val downloaded = configurations.runtimeDownload.get().dependencies
            .filter { it.group == "me.brandonli" && rootProject.findProject(":${it.name}") != null }
        dependsOn(downloaded.map { ":${it.name}:publishMavenPublicationToEndToEndRepository" })
        // Gremlin lists only http(s) repositories and tries them in order, so the server asks the test's repository
        // first for the timestamped snapshots of this build, which no public repository has
        val publicRepositories = repositories.withType<MavenArtifactRepository>()
            .filter { it.url.scheme == "http" || it.url.scheme == "https" }
            .map { it.url.toString() }
        repos.set(listOf("http://127.0.0.1:$endToEndPort/") + publicRepositories)
    }
}

val e2eTestSourceSet = sourceSets.create("e2eTest")

dependencies {
    "e2eTestImplementation"(platform(libs.libraryOf("junit-bom")))
    "e2eTestImplementation"(libs.libraryOf("junit-jupiter"))
    "e2eTestRuntimeOnly"(libs.libraryOf("junit-platform-launcher"))
}

tasks.register<Test>("e2eTest") {
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
    systemProperty("mcav.e2e.repositoryDirectory", endToEndRepository.absolutePath)
    systemProperty("mcav.e2e.repositoryPort", endToEndPort)
    val launcher = javaToolchains.launcherFor { languageVersion = javaRelease }
    doFirst {
        if (!endToEnd) {
            throw GradleException("Run the end-to-end test with -Pmcav.e2e=true, so the server uses the modules of this build")
        }
        systemProperty("mcav.e2e.pluginJar", pluginJar.get().asFile.absolutePath)
        systemProperty("mcav.e2e.java", launcher.get().executablePath.asFile.absolutePath)
    }
}
