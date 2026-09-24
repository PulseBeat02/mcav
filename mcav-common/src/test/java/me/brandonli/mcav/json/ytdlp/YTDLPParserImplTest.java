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
package me.brandonli.mcav.json.ytdlp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonSyntaxException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import me.brandonli.mcav.capability.Capability;
import me.brandonli.mcav.capability.CapabilityGuard;
import me.brandonli.mcav.capability.installer.ytdlp.YTDLPInstaller;
import me.brandonli.mcav.json.ytdlp.format.Format;
import me.brandonli.mcav.json.ytdlp.format.URLParseDump;
import me.brandonli.mcav.media.source.uri.UriSource;
import me.brandonli.mcav.utils.runtime.CommandTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Tests {@link YTDLPParserImpl} with canned yt-dlp output instead of the real executable.
 */
final class YTDLPParserImplTest {

  private static final String URL = "https://www.youtube.com/watch?v=dQw4w9WgXcQ";
  private static final Path EXECUTABLE = Path.of("tools", "yt-dlp");
  private static final Duration TIMEOUT = Duration.ofMinutes(2);

  private static UriSource source() {
    final URI uri = URI.create(URL);
    return UriSource.uri(uri);
  }

  private static String fixture() throws IOException {
    try (final InputStream stream = YTDLPParserImplTest.class.getResourceAsStream("/ytdlp/video.json")) {
      assertNotNull(stream, "the fixture must be on the test classpath");
      final byte[] bytes = stream.readAllBytes();
      final String content = new String(bytes, StandardCharsets.UTF_8);
      return content.strip();
    }
  }

  private static CommandTask task(final int exitCode, final String output, final String errorOutput) throws IOException {
    final CommandTask task = Mockito.mock(CommandTask.class);
    Mockito.when(task.run(ArgumentMatchers.any(Duration.class))).thenReturn(exitCode);
    Mockito.when(task.getOutput()).thenReturn(output);
    Mockito.when(task.getErrorOutput()).thenReturn(errorOutput);
    return task;
  }

  private static YTDLPParserImpl parser(final CommandTask task, final AtomicReference<String[]> command) {
    final Function<String[], CommandTask> factory = arguments -> {
      command.set(arguments);
      return task;
    };
    return new YTDLPParserImpl(() -> EXECUTABLE, factory);
  }

  private static YTDLPParseException assertParseFails(final String output) throws IOException {
    final CommandTask task = task(0, output, "");
    final AtomicReference<String[]> command = new AtomicReference<>();
    final YTDLPParserImpl parser = parser(task, command);
    final UriSource source = source();
    return assertThrows(YTDLPParseException.class, () -> parser.parse(source));
  }

  @Test
  void parsesTheFirstDocumentWithTheExpectedCommandLine() throws IOException {
    final String fixture = fixture();
    final String output = fixture + "\n{\"id\": \"second\", \"title\": \"Second Video\"}\n";
    final CommandTask task = task(0, output, "");
    final AtomicReference<String[]> command = new AtomicReference<>();
    final YTDLPParserImpl parser = parser(task, command);
    final UriSource source = source();
    final URLParseDump dump = parser.parse(source, "--cookies-from-browser", "firefox");
    final String executable = EXECUTABLE.toString();
    // the URL follows "--", so yt-dlp can never read it as an option, and only the first playlist entry is resolved
    final String[] expected = {
      executable,
      "--dump-json",
      "--no-cache-dir",
      "--no-playlist",
      "--playlist-items",
      "1",
      "--no-warnings",
      "--cookies-from-browser",
      "firefox",
      "--",
      URL,
    };
    final String[] actual = command.get();
    final List<Format> formats = dump.formats;
    assertArrayEquals(expected, actual);
    assertEquals("dQw4w9WgXcQ", dump.id);
    assertEquals("Example Video", dump.title);
    assertNotNull(formats);
    final int formatsCount = formats.size();
    assertEquals(3, formatsCount);
    final CommandTask verifiedTask = Mockito.verify(task);
    verifiedTask.run(TIMEOUT);
  }

  @Test
  void acceptsMoreExtraArgumentsThanItsFixedOptions() throws IOException {
    final CommandTask task = task(0, "{\"id\":\"many\"}", "");
    final AtomicReference<String[]> command = new AtomicReference<>();
    final YTDLPParserImpl parser = parser(task, command);
    final UriSource source = source();
    final String[] options = {
      "--format",
      "best",
      "--socket-timeout",
      "10",
      "--retries",
      "2",
      "--fragment-retries",
      "2",
      "--user-agent",
      "mcav-test",
      "--no-progress",
      "--quiet",
    };
    final URLParseDump dump = parser.parse(source, options);
    final String[] actual = command.get();
    assertNotNull(actual);
    assertEquals("many", dump.id);
    assertEquals(21, actual.length);
    for (int index = 0; index < options.length; index++) {
      assertEquals(options[index], actual[7 + index]);
    }
    assertEquals("--", actual[19]);
    assertEquals(URL, actual[20]);
  }

  @Test
  void runsWithoutExtraArguments() throws IOException {
    final CommandTask task = task(0, "{\"id\": \"plain\"}", "");
    final AtomicReference<String[]> command = new AtomicReference<>();
    final YTDLPParserImpl parser = parser(task, command);
    final UriSource source = source();
    final URLParseDump dump = parser.parse(source);
    final String executable = EXECUTABLE.toString();
    final String[] expected = {
      executable,
      "--dump-json",
      "--no-cache-dir",
      "--no-playlist",
      "--playlist-items",
      "1",
      "--no-warnings",
      "--",
      URL,
    };
    final String[] actual = command.get();
    assertArrayEquals(expected, actual);
    assertEquals("plain", dump.id);
  }

  @Test
  void ignoresSurroundingWhitespaceAndWindowsLineEndings() throws IOException {
    final CommandTask windowsTask = task(0, "\r\n  {\"id\": \"first\"}  \r\n{\"id\": \"second\"}\r\n", "");
    final CommandTask singleLineTask = task(0, "  {\"id\": \"only\"}  \n\n", "");
    final AtomicReference<String[]> command = new AtomicReference<>();
    final YTDLPParserImpl windowsParser = parser(windowsTask, command);
    final YTDLPParserImpl singleLineParser = parser(singleLineTask, command);
    final UriSource source = source();
    final URLParseDump windowsDump = windowsParser.parse(source);
    final URLParseDump singleLineDump = singleLineParser.parse(source);
    assertEquals("first", windowsDump.id);
    assertEquals("only", singleLineDump.id);
  }

  @Test
  void reportsOutputWithoutMetadata() throws IOException {
    final YTDLPParseException empty = assertParseFails("");
    final YTDLPParseException blank = assertParseFails(" \r\n\t\n");
    final YTDLPParseException jsonNull = assertParseFails("null\n");
    final String expected = "yt-dlp printed no metadata for " + URL;
    final String emptyMessage = empty.getMessage();
    final String blankMessage = blank.getMessage();
    final String jsonNullMessage = jsonNull.getMessage();
    final Throwable jsonNullCause = jsonNull.getCause();
    assertEquals(expected, emptyMessage);
    assertEquals(expected, blankMessage);
    assertEquals(expected, jsonNullMessage);
    assertNull(jsonNullCause);
  }

  @Test
  void reportsMalformedMetadata() throws IOException {
    final YTDLPParseException truncated = assertParseFails("{\"id\": \"abc\", \"title\": ");
    final YTDLPParseException array = assertParseFails("[1, 2, 3]");
    final YTDLPParseException wrongType = assertParseFails("{\"formats\": \"not a list\"}");
    final String expected = "yt-dlp printed invalid metadata for " + URL;
    final String truncatedMessage = truncated.getMessage();
    final String arrayMessage = array.getMessage();
    final String wrongTypeMessage = wrongType.getMessage();
    final Throwable truncatedCause = truncated.getCause();
    final Throwable arrayCause = array.getCause();
    final Throwable wrongTypeCause = wrongType.getCause();
    assertEquals(expected, truncatedMessage);
    assertEquals(expected, arrayMessage);
    assertEquals(expected, wrongTypeMessage);
    assertInstanceOf(JsonSyntaxException.class, truncatedCause);
    assertInstanceOf(JsonSyntaxException.class, arrayCause);
    assertInstanceOf(JsonSyntaxException.class, wrongTypeCause);
  }

  @Test
  void rejectsTextAfterTheDocumentInsteadOfIgnoringIt() throws IOException {
    final YTDLPParseException trailingText = assertParseFails("{\"id\": \"abc\"} unexpected trailing text");
    final YTDLPParseException secondDocument = assertParseFails("{\"id\": \"abc\"} {\"id\": \"def\"}");
    final String expected = "yt-dlp printed invalid metadata for " + URL;
    final String trailingTextMessage = trailingText.getMessage();
    final String secondDocumentMessage = secondDocument.getMessage();
    final Throwable trailingTextCause = trailingText.getCause();
    final Throwable secondDocumentCause = secondDocument.getCause();
    assertEquals(expected, trailingTextMessage);
    assertEquals(expected, secondDocumentMessage);
    assertInstanceOf(JsonSyntaxException.class, trailingTextCause);
    assertInstanceOf(JsonSyntaxException.class, secondDocumentCause);
  }

  @Test
  void reportsTheErrorOutputWhenYtdlpFails() throws IOException {
    final CommandTask task = task(1, "partial output", "ERROR: [youtube] dQw4w9WgXcQ: Private video\n");
    final AtomicReference<String[]> command = new AtomicReference<>();
    final YTDLPParserImpl parser = parser(task, command);
    final UriSource source = source();
    final YTDLPParseException exception = assertThrows(YTDLPParseException.class, () -> parser.parse(source));
    final String message = exception.getMessage();
    assertEquals("yt-dlp failed for " + URL + ": ERROR: [youtube] dQw4w9WgXcQ: Private video", message);
  }

  @Test
  void reportsTheStandardOutputWhenTheErrorOutputIsBlank() throws IOException {
    final CommandTask task = task(2, "  Usage: yt-dlp [OPTIONS] URL\n", " \n");
    final AtomicReference<String[]> command = new AtomicReference<>();
    final YTDLPParserImpl parser = parser(task, command);
    final UriSource source = source();
    final YTDLPParseException exception = assertThrows(YTDLPParseException.class, () -> parser.parse(source));
    final String message = exception.getMessage();
    assertEquals("yt-dlp failed for " + URL + ": Usage: yt-dlp [OPTIONS] URL", message);
  }

  @Test
  void propagatesInstallationFailuresWithoutRunningAnything() {
    final IOException failure = new IOException("no network");
    final AtomicBoolean created = new AtomicBoolean();
    final YTDLPParserImpl parser = new YTDLPParserImpl(
      () -> {
        throw failure;
      },
      _ -> {
        created.set(true);
        return Mockito.mock(CommandTask.class);
      }
    );
    final UriSource source = source();
    final IOException thrown = assertThrows(IOException.class, () -> parser.parse(source));
    final boolean taskCreated = created.get();
    assertSame(failure, thrown);
    assertFalse(taskCreated);
  }

  @Test
  void propagatesFailuresToStartTheProcess() throws IOException {
    final IOException failure = new IOException("cannot start");
    final CommandTask task = Mockito.mock(CommandTask.class);
    Mockito.when(task.run(ArgumentMatchers.any(Duration.class))).thenThrow(failure);
    final AtomicReference<String[]> command = new AtomicReference<>();
    final YTDLPParserImpl parser = parser(task, command);
    final UriSource source = source();
    final IOException thrown = assertThrows(IOException.class, () -> parser.parse(source));
    assertSame(failure, thrown);
  }

  @Test
  void rejectsNullArgumentsBeforeInstallingAnything() {
    final AtomicBoolean located = new AtomicBoolean();
    final YTDLPParserImpl parser = new YTDLPParserImpl(
      () -> {
        located.set(true);
        return EXECUTABLE;
      },
      _ -> Mockito.mock(CommandTask.class)
    );
    final UriSource source = source();
    assertThrows(NullPointerException.class, () -> parser.parse(null));
    assertThrows(NullPointerException.class, () -> parser.parse(source, (String[]) null));
    assertThrows(NullPointerException.class, () -> parser.parse(source, "--cookies-from-browser", null));
    final boolean installed = located.get();
    assertFalse(installed);
  }

  @ParameterizedTest
  @ValueSource(strings = { "--cookies=/x", "--exec=calc", "ftp://example.com/video", "file:///etc/passwd", "www.youtube.com/watch?v=x" })
  void rejectsInputsThatAreNotAbsoluteWebUrlsBeforeRunningAnything(final String raw) {
    final URI uri = URI.create(raw);
    final UriSource source = Mockito.mock(UriSource.class);
    Mockito.when(source.getUri()).thenReturn(uri);
    final AtomicBoolean located = new AtomicBoolean();
    final AtomicBoolean created = new AtomicBoolean();
    final YTDLPParserImpl parser = new YTDLPParserImpl(
      () -> {
        located.set(true);
        return EXECUTABLE;
      },
      _ -> {
        created.set(true);
        return Mockito.mock(CommandTask.class);
      }
    );
    final IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> parser.parse(source));
    final String message = exception.getMessage();
    final boolean installed = located.get();
    final boolean taskCreated = created.get();
    final boolean namesTheInput = message.contains(raw);
    assertTrue(namesTheInput, message);
    assertFalse(installed);
    assertFalse(taskCreated);
  }

  @Test
  void acceptsWebUrlsWhateverTheCaseOfTheScheme() throws IOException {
    final CommandTask task = task(0, "{\"id\": \"upper\"}", "");
    final AtomicReference<String[]> command = new AtomicReference<>();
    final YTDLPParserImpl parser = parser(task, command);
    final URI uri = URI.create("HTTPS://www.youtube.com/watch?v=upper");
    final UriSource source = UriSource.uri(uri);
    final URLParseDump dump = parser.parse(source);
    final String[] actual = command.get();
    final String lastArgument = actual[actual.length - 1];
    assertEquals("upper", dump.id);
    assertEquals("HTTPS://www.youtube.com/watch?v=upper", lastArgument);
  }

  @Test
  void simpleReturnsTheSharedDefaultParser() {
    final YTDLPParser first = YTDLPParser.simple();
    final YTDLPParser second = YTDLPParser.simple();
    assertSame(first, second);
    assertSame(YTDLPParserImpl.INSTANCE, first);
    assertInstanceOf(YTDLPParserImpl.class, first);
  }

  @Test
  void defaultParserRunsTheInstalledExecutable() throws IOException {
    // the JVM stands in for yt-dlp: it rejects --dump-json, so the default task factory runs a real process that fails
    final String javaHome = System.getProperty("java.home");
    final String launcherName = File.separatorChar == '\\' ? "java.exe" : "java";
    final Path javaPath = Path.of(javaHome, "bin", launcherName);
    final YTDLPInstaller installer = Mockito.mock(YTDLPInstaller.class);
    Mockito.when(installer.download(true)).thenReturn(javaPath);
    final UriSource source = source();
    final YTDLPParser parser = YTDLPParser.simple();
    try (final MockedStatic<YTDLPInstaller> installers = Mockito.mockStatic(YTDLPInstaller.class)) {
      installers.when(YTDLPInstaller::shared).thenReturn(installer);
      final YTDLPParseException exception = assertThrows(YTDLPParseException.class, () -> parser.parse(source));
      final String message = exception.getMessage();
      final boolean startsWithUrl = message.startsWith("yt-dlp failed for " + URL + ": ");
      final boolean namesTheOption = message.contains("--dump-json");
      assertTrue(startsWithUrl, message);
      assertTrue(namesTheOption, message);
    }
    final YTDLPInstaller verifiedInstaller = Mockito.verify(installer);
    verifiedInstaller.download(true);
  }

  @Test
  void defaultParserRefusesWhileTheLibraryPreparesYtdlp() {
    final YTDLPInstaller installer = Mockito.mock(YTDLPInstaller.class);
    final UriSource source = source();
    final YTDLPParser parser = YTDLPParser.simple();
    final CapabilityGuard guard = CapabilityGuard.shared();
    guard.markPreparing(Capability.YT_DLP);
    try (final MockedStatic<YTDLPInstaller> installers = Mockito.mockStatic(YTDLPInstaller.class)) {
      installers.when(YTDLPInstaller::shared).thenReturn(installer);
      final IllegalStateException exception = assertThrows(IllegalStateException.class, () -> parser.parse(source));
      final String message = exception.getMessage();
      assertEquals(
        "yt-dlp is still being prepared in the background; wait for MCAVApi.whenCapabilityReady(Capability.YT_DLP) or try again shortly",
        message
      );
    } finally {
      guard.forget(Capability.YT_DLP);
    }
    Mockito.verifyNoInteractions(installer);
  }
}
