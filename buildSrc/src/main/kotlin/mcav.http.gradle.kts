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

import com.github.gradle.node.NodeExtension
import me.brandonli.mcav.gradle.HostPlatform

plugins {
    id("mcav.module")
    id("mcav.publishing")
}

val pathVariable: String = "PATH"
val corepackPromptVariable: String = "COREPACK_ENABLE_DOWNLOAD_PROMPT"
val disabledPrompt: String = "0"
val nodeOptionsVariable: String = "NODE_OPTIONS"
val nodeHeapMegabytes: Int = 4096

val websitePath: String = "mcav-website"
val packageJsonPath: String = "mcav-website/package.json"
val pnpmLockPath: String = "mcav-website/pnpm-lock.yaml"
val installedModulesPath: String = "mcav-website/node_modules/.modules.yaml"
val websiteSourcePath: String = "mcav-website/src"
val websitePublicPath: String = "mcav-website/public"
val websiteTestPath: String = "mcav-website/test"
val websiteOutputPath: String = "mcav-website/out"
val websiteConfigurationFiles: List<String> = listOf(
    "mcav-website/next.config.ts",
    "mcav-website/third-party-notices.mjs",
    "mcav-website/tsconfig.json",
    "mcav-website/postcss.config.mjs"
)
val websiteTestsPassedPath: String = "website-tests/passed"
val passedMarker: String = "passed\n"

val bundledWebsitePath: String = "mcav/http/website"
val websiteTestResources: String = "website/**"
val httpResourcePath: String = "mcav/http"
val builtJarProperty: String = "mcav.http.jar"
val builtJarPath: String = "libs/mcav-http.jar"

val node: NodeExtension = rootProject.extensions.getByType<NodeExtension>()
val nodeSetup: TaskProvider<Task> = rootProject.tasks.named("nodeSetup")
val host: HostPlatform = HostPlatform.current()
val isWindows: Boolean = host.isWindows
val nodeDirectory: Directory = node.resolvedNodeDir.get()
val nodeExecutable: File = nodeDirectory.file(if (isWindows) "node.exe" else "bin/node").asFile
// node-gradle unpacks the bin/corepack symlink as an empty file, so corepack runs through node
val corepackScript: String = if (isWindows) "node_modules/corepack/dist/corepack.js" else "lib/node_modules/corepack/dist/corepack.js"
val corepack: File = nodeDirectory.file(corepackScript).asFile
val nodePath: String = "${nodeExecutable.parentFile.absolutePath}${File.pathSeparator}${System.getenv(pathVariable)}"

val pnpmProjectInstall: TaskProvider<Exec> = tasks.register<Exec>("pnpmProjectInstall") {
    this.group = "build"
    this.description = "Installs the pnpm dependencies of the website"
    this.dependsOn(nodeSetup)
    this.workingDir = file(websitePath)
    this.executable = nodeExecutable.absolutePath
    this.environment(pathVariable, nodePath)
    this.environment(corepackPromptVariable, disabledPrompt)
    this.args(corepack.absolutePath, "pnpm", "install", "--frozen-lockfile")
    this.inputs.file(packageJsonPath)
    this.inputs.file(pnpmLockPath)
    this.outputs.file(installedModulesPath)
}

val buildWebsite: TaskProvider<Exec> = tasks.register<Exec>("buildWebsite") {
    this.group = "build"
    this.description = "Builds the Next.js website"
    this.dependsOn(pnpmProjectInstall)
    this.workingDir = file(websitePath)
    this.executable = nodeExecutable.absolutePath
    this.environment(pathVariable, nodePath)
    this.environment(corepackPromptVariable, disabledPrompt)
    this.environment(nodeOptionsVariable, "--max-old-space-size=$nodeHeapMegabytes")
    this.args(corepack.absolutePath, "pnpm", "run", "build")
    this.inputs.dir(websiteSourcePath)
    this.inputs.dir(websitePublicPath)
    this.inputs.file(packageJsonPath)
    this.inputs.file(pnpmLockPath)
    this.inputs.files(websiteConfigurationFiles)
    this.outputs.dir(websiteOutputPath)
    this.outputs.cacheIf { false }
}

val testWebsite: TaskProvider<Exec> = tasks.register<Exec>("testWebsite") {
    this.group = "verification"
    this.description = "Runs the tests of the website"
    this.dependsOn(pnpmProjectInstall)
    this.workingDir = file(websitePath)
    this.executable = nodeExecutable.absolutePath
    this.environment(pathVariable, nodePath)
    this.environment(corepackPromptVariable, disabledPrompt)
    this.args(corepack.absolutePath, "pnpm", "test")
    this.inputs.dir(websiteSourcePath)
    this.inputs.dir(websiteTestPath)
    this.inputs.file(packageJsonPath)

    val passed: Provider<RegularFile> = layout.buildDirectory.file(websiteTestsPassedPath)
    this.outputs.file(passed)
    this.doLast {
        passed.get().asFile.writeText(passedMarker)
    }
}

tasks.check {
    this.dependsOn(testWebsite)
}

tasks.jar {
    this.from(buildWebsite) {
        this.into(bundledWebsitePath)
    }
}

tasks.processTestResources {
    this.filesMatching(websiteTestResources) {
        this.path = "$httpResourcePath/${this.path}"
    }
}

tasks.named<Jar>("sourcesJar") {
    this.from(buildWebsite) {
        this.into(bundledWebsitePath)
    }
}

val builtJarFile: String = layout.buildDirectory.file(builtJarPath).get().asFile.absolutePath
val builtJar: String = "-D$builtJarProperty=$builtJarFile"
tasks.test {
    this.dependsOn(tasks.jar)
    this.jvmArgs(builtJar)
}

tasks.named("pitest") {
    this.dependsOn(tasks.jar)
}
