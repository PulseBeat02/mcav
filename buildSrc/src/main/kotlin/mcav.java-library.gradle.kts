// Compiles the Java code of a module: Java from the toolchain the catalog names, every javac lint an error, Error Prone,
// and the Checker Framework's nullness checker on the production code.

import me.brandonli.mcav.gradle.libraryOf
import me.brandonli.mcav.gradle.libs
import me.brandonli.mcav.gradle.versionOf
import net.ltgt.gradle.errorprone.errorprone

plugins {
    `java-library`
    id("org.checkerframework")
    id("net.ltgt.errorprone")
}

val javaRelease = libs.versionOf("java").toInt()

// The code-quality rules of mcav that Error Prone can check, as errors: the most restrictive modifiers, no dead code,
// named constants, one declaration per line, overloads side by side and imports without wildcards.
val enforcedChecks = listOf(
    "FieldCanBeFinal",
    "FieldCanBeStatic",
    "FieldCanBeLocal",
    "MethodCanBeStatic",
    "ClassCanBeStatic",
    "PrivateConstructorForUtilityClass",
    "ConstantField",
    "ConstantPatternCompile",
    "UnusedVariable",
    "UnusedMethod",
    "UnusedNestedClass",
    "UnusedLabel",
    "RedundantOverride",
    "MultiVariableDeclaration",
    "UngroupedOverloads",
    "WildcardImport",
    "RemoveUnusedImports",
    "MissingOverride",
    "UnnecessaryAnonymousClass",
    "UnnecessaryBoxedVariable",
    "UnnecessaryBoxedAssignment",
    "StaticQualifiedUsingExpression",
    "LongLiteralLowerCaseSuffix",
    "MultipleTopLevelClasses",
    "PackageLocation",
    "DefaultLocale"
)

repositories {
    mavenCentral()
    google()
    maven("https://repo.brandonli.me/snapshots")
    maven("https://maven.maxhenkel.de/repository/public")
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
    maven("https://repo.codemc.io/repository/maven-releases/")
    maven("https://api.modrinth.com/maven") {
        content {
            includeGroup("maven.modrinth")
        }
    }
}

dependencies {
    errorprone(libs.libraryOf("errorprone-core"))
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(javaRelease)
}

tasks.withType<JavaCompile>().configureEach {
    options.release = javaRelease
    options.encoding = "UTF-8"
    // the Checker Framework does not claim the annotations it reads, which javac would report as processing notes
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all", "-Xlint:-processing", "-Werror"))
    options.isFork = true
    options.forkOptions.memoryMaximumSize = "4g"
    // the Checker Framework plugin leaves this export out for recent Checker Framework versions
    // (https://github.com/typetools/checker-framework/issues/7241); a provider keeps the ones Error Prone adds
    options.forkOptions.jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("--add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED")
    })
    options.errorprone {
        disableWarningsInGeneratedCode = true
        error(*enforcedChecks.toTypedArray())
    }
}

checkerFramework {
    version = libs.versionOf("checker-framework")
    checkers = listOf("org.checkerframework.checker.nullness.NullnessChecker")
    // the tests pass nulls on purpose to check the preconditions
    excludeTests = true
    // the checker warns about a class that takes 45 seconds of wall-clock time, which -Werror turns into a failed build
    // on a loaded two-core VM although the class takes under a second here; ten minutes still flags a real blow-up
    extraJavacArgs = stubsArgument() + "-AslowTypecheckingSeconds=600"
}

tasks.processResources {
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    filteringCharset = "UTF-8"
}

// javac keeps only the last value of a repeated -A option, so the stubs of the module and of the repository go into one
fun stubsArgument(): List<String> {
    val folders = listOf(project.file("checker-framework"), rootProject.file("checker-framework")).filter { it.isDirectory }
    return if (folders.isEmpty()) emptyList() else listOf("-Astubs=" + folders.joinToString(File.pathSeparator))
}
