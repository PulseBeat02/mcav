// the build logic reads the versions of the plugins it applies from the version catalog of the main build
dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}
