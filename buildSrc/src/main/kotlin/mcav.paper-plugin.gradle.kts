import java.net.ServerSocket
import me.brandonli.mcav.gradle.RequiredModuleClassesTask
import me.brandonli.mcav.gradle.libraryOf
import me.brandonli.mcav.gradle.libs
import me.brandonli.mcav.gradle.versionOf
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import xyz.jpenilla.gremlin.gradle.WriteDependencySet
import xyz.jpenilla.runtask.task.AbstractRun

plugins {
    java
    id("mcav.licensing")
    id("com.gradleup.shadow")
    id("xyz.jpenilla.run-paper")
    id("xyz.jpenilla.resource-factory-paper-convention")
    id("xyz.jpenilla.gremlin-gradle")
    id("org.bytedeco.gradle-javacpp-platform")
}

val minecraftVersion = libs.versionOf("minecraft")
val javaRelease = JavaLanguageVersion.of(libs.versionOf("java"))

configurations.compileOnly {
    extendsFrom(configurations.runtimeDownload.get())
}

configurations.testImplementation {
    extendsFrom(configurations.compileOnly.get())
}

configurations.runtimeDownload {
    resolutionStrategy.cacheChangingModulesFor(0, "seconds")
}

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

val requiredModuleClasses = tasks.register<RequiredModuleClassesTask>("requiredModuleClasses") {
    description = "Lists the classes of the downloaded modules that the plugin uses, for its loader to check them"
    val downloaded = configurations.runtimeDownload.get().dependencies
        .filter { it.group == "me.brandonli" && rootProject.findProject(":${it.name}") != null }
        .map { ":${it.name}" }
        .toSet()
    pluginClasses.from(sourceSets.main.map { it.output.classesDirs })
    modules.from(
        configurations.compileClasspath.map { classpath ->
            classpath.incoming.artifactView {
                componentFilter { it is ProjectComponentIdentifier && it.projectPath in downloaded }
            }.files
        }
    )
    output = layout.buildDirectory.dir("generated/resources/required-classes")
}

sourceSets.main {
    resources.srcDir(requiredModuleClasses)
}

val dependencyManifestPath = "mcav/plugin/dependencies.txt"
tasks.processResources {
    inputs.property("dependencyManifestPath", dependencyManifestPath)
    filesMatching("dependencies.txt") {
        path = dependencyManifestPath
    }
}

paperPluginYaml {
    apiVersion = minecraftVersion
}

tasks.runServer {
    minecraftVersion(minecraftVersion)
}

tasks.withType<AbstractRun>().configureEach {
    javaLauncher = javaToolchains.launcherFor {
        vendor = JvmVendorSpec.JETBRAINS
        languageVersion = javaRelease
    }
    jvmArgs("-Xms8192m", "-Xmx8192m", "-XX:+AllowEnhancedClassRedefinition", "-XX:+AllowRedefinitionToAddDeleteMethods")
}

val endToEnd = providers.gradleProperty("mcav.e2e").map { it.toBoolean() }.getOrElse(false)
val endToEndRepository = rootProject.layout.buildDirectory.dir("e2e-repository").get().asFile
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
    repositories.remove(repository)
    repositories.addFirst(repository)
    tasks.named<WriteDependencySet>("writeDependencies") {
        val downloaded = configurations.runtimeDownload.get().dependencies
            .filter { it.group == "me.brandonli" && rootProject.findProject(":${it.name}") != null }
        dependsOn(downloaded.map { ":${it.name}:publishMavenPublicationToEndToEndRepository" })
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
    "e2eTestImplementation"(libs.libraryOf("gson"))
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
