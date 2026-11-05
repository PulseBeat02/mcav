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
    id("mcav.licensing")
}

val fabricRepositoryUrl: String = "https://maven.fabricmc.net/"
val neoForgedRepositoryUrl: String = "https://maven.neoforged.net/releases"
val minecraftLibrariesUrl: String = "https://libraries.minecraft.net"
val minecraftClientUrl: String = "https://piston-data.mojang.com/v1/objects/"
val minecraftClientPattern: String = "[revision]/[module].[ext]"

val fabricGroup: String = "net.fabricmc"
val neoForgedGroup: String = "net.neoforged"
val mojangGroup: String = "com.mojang"
val minecraftGroup: String = "net.minecraft"
val minecraftClientModule: String = "client"

val fabricMetadata: String = "fabric.mod.json"
val neoForgeMetadata: String = "META-INF/neoforge.mods.toml"
val fabricClasses: String = "**/Fabric*.class"
val neoForgeClasses: String = "**/NeoForge*.class"

val plainClassifier: String = "plain"
val fabricClassifier: String = "fabric"
val neoForgeClassifier: String = "neoforge"

repositories {
    exclusiveContent {
        forRepository {
            maven(fabricRepositoryUrl) {
                name = "Fabric"
            }
        }
        filter {
            includeGroupAndSubgroups(fabricGroup)
        }
    }
    exclusiveContent {
        forRepository {
            maven(neoForgedRepositoryUrl) {
                name = "NeoForged"
                metadataSources {
                    artifact()
                }
            }
        }
        filter {
            includeGroupAndSubgroups(neoForgedGroup)
        }
    }
    exclusiveContent {
        forRepository {
            maven(minecraftLibrariesUrl) {
                name = "Minecraft libraries"
            }
        }
        filter {
            includeGroup(mojangGroup)
        }
    }
    exclusiveContent {
        forRepository {
            ivy(minecraftClientUrl) {
                name = "Minecraft client"
                patternLayout {
                    artifact(minecraftClientPattern)
                }
                metadataSources {
                    artifact()
                }
            }
        }
        filter {
            includeModule(minecraftGroup, minecraftClientModule)
        }
    }
}

tasks.processResources {
    val modVersion: String = rootProject.version.toString()
    this.inputs.property("modVersion", modVersion)
    this.filesMatching(listOf(fabricMetadata, neoForgeMetadata)) {
        this.expand("version" to modVersion)
    }
}

tasks.jar {
    this.archiveClassifier = plainClassifier
}

val fabricJar: TaskProvider<Jar> = tasks.register<Jar>("fabricJar") {
    this.group = "build"
    this.description = "Assembles the mod for Fabric"
    this.archiveClassifier = fabricClassifier
    this.from(sourceSets.main.map { it.output })
    this.exclude(neoForgeClasses, neoForgeMetadata)
}

val neoForgeJar: TaskProvider<Jar> = tasks.register<Jar>("neoForgeJar") {
    this.group = "build"
    this.description = "Assembles the mod for NeoForge"
    this.archiveClassifier = neoForgeClassifier
    this.from(sourceSets.main.map { it.output })
    this.exclude(fabricClasses, fabricMetadata)
}

tasks.assemble {
    this.dependsOn(fabricJar, neoForgeJar)
}
