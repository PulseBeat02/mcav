// The MCV2 client mod: one jar for Fabric and one for NeoForge, from one set of classes. Minecraft 26.3 ships
// unobfuscated, so the client jar Mojang publishes has the names both loaders run with, and the mod compiles against it
// like against any library: neither Fabric Loom nor NeoForge's ModDevGradle is needed, and neither adds its own build
// classpath to buildSrc, which configures every other module too. The repositories serve this module alone, each for
// the groups it is the home of.

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
                // the jars alone: NeoForge's Gradle metadata describes the variants ModDevGradle sets up, not a library
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
            // a version's client jar, under the SHA-1 its version manifest gives it, which the catalog keeps as its version
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

// the loaders read the mod's version from its metadata: the version of mcav, which only the root project sets
tasks.processResources {
    val modVersion = rootProject.version.toString()
    inputs.property("modVersion", modVersion)
    filesMatching(listOf("fabric.mod.json", "META-INF/neoforge.mods.toml")) {
        expand("version" to modVersion)
    }
}

// the classes of both loaders, which nobody installs; each loader's jar leaves the other's entry point and metadata out
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
