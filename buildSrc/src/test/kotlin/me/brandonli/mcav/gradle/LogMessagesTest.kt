package me.brandonli.mcav.gradle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LogMessagesTest {

    @Test
    fun acceptsCommentsAroundAConstantMessage() {
        for (source in listOf(
            "LOGGER.info(/* why this message */ STARTED);",
            "LOGGER.info(STARTED /* why this message */);",
            "LOGGER.info(// why this message\n STARTED);",
        )) {
            assertEquals(emptyList<InlineLogMessage>(), LogMessages.find(source), source)
        }
    }

    @Test
    fun doesNotTreatQuotedCommentTextAsStringConcatenation() {
        val source = "LOGGER.info(STARTED, count + 1 /* \"units\" */);"
        assertEquals(emptyList<InlineLogMessage>(), LogMessages.find(source))
    }

    @Test
    fun stillRejectsLiteralConcatenationBesideAComment() {
        val source = "LOGGER.info(/* explanation */ \"\" + STARTED);"
        assertEquals(listOf(InlineLogMessage(1, "LOGGER.info")), LogMessages.find(source))
    }

    @Test
    fun acceptsAMessageInAConstant() {
        val source = """
            final class Example {
              private static final String FAILED_TO_START = "Failed to start {}";
              void run() {
                LOGGER.error(FAILED_TO_START, name, exception);
                LOGGER.info(Messages.STARTED);
                this.logger.warn(NOT_STOPPED, thread, 50L);
              }
            }
        """.trimIndent()
        assertEquals(emptyList<InlineLogMessage>(), LogMessages.find(source))
    }

    @Test
    fun reportsAMessageWrittenInline() {
        val source = """
            final class Example {
              void run() {
                LOGGER.info("Started {}", name);
              }
            }
        """.trimIndent()
        assertEquals(listOf(InlineLogMessage(3, "LOGGER.info")), LogMessages.find(source))
    }

    @Test
    fun reportsEveryLevelOnEveryKindOfLogger() {
        val source = listOf(
            "log.trace(\"a\");",
            "logger.debug(\"b\");",
            "LOG.info(\"c\");",
            "AUDIT_LOGGER.warn(\"d\");",
            "pluginLogger.error(\"e\");",
        ).joinToString("\n")
        assertEquals(listOf(1, 2, 3, 4, 5), LogMessages.find(source).map { it.line })
    }

    @Test
    fun reportsAMessageThatIsNotAConstant() {
        val source = listOf(
            "LOGGER.error(message, error);",
            "LOGGER.warn(prefix() + NAME);",
            "LOGGER.info(Failed.name());",
        ).joinToString("\n")
        assertEquals(listOf(1, 2, 3), LogMessages.find(source).map { it.line })
    }

    @Test
    fun reportsAnArgumentThatJoinsStrings() {
        val source = listOf(
            "LOGGER.warn(FAILED, \"at \" + place);",
            "LOGGER.warn(FAILED, count + 1, String.join(\",\", names));",
            "LOGGER.warn(FAILED, place.concat(\"x\" + y));",
        ).joinToString("\n")
        assertEquals(listOf(1), LogMessages.find(source).map { it.line })
    }

    @Test
    fun readsACallAcrossLinesAndInsideALambda() {
        val source = """
            final class Example {
              void run() {
                budget.log(() -> LOGGER.warn(
                  "The browser could not load {}: {}",
                  url,
                  text
                ), Example::skipped);
              }
            }
        """.trimIndent()
        assertEquals(listOf(InlineLogMessage(3, "LOGGER.warn")), LogMessages.find(source))
    }

    @Test
    fun ignoresCallsThatAreNotLogCalls() {
        val source = listOf(
            "verify(logger).warn(\"checked by a test\");",
            "counter.info(\"not a logger\");",
            "LOGGER.isDebugEnabled();",
            "log.info();",
            "// LOGGER.info(\"in a comment\");",
            "String text = \"LOGGER.info(\\\"in a string\\\")\";",
        ).joinToString("\n")
        assertEquals(emptyList<InlineLogMessage>(), LogMessages.find(source))
    }

    @Test
    fun readsParenthesesAndCommasInLiteralsAsText() {
        val source = "LOGGER.info(STARTED, \"(,\", ')', name);\nLOGGER.info(\"x\");"
        assertEquals(listOf(2), LogMessages.find(source).map { it.line })
    }
}
