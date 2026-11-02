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

plugins {
    id("mcav.module")
    id("mcav.publishing")
}

val commonProjectPath: String = ":mcav-common"
val pinGeneratorClass: String = "me.brandonli.mcav.capability.installer.debian.DebianPinGenerator"
val installerResourcePath: String = "src/main/resources/mcav/vm/installers"
val includedIndependentPackages: String = "INCLUDED"
val pinBundles: List<String> = listOf(
    "debian-qemu-x86.json=qemu-system-x86",
    "debian-qemu-arm.json=qemu-system-arm",
    "debian-qemu-misc.json=qemu-system-misc"
)

evaluationDependsOn(commonProjectPath)

val commonProject: Project = project(commonProjectPath)
val commonSourceSets: SourceSetContainer = commonProject.the<SourceSetContainer>()
val commonTestSourceSet: SourceSet = commonSourceSets.getByName("test")
val commonTestClasses: SourceSetOutput = commonTestSourceSet.output
val testRuntimeClasspath: Configuration = configurations.testRuntimeClasspath.get()
val installerResources: Directory = layout.projectDirectory.dir(installerResourcePath)

tasks.register<JavaExec>("generateDebianPins") {
    this.group = "build setup"
    this.description = "Pins the Debian packages that mcav fetches QEMU from to the latest Debian archive and security snapshots"
    // common's own test runtime classpath may only be resolved by common's tasks
    this.classpath = commonTestClasses + testRuntimeClasspath
    this.mainClass.set(pinGeneratorClass)
    this.args(installerResources.asFile.path, includedIndependentPackages)
    this.args(pinBundles)
}
