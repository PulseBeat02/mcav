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

import org.gradle.api.artifacts.ModuleDependency

plugins {
    id("mcav.module")
    id("mcav.publishing")
}

val bytedecoGroup: String = "org.bytedeco"
val javacvPlatformModule: String = "javacv-platform"
val platformSuffix: String = "-platform"

val unusedJavacvPresets: List<String> = listOf(
    "flycapture",
    "libdc1394",
    "libfreenect",
    "libfreenect2",
    "librealsense",
    "videoinput",
    "artoolkitplus",
    "flandmark",
    "leptonica",
    "tesseract"
)

configurations.api {
    dependencies.withType<ModuleDependency>().configureEach {
        if (group == bytedecoGroup && name == javacvPlatformModule) {
            for (preset in unusedJavacvPresets) {
                exclude(group = bytedecoGroup, module = preset)
                exclude(group = bytedecoGroup, module = "$preset$platformSuffix")
            }
        }
    }
}

val pinGeneratorClass: String = "me.brandonli.mcav.capability.installer.debian.DebianPinGenerator"
val installerResourcePath: String = "src/main/resources/mcav/common/installers"

val installerResources: Directory = layout.projectDirectory.dir(installerResourcePath)
val testSourceSet: SourceSet = sourceSets.test.get()

tasks.register<JavaExec>("generateDebianPins") {
    this.group = "build setup"
    this.description = "Pins the Debian packages that mcav fetches libraries from to the latest Debian archive and security snapshots"
    this.classpath = testSourceSet.runtimeClasspath
    this.mainClass.set(pinGeneratorClass)
    this.args(installerResources.asFile.path)
}
