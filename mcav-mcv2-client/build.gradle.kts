plugins {
    id("mcav.mcv2-client")
}

dependencies {
    compileOnly(libs.bundles.mcv2.client)
    compileOnly(variantOf(libs.neoforge.core) { classifier("universal") })
    compileOnly(variantOf(libs.neoforge.mergetool) { classifier("api") })
    testImplementation(libs.bundles.mcv2.client)
    testImplementation(variantOf(libs.neoforge.core) { classifier("universal") })
    testImplementation(variantOf(libs.neoforge.mergetool) { classifier("api") })
    testImplementation(libs.bundles.minecraft.client.libraries)
}
