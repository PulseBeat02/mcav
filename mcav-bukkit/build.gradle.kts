plugins {
    id("maven-publish")
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.17"
}

dependencies {

    // project dependencies
    paperweight.paperDevBundle("1.21.5-no-moonrise-SNAPSHOT")
    api("team.unnamed:creative-api:1.7.3")
    api("team.unnamed:creative-serializer-minecraft:1.7.3")
    api("net.bytebuddy:byte-buddy:1.17.5")
    api("net.bytebuddy:byte-buddy-agent:1.17.5")

    // provided
    compileOnlyApi(project(":mcav-common"))
    compileOnlyApi("io.netty:netty-all:4.1.97.Final")
    compileOnlyApi("com.google.guava:guava:33.4.8-jre")
    compileOnlyApi("com.google.code.gson:gson:2.13.1")
    compileOnlyApi("net.java.dev.jna:jna:5.17.0")

    // testing
    testImplementation(project(":mcav-common"))
}

val targetJavaVersion = 21
java {
    val javaVersion = JavaVersion.toVersion(targetJavaVersion)
    val language = JavaLanguageVersion.of(targetJavaVersion)
    sourceCompatibility = javaVersion
    targetCompatibility = javaVersion
    toolchain.languageVersion.set(language)
}

tasks {

    withType<JavaCompile>().configureEach {
        options.release.set(targetJavaVersion)
    }

    java {
        withSourcesJar()
        withJavadocJar()
    }

    assemble {
        dependsOn("reobfJar")
    }

    withType<Javadoc>().configureEach {
        options.encoding = "UTF-8"
    }
}

publishing {
    repositories {
        maven {
            name = "brandonli"
            url = uri("https://repo.brandonli.me/snapshots")
            credentials(PasswordCredentials::class)
            authentication {
                create<BasicAuthentication>("basic")
            }
        }
    }
    publications {
        create<MavenPublication>("maven") {
            groupId = "me.brandonli"
            artifactId = project.name
            version = "${rootProject.version}"
            artifact(tasks.named("reobfJar"))
            artifact(tasks.named("sourcesJar")) {
                classifier = "sources"
                builtBy(tasks.named("reobfJar"))
            }
            artifact(tasks.named("javadocJar")) {
                classifier = "javadoc"
                builtBy(tasks.named("reobfJar"))
            }
        }
    }
}