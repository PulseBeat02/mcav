package me.brandonli.mcav.gradle

/** Whether the build runs on Windows, whose executables end in `.exe`. */
val isWindows: Boolean = System.getProperty("os.name").lowercase().contains("windows")

/** The `java` executable of the JDK installed at [javaHome]. */
fun javaExecutable(javaHome: String): String = javaHome + "/bin/java" + if (isWindows) ".exe" else ""
