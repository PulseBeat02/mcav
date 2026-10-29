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
package me.brandonli.mcav.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(120)
final class GeneratedMediaTest {

  private static final String FIXTURE_SOURCE_FILE = "FixtureWriter.java";
  private static final String OUTPUT_FILE_NAME = "video.mp4";
  private static final String TEMPORARY_FILE_PREFIX = "mcav-generating-";

  private static final String BINARY_DIRECTORY = "bin";
  private static final String WINDOWS_JAVA_EXECUTABLE = "java.exe";
  private static final String JAVA_EXECUTABLE = "java";

  private static final String SUCCESS_MODE = "success";

  private static final String COMPLETED_CONTENT = "completed media";

  @TempDir
  private Path directory;

  @BeforeEach
  void compileFixtureOutsideTheSubprocessDeadline() throws Exception {
    final Path source = this.directory.resolve(FIXTURE_SOURCE_FILE);
    Files.writeString(
      source,
      """
      import java.nio.file.*;
      class FixtureWriter {
        public static void main(String[] args) throws Exception {
          Path output = Path.of(args[1]);
          if (!output.toString().endsWith(".mp4")) throw new AssertionError("format extension was lost!");
          Files.writeString(output, args[0].equals("success") ? "completed media" : "partial media");

          if (args[0].equals("failure")) { System.err.println("fixture encoder diagnostic"); System.exit(7); }
          if (args[0].equals("timeout")) { Thread.sleep(30_000L); }
        }
      }

      """
    );
    final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    assertNotNull(compiler, "fixture tests run on the build JDK");

    final String target = this.directory.toString();
    final String sourcePath = source.toString();
    final int code = compiler.run(null, null, null, "-d", target, sourcePath);
    assertEquals(0, code);
  }

  private List<String> command(final Path temporary, final String mode) {
    final String home = System.getProperty("java.home");
    final String executable = File.separatorChar == '\\' ? WINDOWS_JAVA_EXECUTABLE : JAVA_EXECUTABLE;
    final String java = Path.of(home, BINARY_DIRECTORY, executable).toString();
    final String classes = this.directory.toString();
    return List.of(java, "-cp", classes, "FixtureWriter", mode, temporary.toString());
  }

  private void assertNoPartialFiles() throws Exception {
    try (final Stream<Path> paths = Files.list(this.directory)) {
      final boolean hasLeftovers = paths.anyMatch(path -> path.getFileName().toString().startsWith(TEMPORARY_FILE_PREFIX));
      assertFalse(hasLeftovers, "temporary media and process logs are removed");
    }
  }

  @Test
  void shouldReplaceUntrustedExistingFilesAndCacheOnlySuccessfulGeneration() throws Exception {
    final Path output = this.directory.resolve(OUTPUT_FILE_NAME);
    Files.writeString(output, "partial from a failed older attempt");
    final AtomicInteger invocations = new AtomicInteger();
    final AtomicReference<Path> temporary = new AtomicReference<>();
    final Path generated = GeneratedMedia.generate(output, path -> this.recordSuccessfulCommand(path, invocations, temporary));
    final String content = Files.readString(generated);
    assertEquals(COMPLETED_CONTENT, content);
    assertEquals(output.toAbsolutePath(), generated);

    final Path staged = temporary.get();
    assertNotNull(staged);

    final boolean stagedAtOutput = staged.equals(output);
    assertFalse(stagedAtOutput, "the encoder writes into a temporary sibling");

    final Path cached = GeneratedMedia.generate(output, _ -> this.rejectRegeneration());
    final int commands = invocations.get();
    assertEquals(generated, cached);
    assertEquals(1, commands);

    this.assertNoPartialFiles();
  }

  private List<String> recordSuccessfulCommand(final Path path, final AtomicInteger invocations, final AtomicReference<Path> temporary) {
    invocations.incrementAndGet();
    temporary.set(path);
    return this.command(path, SUCCESS_MODE);
  }

  private List<String> rejectRegeneration() {
    throw new AssertionError("successfully generated media must be reused!");
  }

  @Test
  void shouldPreserveTheOldDestinationAfterAFailedEncodingAndRetryOnTheNextRequest() throws Exception {
    final Path output = this.directory.resolve(OUTPUT_FILE_NAME);
    Files.writeString(output, "old complete media");
    final UncheckedIOException thrown = assertThrows(UncheckedIOException.class, () ->
      GeneratedMedia.generate(output, path -> this.command(path, "failure"))
    );
    final String message = thrown.getMessage();
    final boolean namesExitCode = message.contains("exit code 7");
    assertTrue(namesExitCode);

    final boolean namesDiagnostic = message.contains("fixture encoder diagnostic");
    assertTrue(namesDiagnostic);

    final String kept = Files.readString(output);
    assertEquals("old complete media", kept, "failed temporary output is never published");

    this.assertNoPartialFiles();
    final Path retried = GeneratedMedia.generate(output, path -> this.command(path, SUCCESS_MODE));
    final String content = Files.readString(retried);
    assertEquals(COMPLETED_CONTENT, content);

    this.assertNoPartialFiles();
  }

  @Test
  void shouldBoundATimedOutEncodingAndNotPublishItsPartialOutput() throws Exception {
    final Path output = this.directory.resolve(OUTPUT_FILE_NAME);
    final Duration timeout = Duration.ofSeconds(2);
    final long before = System.nanoTime();
    final UncheckedIOException thrown = assertThrows(UncheckedIOException.class, () ->
      GeneratedMedia.generate(output, path -> this.command(path, "timeout"), timeout)
    );
    final long elapsed = System.nanoTime() - before;
    final String message = thrown.getMessage();
    final boolean namesDeadline = message.contains("exceeded");
    assertTrue(namesDeadline);

    final boolean boundedWait = elapsed < 6_000_000_000L;
    assertTrue(boundedWait, "the deadline plus bounded process cleanup must not wait for the 30 second fixture");

    final boolean published = Files.exists(output);
    assertFalse(published);

    this.assertNoPartialFiles();
  }
}
