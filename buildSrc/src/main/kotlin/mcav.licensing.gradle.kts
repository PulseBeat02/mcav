// Every distributed jar keeps mcav's full licence and third-party notices, including source and documentation jars.
// A distinct licence name preserves the licences that a shadow jar's dependencies already put in META-INF/LICENSE.

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
