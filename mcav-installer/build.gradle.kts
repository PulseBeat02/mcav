plugins {
    id("mcav.module")
    id("mcav.publishing")
    id("com.gradleup.shadow")
}

dependencies {
    implementation(libs.maven.resolver.supplier)
}

// a plugin ships the installer alone and downloads the rest of mcav with it, so the installer is one jar whose
// dependencies are relocated into it, where they cannot clash with the plugin's
mcavPublishing {
    bundledJar = tasks.shadowJar
}

tasks.shadowJar {
    archiveClassifier = ""
    mergeServiceFiles()
    listOf("com.ctc", "jakarta.inject", "org.apache", "org.codehaus", "org.eclipse", "org.slf4j").forEach { prefix ->
        relocate(prefix, "me.brandonli.mcav.libs.$prefix")
    }
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}

// the reflective injector needs java.net opened, as the error message of the injector tells users to do
tasks.test {
    jvmArgs("--add-opens", "java.base/java.net=ALL-UNNAMED")
}
