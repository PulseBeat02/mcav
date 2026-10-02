import me.brandonli.mcav.gradle.isWindows

plugins {
    id("mcav.module")
    id("mcav.publishing")
}

dependencies {
    api(libs.bundles.spring.boot.web) {
        exclude(group = "org.springframework.boot", module = "spring-boot-starter-logging")
    }
    compileOnlyApi(project(":mcav-common"))
    testImplementation(project(":mcav-common"))
    testImplementation(libs.slf4j.simple)
    // the releases that fix the known vulnerabilities of the Tomcat and Jackson Spring Boot brings (see the catalog)
    constraints {
        api(libs.tomcat.embed.core)
        api(libs.tomcat.embed.el)
        api(libs.tomcat.embed.websocket)
        api(libs.jackson.bom)
    }
}

// The website of the audio web player (mcav-website, Next.js) is built with the npm of the Node.js the build downloads
// and served from the jar's static folder. npm's shebang looks node up on the PATH, so that Node.js comes first there.
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
    inputs.file("mcav-website/package-lock.json")
    inputs.file("mcav-website/tsconfig.json")
    inputs.file("mcav-website/postcss.config.mjs")
    outputs.dir("mcav-website/out")
    outputs.cacheIf { false }
}

tasks.jar {
    from(buildWebsite) {
        into("static")
    }
}

tasks.named<Jar>("sourcesJar") {
    from(buildWebsite) {
        into("static")
    }
}
