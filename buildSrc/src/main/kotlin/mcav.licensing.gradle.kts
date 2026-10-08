// A distinct license name preserves dependency licenses already stored at META-INF/LICENSE in shadow jars.

plugins {
    java
}

tasks.withType<Jar>().configureEach {
    from(rootProject.file("LICENSE")) {
        into("META-INF")
        rename { "LICENSE-MCAV" }
    }
    from(rootProject.file("THIRD-PARTY-NOTICES.md")) {
        into("META-INF")
    }
}
