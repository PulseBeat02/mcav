// The installer bootstraps libraries inside other plugins, so its bundled dependencies must use private names.

import com.github.jengelman.gradle.plugins.shadow.transformers.ApacheNoticeResourceTransformer
import me.brandonli.mcav.gradle.McavPublishingExtension
import me.brandonli.mcav.gradle.libraryOf
import me.brandonli.mcav.gradle.libs

plugins {
    id("mcav.module")
    id("mcav.publishing")
    id("com.gradleup.shadow")
}

dependencies {
    implementation(libs.libraryOf("maven-resolver-supplier"))
}

// a plugin ships the installer alone and downloads the rest of mcav with it, so the installer is one jar whose
// dependencies are relocated into it, where they cannot clash with the plugin's
extensions.configure<McavPublishingExtension> {
    bundledJar = tasks.shadowJar
}

// the jar of the module's own classes is named apart from the bundled jar, which is the one published and tested: both
// wrote libs/mcav-installer.jar, so a build that ran the jar task and a publication failed, or published the wrong one
tasks.jar {
    archiveClassifier = "plain"
}

tasks.shadowJar {
    archiveClassifier = ""
    mergeServiceFiles()
    // Apache-2.0 asks a redistribution to keep the NOTICE of every bundled component; a plain merge keeps only the first,
    // and the transformer sees only the duplicates the jar's duplicates strategy lets through
    filesMatching(listOf("META-INF/NOTICE", "META-INF/NOTICE.txt", "META-INF/NOTICE.md")) {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }
    transform(ApacheNoticeResourceTransformer::class.java)
    // Gradle does not count the duplicates strategy of a filesMatching among the task's inputs, so a jar built without it
    // could come from the build cache
    inputs.property("noticeDuplicates", DuplicatesStrategy.INCLUDE.name)
    listOf("com.ctc", "com.google", "jakarta.inject", "org.apache", "org.codehaus", "org.eclipse", "org.objectweb", "org.slf4j").forEach { prefix ->
        relocate(prefix, "me.brandonli.mcav.libs.$prefix")
    }
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}

// the reflective injector needs java.net opened, as the error message of the injector tells users to do; the notice
// tests read the jar the build made, and so do PIT's runs of them: Gradle keeps a -D among the test task's system
// properties, which PIT is not given
val builtJar = "-Dmcav.installer.jar=" + layout.buildDirectory.file("libs/mcav-installer.jar").get().asFile.absolutePath
tasks.test {
    jvmArgs("--add-opens", "java.base/java.net=ALL-UNNAMED")
    dependsOn(tasks.shadowJar)
    jvmArgs(builtJar)
}
pitest {
    jvmArgs.add(builtJar)
}
tasks.named("pitest") {
    dependsOn(tasks.shadowJar)
}
