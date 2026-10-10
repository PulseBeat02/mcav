import me.brandonli.mcav.gradle.isWindows

plugins {
    id("mcav.module")
    id("mcav.publishing")
}

val npm = node.resolvedNodeDir.get().file(if (isWindows) "npm.cmd" else "bin/npm").asFile
val npmPath = npm.parentFile.absolutePath + File.pathSeparator + System.getenv("PATH")

val npmProjectInstall = tasks.register<Exec>("npmProjectInstall") {
    group = "build"
    description = "Install npm dependencies for the website"
    dependsOn("nodeSetup")
    workingDir = file("mcav-website")
    executable = npm.absolutePath
    environment("PATH", npmPath)
    args("ci")
    inputs.file("mcav-website/package.json")
    inputs.file("mcav-website/package-lock.json")
    outputs.file("mcav-website/node_modules/.package-lock.json")
}

val buildWebsite = tasks.register<Exec>("buildWebsite") {
    group = "build"
    description = "Build the Next.js website"
    dependsOn(npmProjectInstall)
    workingDir = file("mcav-website")
    executable = npm.absolutePath
    environment("PATH", npmPath)
    environment("NODE_OPTIONS", "--max-old-space-size=4096")
    args("run", "build")
    inputs.dir("mcav-website/src")
    inputs.dir("mcav-website/public")
    inputs.file("mcav-website/package.json")
    inputs.file("mcav-website/next.config.ts")
    inputs.file("mcav-website/third-party-notices.mjs")
    inputs.file("mcav-website/package-lock.json")
    inputs.file("mcav-website/tsconfig.json")
    inputs.file("mcav-website/postcss.config.mjs")
    outputs.dir("mcav-website/out")
    outputs.cacheIf { false }
}

val testWebsite = tasks.register<Exec>("testWebsite") {
    group = "verification"
    description = "Run the tests of the website"
    dependsOn("nodeSetup")
    workingDir = file("mcav-website")
    executable = npm.absolutePath
    environment("PATH", npmPath)
    args("test")
    inputs.dir("mcav-website/src")
    inputs.dir("mcav-website/test")
    inputs.file("mcav-website/package.json")
    val passed = layout.buildDirectory.file("website-tests/passed")
    outputs.file(passed)
    doLast {
        passed.get().asFile.writeText("passed\n")
    }
}

tasks.check {
    dependsOn(testWebsite)
}

tasks.jar {
    from(buildWebsite) {
        into("mcav/http/website")
    }
}

tasks.processTestResources {
    filesMatching("website/**") {
        path = "mcav/http/$path"
    }
}

tasks.named<Jar>("sourcesJar") {
    from(buildWebsite) {
        into("mcav/http/website")
    }
}

val builtJar = "-Dmcav.http.jar=" + layout.buildDirectory.file("libs/mcav-http.jar").get().asFile.absolutePath
tasks.test {
    dependsOn(tasks.jar)
    jvmArgs(builtJar)
}
tasks.named("pitest") {
    dependsOn(tasks.jar)
}
