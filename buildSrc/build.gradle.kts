plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    implementation(libs.xz)
    implementation(plugin(libs.plugins.spotless))
    implementation(plugin(libs.plugins.paperweight.userdev))
    implementation(plugin(libs.plugins.checker.framework))
    implementation(plugin(libs.plugins.node))
    implementation(plugin(libs.plugins.errorprone))
    implementation(plugin(libs.plugins.pitest))
    implementation(plugin(libs.plugins.shadow))
    implementation(plugin(libs.plugins.run.paper))
    implementation(plugin(libs.plugins.resource.factory.paper))
    implementation(plugin(libs.plugins.gremlin))
    implementation(plugin(libs.plugins.javacpp.platform))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockito.core)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(gradleTestKit())
}

val catalogVersions = layout.buildDirectory.dir("generated/sources/catalog/kotlin")
val jacocoVersion = libs.versions.jacoco.get()
val repositoryHeader = layout.projectDirectory.file("../HEADER")
val generateCatalogVersions = tasks.register("generateCatalogVersions") {
    description = "Writes the catalog versions the plugins of this build need outside the main build"
    inputs.property("jacoco", jacocoVersion)
    inputs.file(repositoryHeader)
    outputs.dir(catalogVersions)
    doLast {
        val file = catalogVersions.get().file("me/brandonli/mcav/gradle/CatalogVersions.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            repositoryHeader.asFile.readText() + "\n" + """
            |package me.brandonli.mcav.gradle
            |
            |object CatalogVersions {
            |    const val JACOCO = "$jacocoVersion"
            |}
            |""".trimMargin()
        )
    }
}

kotlin.sourceSets.main {
    kotlin.srcDir(generateCatalogVersions)
}

tasks.test {
    useJUnitPlatform()
}

fun plugin(plugin: Provider<PluginDependency>): Provider<String> =
    plugin.map { "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version}" }
