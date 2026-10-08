plugins {
    id("mcav.mod")
}

dependencies {
    compileOnly(libs.bundles.mod)
    compileOnly(variantOf(libs.neoforge.core) { classifier("universal") })
    compileOnly(variantOf(libs.neoforge.mergetool) { classifier("api") })
    testImplementation(libs.bundles.mod)
    testImplementation(variantOf(libs.neoforge.core) { classifier("universal") })
    testImplementation(variantOf(libs.neoforge.mergetool) { classifier("api") })
    testImplementation(libs.bundles.minecraft.client.libraries)
}
