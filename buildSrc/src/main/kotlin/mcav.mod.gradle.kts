plugins {
    id("mcav.module")
}

repositories {
    exclusiveContent {
        forRepository {
            maven("https://maven.fabricmc.net/") {
                name = "Fabric"
            }
        }
        filter {
            includeGroupAndSubgroups("net.fabricmc")
        }
    }
    exclusiveContent {
        forRepository {
            maven("https://maven.neoforged.net/releases") {
                name = "NeoForged"
                metadataSources {
                    artifact()
                }
            }
        }
        filter {
            includeGroupAndSubgroups("net.neoforged")
        }
    }
    exclusiveContent {
        forRepository {
            maven("https://libraries.minecraft.net") {
                name = "Minecraft libraries"
            }
        }
        filter {
            includeGroup("com.mojang")
        }
    }
    exclusiveContent {
        forRepository {
            ivy("https://piston-data.mojang.com/v1/objects/") {
                name = "Minecraft client"
                patternLayout {
                    artifact("[revision]/[module].[ext]")
                }
                metadataSources {
                    artifact()
                }
            }
        }
        filter {
            includeModule("net.minecraft", "client")
        }
    }
}

tasks.processResources {
    val modVersion = rootProject.version.toString()
    inputs.property("modVersion", modVersion)
    filesMatching(listOf("fabric.mod.json", "META-INF/neoforge.mods.toml")) {
        expand("version" to modVersion)
    }
}

tasks.jar {
    archiveClassifier = "plain"
}

val fabricJar = tasks.register<Jar>("fabricJar") {
    description = "Assembles the mod for Fabric"
    group = BasePlugin.BUILD_GROUP
    archiveClassifier = "fabric"
    from(sourceSets.main.map { it.output })
    exclude("**/NeoForge*.class", "META-INF/neoforge.mods.toml")
}

val neoforgeJar = tasks.register<Jar>("neoforgeJar") {
    description = "Assembles the mod for NeoForge"
    group = BasePlugin.BUILD_GROUP
    archiveClassifier = "neoforge"
    from(sourceSets.main.map { it.output })
    exclude("**/Fabric*.class", "fabric.mod.json")
}

tasks.assemble {
    dependsOn(fabricJar, neoforgeJar)
}
