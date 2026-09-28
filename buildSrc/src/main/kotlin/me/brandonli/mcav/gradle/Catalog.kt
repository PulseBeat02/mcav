package me.brandonli.mcav.gradle

import org.gradle.api.Project
import org.gradle.api.artifacts.ExternalModuleDependencyBundle
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.getByType

/**
 * The version catalog of the build, `gradle/libs.versions.toml`. Gradle generates the type-safe `libs` accessor for
 * build scripts only, so the convention plugins look entries up by their alias.
 */
val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

/** The version the catalog calls [alias]. */
fun VersionCatalog.versionOf(alias: String): String = findVersion(alias).get().requiredVersion

/** The library the catalog calls [alias]. */
fun VersionCatalog.libraryOf(alias: String): Provider<MinimalExternalModuleDependency> = findLibrary(alias).get()

/** The bundle the catalog calls [alias]. */
fun VersionCatalog.bundleOf(alias: String): Provider<ExternalModuleDependencyBundle> = findBundle(alias).get()
