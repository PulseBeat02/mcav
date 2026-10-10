package me.brandonli.mcav.gradle

data class QualifiedName(val line: Int, val name: String)

object QualifiedNames {

    private val ROOTS = listOf("java", "javax", "jakarta", "jdk", "sun", "com", "org", "net", "io", "me", "uk", "de", "xyz", "it", "dev", "info")

    private val NAME = Regex("(?<![\\w.$])((?:${ROOTS.joinToString("|")})\\.(?:[a-z_][a-z0-9_]*\\.)+[A-Z][\\w$]*)")

    private val MARKER = Regex("//\\s*fqn:\\s*\\S")

    private val DECLARATION = Regex("^\\s*(package|import)\\s")

    fun find(source: String): List<QualifiedName> {
        val lines = source.split('\n')
        val code = JavaSource.mask(source).split('\n')
        val found = mutableListOf<QualifiedName>()
        code.forEachIndexed { index, line ->
            if (DECLARATION.containsMatchIn(line) || MARKER.containsMatchIn(lines[index])) {
                return@forEachIndexed
            }
            NAME.findAll(line).forEach { match -> found += QualifiedName(index + 1, match.groupValues[1]) }
        }
        return found
    }
}
