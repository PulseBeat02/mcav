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

extensions.configure<McavPublishingExtension> {
    bundledJar = tasks.shadowJar
}

tasks.jar {
    archiveClassifier = "plain"
}

tasks.shadowJar {
    archiveClassifier = ""
    mergeServiceFiles()
    filesMatching(listOf("META-INF/NOTICE", "META-INF/NOTICE.txt", "META-INF/NOTICE.md")) {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }
    transform(ApacheNoticeResourceTransformer::class.java)
    inputs.property("noticeDuplicates", DuplicatesStrategy.INCLUDE.name)
    listOf("com.ctc", "com.google", "jakarta.inject", "org.apache", "org.codehaus", "org.eclipse", "org.objectweb", "org.slf4j").forEach { prefix ->
        relocate(prefix, "me.brandonli.mcav.libs.$prefix")
    }
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}

val builtJar = "-Dmcav.installer.jar=" + layout.buildDirectory.file("libs/mcav-installer.jar").get().asFile.absolutePath
tasks.test {
    jvmArgs("--add-opens", "java.base/java.net=ALL-UNNAMED")
    dependsOn(tasks.shadowJar)
    jvmArgs(builtJar)
}
tasks.named("pitest") {
    dependsOn(tasks.shadowJar)
}
