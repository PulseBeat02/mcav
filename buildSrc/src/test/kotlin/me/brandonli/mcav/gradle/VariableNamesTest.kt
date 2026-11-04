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

import java.io.File
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class VariableNamesTest {

    private companion object {

        const val SOURCE_FILE: String = "Sample.java"
        const val DETOUR_PATH: String = "sub/../Sample.java"
    }

    @Test
    fun shouldAcceptDescriptiveNamesTypeParametersAndTheUnnamedVariable(): Unit {
        val source: String = """
            package example;
            final class Pairs<K, V> {
              private final int count;
              <T> T first(final List<T> values, final int index) {
                for (final T value : values) {
                  return value;
                }
                values.forEach(_ -> { });
                return null;
              }
            }
        """.trimIndent()
        val found: List<UndescriptiveName> = VariableNames.find(source)
        assertEquals(emptyList<UndescriptiveName>(), found)
    }

    @Test
    fun shouldReportEveryKindOfDeclarationNamedByOneLetter(): Unit {
        val source: String = """
            package example;
            final class Shapes {
              private int w;
              record Point(int x, int height) {}
              void draw(final int y) throws Exception {
                for (int i = 0; i < 3; i++) {}
                for (final Object o : List.of()) {}
                try (final Stream s = open()) {
                } catch (final IOException e) {
                }
                Runnable r = () -> {};
                Function<Integer, Integer> f = n -> n;
                if (this instanceof Object p) {}
              }
            }
        """.trimIndent()
        val kinds: List<String> = VariableNames.find(source).map(this::nameAndKindOf)
        assertEquals(
            listOf(
                "w: field",
                "x: record component",
                "y: parameter",
                "i: loop variable",
                "o: loop variable",
                "s: resource",
                "e: catch parameter",
                "r: local variable",
                "f: local variable",
                "n: lambda parameter",
                "p: pattern variable",
            ),
            kinds,
        )
    }

    @Test
    fun shouldReportTheShortFormsTheRulesListButNotWordsThatStartWithThem(): Unit {
        val source: String = """
            final class Buffers {
              void fill(final byte[] buf, final int idx) {
                final int bufferSize = buf.length;
                final StringBuilder sb = new StringBuilder();
                final String string = sb.toString();
              }
            }
        """.trimIndent()
        val names: List<String> = VariableNames.find(source).map { it.name }
        assertEquals(listOf("buf", "idx", "sb"), names)
    }

    @Test
    fun shouldNameTheMemberAsThePackageTheTypesAndTheName(): Unit {
        val source: String = """
            package me.example;
            public final class Frame {
              public record Leaf(int x, int size) {}
              void run() {
                new Object() {
                  int q;
                };
              }
            }
        """.trimIndent()
        val found: List<UndescriptiveName> = VariableNames.find(source)
        val members: List<String> = found.map { it.member }
        assertEquals(listOf("me.example.Frame.Leaf#x", "me.example.Frame.<anonymous>#q"), members)

        val lines: List<Long> = found.map { it.line }
        assertEquals(listOf(3L, 6L), lines)
    }

    @Test
    fun shouldReportAOneLetterStaticFieldOfARecordAsAField(): Unit {
        val source: String = """
            package example;
            record Point(int width) {
              static int x;
            }
        """.trimIndent()

        val kinds: List<String> = VariableNames.find(source).map(this::nameAndKindOf)
        assertEquals(listOf("x: field"), kinds)
    }

    @Test
    fun shouldNotReportAOneCharacterNameThatIsNoLetter(): Unit {
        val source: String = """
            package example;
            final class Dollars {
              private final int $ = 1;
            }
        """.trimIndent()

        val found: List<UndescriptiveName> = VariableNames.find(source)
        assertEquals(emptyList<UndescriptiveName>(), found)
    }

    @Test
    fun shouldKeyAFileByItsNormalizedPathWhenTheGivenPathTakesADetour(@TempDir directory: Path): Unit {
        val file: File = directory.resolve(SOURCE_FILE).toFile()
        file.writeText("final class Sample {\n  private int x;\n}\n")

        val detour: File = directory.resolve(DETOUR_PATH).toFile()

        val found: Map<File, List<UndescriptiveName>> = VariableNames.findInFiles(listOf(detour))
        val files: Set<File> = found.keys
        assertEquals(setOf(file), files)
    }

    private fun nameAndKindOf(variable: UndescriptiveName): String {
        return "${variable.name}: ${variable.kind}"
    }
}
