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

package me.brandonli.mcav.gradle

import com.sun.source.tree.BindingPatternTree
import com.sun.source.tree.CatchTree
import com.sun.source.tree.ClassTree
import com.sun.source.tree.CompilationUnitTree
import com.sun.source.tree.EnhancedForLoopTree
import com.sun.source.tree.ForLoopTree
import com.sun.source.tree.LambdaExpressionTree
import com.sun.source.tree.MethodTree
import com.sun.source.tree.Tree
import com.sun.source.tree.TryTree
import com.sun.source.tree.VariableTree
import com.sun.source.util.JavacTask
import com.sun.source.util.SourcePositions
import com.sun.source.util.TreePathScanner
import com.sun.source.util.Trees
import java.io.File
import java.net.URI
import javax.lang.model.element.Modifier
import javax.tools.JavaCompiler
import javax.tools.JavaFileObject
import javax.tools.SimpleJavaFileObject
import javax.tools.StandardJavaFileManager
import javax.tools.ToolProvider

/**
 * Finds the variables in Java source whose names say nothing about what they hold.
 *
 * A name says nothing when it is one letter or a common abbreviation, such as `tmp`, `sb` or `ctx`. The JDK compiler
 * parses the sources, so fields, parameters, locals, loop variables, resources, record components and pattern variables
 * all count.
 */
object VariableNames {

    private const val SOURCE_URI: String = "string:///Source.java"

    private const val ANONYMOUS_CLASS: String = "<anonymous>"
    private const val PACKAGE_SEPARATOR: String = "."
    private const val MEMBER_SEPARATOR: String = "#"

    private val ABBREVIATIONS: Set<String> =
        setOf("buf", "tmp", "cfg", "idx", "cnt", "val", "res", "sb", "ctx", "mgr", "str", "arr", "len", "num")

    /**
     * Returns the undescriptive variable names in [source], in source order.
     *
     * @param source the Java source of one compilation unit
     * @return the undescriptive names
     */
    fun find(source: String): List<UndescriptiveName> {
        val file: SimpleJavaFileObject = object : SimpleJavaFileObject(URI.create(SOURCE_URI), JavaFileObject.Kind.SOURCE) {
            override fun getCharContent(ignoreEncodingErrors: Boolean): CharSequence {
                return source
            }
        }
        val names: Map<URI, List<UndescriptiveName>> = this.find(listOf(file))
        return names.values.single()
    }

    /**
     * Returns the undescriptive variable names in each of [files].
     *
     * @param files the Java sources
     * @return the undescriptive names of each file
     */
    fun findInFiles(files: List<File>): Map<File, List<UndescriptiveName>> {
        val compiler: JavaCompiler = ToolProvider.getSystemJavaCompiler()
        val fileManager: StandardJavaFileManager = compiler.getStandardFileManager(null, null, Charsets.UTF_8)
        return fileManager.use { this.findInFiles(files, it) }
    }

    private fun find(files: List<JavaFileObject>): Map<URI, List<UndescriptiveName>> {
        val compiler: JavaCompiler = ToolProvider.getSystemJavaCompiler()
        val task: JavacTask = compiler.getTask(null, null, { }, listOf("-proc:none"), null, files) as JavacTask
        val positions: SourcePositions = Trees.instance(task).sourcePositions
        return task.parse().associate { it.sourceFile.toUri() to Scanner(it, positions).names() }
    }

    private fun findInFiles(files: List<File>, manager: StandardJavaFileManager): Map<File, List<UndescriptiveName>> {
        val objects: List<JavaFileObject> = manager.getJavaFileObjectsFromFiles(files).toList()
        val byUri: Map<URI, File> = files.associateBy { it.toPath().toUri() }
        val names: Map<URI, List<UndescriptiveName>> = this.find(objects)
        return names.mapKeys { (uri, _) -> byUri[uri] ?: File(uri) }
    }

    private class Scanner(
        private val unit: CompilationUnitTree,
        private val positions: SourcePositions,
    ) : TreePathScanner<Unit, Unit>() {

        private val found: MutableList<UndescriptiveName> = mutableListOf<UndescriptiveName>()

        fun names(): List<UndescriptiveName> {
            this.scan(this.unit, Unit)

            return this.found
        }

        override fun visitVariable(node: VariableTree, unused: Unit?): Unit? {
            val name: String = node.name.toString()
            if (this.isUndescriptive(name)) {
                val position: Long = this.positions.getStartPosition(this.unit, node)
                val line: Long = this.unit.lineMap.getLineNumber(position)
                val member: String = "${this.owner()}$MEMBER_SEPARATOR$name"
                this.found += UndescriptiveName(line, name, this.kind(node), member)
            }

            return super.visitVariable(node, unused)
        }

        private fun isUndescriptive(name: String): Boolean {
            return name in ABBREVIATIONS || (name.length == 1 && name[0].isLetter())
        }

        private fun owner(): String {
            val types: List<String> = generateSequence(this.currentPath) { it.parentPath }
                .map { it.leaf }
                .filterIsInstance<ClassTree>()
                .map { it.simpleName.toString().ifEmpty { ANONYMOUS_CLASS } }
                .toList()
                .asReversed()
            val packageName: String? = this.unit.packageName?.toString()
            return (listOfNotNull(packageName) + types).joinToString(PACKAGE_SEPARATOR)
        }

        private fun kind(node: VariableTree): String {
            val parent: Tree = this.currentPath.parentPath.leaf
            return when {
                parent is ClassTree && parent.kind == Tree.Kind.RECORD && Modifier.STATIC !in node.modifiers.flags -> "record component"
                parent is ClassTree -> "field"
                parent is MethodTree -> "parameter"
                parent is LambdaExpressionTree -> "lambda parameter"
                parent is CatchTree -> "catch parameter"
                parent is ForLoopTree || parent is EnhancedForLoopTree -> "loop variable"
                parent is TryTree -> "resource"
                parent is BindingPatternTree -> "pattern variable"
                else -> "local variable"
            }
        }
    }
}
