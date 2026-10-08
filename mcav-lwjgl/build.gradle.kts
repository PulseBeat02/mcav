plugins {
    id("mcav.module")
    id("mcav.publishing")
}

dependencies {
    api(libs.bundles.lwjgl.opengl)
    compileOnlyApi(project(":mcav-common"))
    testImplementation(project(":mcav-common"))
    testImplementation(libs.lwjgl.glfw)
    lwjglNatives()?.let { natives ->
        listOf(libs.lwjgl.asProvider(), libs.lwjgl.opengl, libs.lwjgl.glfw).forEach { library ->
            testRuntimeOnly(variantOf(library) { classifier(natives) })
        }
    }
}

fun lwjglNatives(): String? {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val x64 = arch == "amd64" || arch == "x86_64"
    val arm64 = arch == "aarch64" || arch == "arm64"
    return when {
        // "darwin" contains "win", so macOS is recognized first
        os.contains("mac") || os.contains("darwin") -> when {
            arm64 -> "natives-macos-arm64"
            x64 -> "natives-macos"
            else -> null
        }
        os.contains("win") -> when {
            arm64 -> "natives-windows-arm64"
            x64 -> "natives-windows"
            arch == "x86" || arch == "i386" || arch == "i686" -> "natives-windows-x86"
            else -> null
        }
        os.contains("freebsd") -> if (x64) "natives-freebsd" else null
        os.contains("linux") -> when {
            x64 -> "natives-linux"
            arm64 -> "natives-linux-arm64"
            arch == "arm" || arch == "arm32" || arch.startsWith("armv7") -> "natives-linux-arm32"
            arch == "ppc64le" -> "natives-linux-ppc64le"
            arch == "riscv64" -> "natives-linux-riscv64"
            else -> null
        }
        else -> null
    }
}
