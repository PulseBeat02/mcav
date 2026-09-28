package me.brandonli.mcav.gradle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class JavaSourceTest {

    @Test
    fun masksCommentsAndLiteralsAndKeepsEveryOffset() {
        val quotes = "\"\"\""
        val source = listOf(
            "int a = 1; // note",
            "/* block",
            "   end */ call(\"text, (\", 'c');",
            "String b = $quotes",
            "  line \\$quotes",
            "  $quotes;",
        ).joinToString("\n")
        val expected = listOf(
            "int a = 1;" + " ".repeat(8),
            " ".repeat(8),
            " ".repeat(9) + " call(" + " ".repeat(9) + ", " + " ".repeat(3) + ");",
            "String b = " + " ".repeat(3),
            " ".repeat(11),
            " ".repeat(5) + ";",
        ).joinToString("\n")
        assertEquals(expected, JavaSource.mask(source))
    }

    @Test
    fun endsALiteralThatLacksItsQuoteAtTheEndOfTheLine() {
        assertEquals("  \nnext", JavaSource.mask("\"a\nnext"))
    }
}
