val catalogPath: String = "../gradle/libs.versions.toml"

dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files(catalogPath))
        }
    }
}
