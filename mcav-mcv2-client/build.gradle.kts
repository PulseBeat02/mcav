plugins {
    id("mcav.mcv2-client")
}

// the game, the loaders and Iris are there when the mod runs, so the jars bundle none of them
dependencies {
    compileOnly(libs.bundles.mcv2.client)
    compileOnly(variantOf(libs.neoforge.core) { classifier("universal") })
    compileOnly(variantOf(libs.neoforge.mergetool) { classifier("api") })
    testImplementation(libs.bundles.mcv2.client)
    testImplementation(variantOf(libs.neoforge.core) { classifier("universal") })
    testImplementation(variantOf(libs.neoforge.mergetool) { classifier("api") })
    testImplementation(libs.bundles.minecraft.client.libraries)
}
