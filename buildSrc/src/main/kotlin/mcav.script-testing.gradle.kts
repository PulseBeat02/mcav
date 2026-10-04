// Script fixtures use the same pinned Node executable as formatting, including in PIT child JVMs.

import me.brandonli.mcav.gradle.isWindows

plugins {
    id("mcav.formatting")
    id("mcav.mutation")
}

val testNode = node.resolvedNodeDir.map { it.file(if (isWindows) "node.exe" else "bin/node").asFile }

tasks.withType<Test>().configureEach {
    dependsOn("nodeSetup")
    jvmArgs("-Dmcav.testNode=${testNode.get().absolutePath}")
}

tasks.named("pitest") {
    dependsOn("nodeSetup")
}
