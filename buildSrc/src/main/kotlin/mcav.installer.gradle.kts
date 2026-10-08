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

// The installer ships inside other plugins, whose dependencies must not clash with its bundled classes.
extensions.configure<McavPublishingExtension> {
    bundledJar = tasks.shadowJar
}

// The plain jar must not overwrite the bundled jar that publication and tests consume.
tasks.jar {
    archiveClassifier = "plain"
}

tasks.shadowJar {
    archiveClassifier = ""
    mergeServiceFiles()
    // Apache-2.0 requires every bundled NOTICE; the transformer needs duplicates passed through to merge them.
    filesMatching(listOf("META-INF/NOTICE", "META-INF/NOTICE.txt", "META-INF/NOTICE.md")) {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }
    transform(ApacheNoticeResourceTransformer::class.java)
    // Gradle omits filesMatching duplicates strategies from cache keys.
    inputs.property("noticeDuplicates", DuplicatesStrategy.INCLUDE.name)
    listOf("com.ctc", "com.google", "jakarta.inject", "org.apache", "org.codehaus", "org.eclipse", "org.objectweb", "org.slf4j").forEach { prefix ->
        relocate(prefix, "me.brandonli.mcav.libs.$prefix")
    }
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}

// The reflective injector requires java.net to be opened.
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
