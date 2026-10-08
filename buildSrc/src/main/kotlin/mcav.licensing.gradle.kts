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
