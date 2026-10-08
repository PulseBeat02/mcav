// The HTTP module serves the website of the audio web player from its jar: the build makes the website with npm and runs
// its tests, and the module's notice tests read the jar the build made.

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
    // installs exactly what package-lock.json lists, so every machine builds the same website
    args("ci")
    inputs.file("mcav-website/package.json")
    inputs.file("mcav-website/package-lock.json")
    // npm's installation receipt tells a clean install without hashing tens of thousands of dependency files; use
    // --rerun-tasks to repair a dependency folder changed outside npm
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

// the tests of the website's own code, on the same Node.js; they need none of its npm dependencies
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
    // npm leaves nothing behind, so a marker tells a later build that these inputs passed
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

// the notice tests read the jar the build made, with the website and its npm notices in it, and so do PIT's runs of
// them: Gradle keeps a -D among the test task's system properties, which PIT is not given
val builtJar = "-Dmcav.http.jar=" + layout.buildDirectory.file("libs/mcav-http.jar").get().asFile.absolutePath
tasks.test {
    dependsOn(tasks.jar)
    jvmArgs(builtJar)
}
pitest {
    jvmArgs.add(builtJar)
}
tasks.named("pitest") {
    dependsOn(tasks.jar)
}
