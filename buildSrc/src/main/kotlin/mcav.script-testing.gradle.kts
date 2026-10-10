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
