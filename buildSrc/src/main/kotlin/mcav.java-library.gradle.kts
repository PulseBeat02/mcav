/*
 * This file is part of mcav, a media playback library for Java
 * Copyright (C) Brandon Li <https://brandonli.me/>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

import me.brandonli.mcav.gradle.CatalogUtils
import me.brandonli.mcav.gradle.CheckerStubs
import me.brandonli.mcav.gradle.JavaToolchains
import me.brandonli.mcav.gradle.libs
import net.ltgt.gradle.errorprone.errorprone

plugins {
    `java-library`
    id("org.checkerframework")
    id("net.ltgt.errorprone")
}

val javaAlias: String = "java"
val errorProneAlias: String = "errorprone-core"
val checkerFrameworkAlias: String = "checker-framework"

val sourceEncoding: String = "UTF-8"
val compilerHeapSize: String = "4g"
val nullnessChecker: String = "org.checkerframework.checker.nullness.NullnessChecker"
val slowTypecheckingSeconds: Int = 600
val checkerStubsPath: String = "gradle/checker-framework"

val repositoryUrls: List<String> = listOf(
    "https://repo.brandonli.me/snapshots",
    "https://maven.maxhenkel.de/repository/public",
    "https://repo.papermc.io/repository/maven-public/",
    "https://hub.spigotmc.org/nexus/content/repositories/snapshots/",
    "https://repo.codemc.io/repository/maven-releases/"
)
val modrinthRepositoryUrl: String = "https://api.modrinth.com/maven"
val modrinthGroup: String = "maven.modrinth"

val javaRelease: Int = CatalogUtils.versionOf(libs, javaAlias).toInt()
val checkerStubs: File = rootProject.file(checkerStubsPath)

val enforcedChecks: List<String> = listOf(
    "FieldCanBeFinal",
    "FieldCanBeStatic",
    "FieldCanBeLocal",
    "ClassCanBeStatic",
    "PrivateConstructorForUtilityClass",
    "ConstantField",
    "ConstantPatternCompile",
    "UnusedVariable",
    "UnusedMethod",
    "UnusedNestedClass",
    "UnusedLabel",
    "RedundantOverride",
    "MultiVariableDeclaration",
    "UngroupedOverloads",
    "WildcardImport",
    "RemoveUnusedImports",
    "MissingOverride",
    "UnnecessaryAnonymousClass",
    "UnnecessaryBoxedVariable",
    "UnnecessaryBoxedAssignment",
    "StaticQualifiedUsingExpression",
    "LongLiteralLowerCaseSuffix",
    "MultipleTopLevelClasses",
    "PackageLocation",
    "DefaultLocale"
)

repositories {
    mavenCentral()
    google()
    repositoryUrls.forEach { maven(it) }
    maven(modrinthRepositoryUrl) {
        content {
            includeGroup(modrinthGroup)
        }
    }
}

dependencies {
    errorprone(CatalogUtils.libraryOf(libs, errorProneAlias))
}

java {
    toolchain.languageVersion = JavaToolchains.languageVersion(project)
}

tasks.withType<JavaCompile>().configureEach {
    this.options.release = javaRelease
    this.options.encoding = sourceEncoding
    this.options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all", "-Xlint:-processing", JavaToolchains.WARNINGS_AS_ERRORS))
    this.options.isFork = true
    this.options.forkOptions.memoryMaximumSize = compilerHeapSize
    this.options.forkOptions.jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("--add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED")
    })
    this.options.errorprone {
        this.disableWarningsInGeneratedCode = true
        this.error(*enforcedChecks.toTypedArray())
        // the style guide keeps stateless private helpers as instance methods called with this.
        this.disable("MethodCanBeStatic")
    }
}

checkerFramework {
    version = CatalogUtils.versionOf(libs, checkerFrameworkAlias)
    checkers = listOf(nullnessChecker)
    excludeTests = true
    extraJavacArgs = CheckerStubs.argumentsFor(checkerStubs, project.name) + "-AslowTypecheckingSeconds=$slowTypecheckingSeconds"
}

tasks.processResources {
    this.duplicatesStrategy = DuplicatesStrategy.INCLUDE
    this.filteringCharset = sourceEncoding
}
