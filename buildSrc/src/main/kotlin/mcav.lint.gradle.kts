// Source lints of a module's Java code, part of `check`. `qualifiedNames` fails on a type named by its fully qualified
// name instead of an import; a line where two types of one simple name meet keeps the name and is marked `// fqn: <why>`.
// `logMessages` fails on a log call whose message is not a named constant.

import me.brandonli.mcav.gradle.LogMessagesTask
import me.brandonli.mcav.gradle.QualifiedNamesTask

plugins {
    base
}

val javaSources = layout.projectDirectory.dir("src").asFileTree.matching { include("*/java/**/*.java") }

val qualifiedNames = tasks.register<QualifiedNamesTask>("qualifiedNames") {
    description = "Fails on fully qualified type names in the Java code of every source set of this module"
    group = "verification"
    sources.from(javaSources)
    report = layout.buildDirectory.file("reports/qualified-names.txt")
}

val logMessages = tasks.register<LogMessagesTask>("logMessages") {
    description = "Fails on log calls whose message is not a constant in the Java code of every source set of this module"
    group = "verification"
    sources.from(javaSources)
    report = layout.buildDirectory.file("reports/log-messages.txt")
}

tasks.check {
    dependsOn(qualifiedNames, logMessages)
}
