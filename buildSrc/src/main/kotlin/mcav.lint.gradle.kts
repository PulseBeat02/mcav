/*
 * This file is part of mcav, a media playback library for Java
 * Copyright (C) Brandon Li <https://brandonli.me/>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

import me.brandonli.mcav.gradle.CommentFormTask
import me.brandonli.mcav.gradle.LogMessagesTask
import me.brandonli.mcav.gradle.QualifiedNamesTask
import me.brandonli.mcav.gradle.VariableNamesTask

plugins {
    base
}

val sourcePath: String = "src"
val javaSourcePattern: String = "*/java/**/*.java"

val qualifiedNamesReportPath: String = "reports/qualified-names.txt"
val logMessagesReportPath: String = "reports/log-messages.txt"
val variableNamesReportPath: String = "reports/variable-names.txt"
val commentFormReportPath: String = "reports/comment-form.txt"

val javaSources: FileTree = layout.projectDirectory.dir(sourcePath).asFileTree.matching { include(javaSourcePattern) }

val qualifiedNames: TaskProvider<QualifiedNamesTask> = tasks.register<QualifiedNamesTask>("qualifiedNames") {
    this.group = "verification"
    this.description = "Fails on fully qualified type names in the Java code of every source set of this module"
    this.sources.from(javaSources)
    this.report = layout.buildDirectory.file(qualifiedNamesReportPath)
}

val logMessages: TaskProvider<LogMessagesTask> = tasks.register<LogMessagesTask>("logMessages") {
    this.group = "verification"
    this.description = "Fails on log calls that join strings with + in the Java code of every source set of this module"
    this.sources.from(javaSources)
    this.report = layout.buildDirectory.file(logMessagesReportPath)
}

val variableNames: TaskProvider<VariableNamesTask> = tasks.register<VariableNamesTask>("variableNames") {
    this.group = "verification"
    this.description = "Fails on one-letter and abbreviated variable names in the Java code of every source set of this module"
    this.sources.from(javaSources)
    this.report = layout.buildDirectory.file(variableNamesReportPath)
}

val commentForm: TaskProvider<CommentFormTask> = tasks.register<CommentFormTask>("commentForm") {
    this.group = "verification"
    this.description = "Fails on comments written as runs of // lines in the Java code of every source set of this module"
    this.sources.from(javaSources)
    this.report = layout.buildDirectory.file(commentFormReportPath)
}

tasks.check {
    this.dependsOn(qualifiedNames, logMessages, variableNames, commentForm)
}
