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
package me.brandonli.mcav.sandbox.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import me.brandonli.mcav.sandbox.testing.UtilityClassAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Tests {@link DumpUtils} against a paste site on the loopback interface.
 */
final class DumpUtilsTest {

  private static final long MEGABYTE = 1024L * 1024L;

  @TempDir
  private Path folder;

  private final HttpClient client = HttpClient.newHttpClient();
  private final AtomicReference<String> uploadedBody = new AtomicReference<>();
  private final AtomicReference<String> uploadedPath = new AtomicReference<>();
  private final AtomicReference<String> contentType = new AtomicReference<>();

  private HttpServer server;
  private volatile int status;
  private volatile String answer;
  private volatile CountDownLatch release;

  @BeforeEach
  void startPasteSite() throws IOException {
    this.status = 200;
    this.answer = "{\"key\":\"abc123\"}";
    this.release = new CountDownLatch(0);
    final InetAddress loopback = InetAddress.getLoopbackAddress();
    final InetSocketAddress address = new InetSocketAddress(loopback, 0);
    this.server = HttpServer.create(address, 0);
    this.server.createContext("/", this::handle);
    this.server.start();
  }

  @AfterEach
  void stopPasteSite() {
    this.release.countDown();
    this.server.stop(0);
    this.client.close();
  }

  private void handle(final HttpExchange exchange) throws IOException {
    try (final InputStream input = exchange.getRequestBody()) {
      final byte[] bytes = input.readAllBytes();
      final String body = new String(bytes, StandardCharsets.UTF_8);
      this.uploadedBody.set(body);
    }
    final URI uri = exchange.getRequestURI();
    final String path = uri.getPath();
    this.uploadedPath.set(path);
    final Headers headers = exchange.getRequestHeaders();
    final String type = headers.getFirst("Content-Type");
    this.contentType.set(type);
    try {
      final boolean released = this.release.await(10, TimeUnit.SECONDS);
      assertTrue(released, "the test never let the paste site answer");
    } catch (final InterruptedException exception) {
      final Thread current = Thread.currentThread();
      current.interrupt();
    }
    final byte[] response = this.answer.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(this.status, response.length);
    try (final OutputStream output = exchange.getResponseBody()) {
      output.write(response);
    }
  }

  private String pasteSite() {
    final InetSocketAddress address = this.server.getAddress();
    final int port = address.getPort();
    return "http://127.0.0.1:" + port + "/";
  }

  private Path missingLog() {
    return this.folder.resolve("latest.log");
  }

  @Test
  void uploadsTheDumpAndReturnsItsLink() {
    final String site = this.pasteSite();
    final Path log = this.missingLog();
    final String url = DumpUtils.createAndUploadDump(this.client, site, log);
    assertEquals(site + "abc123", url);
    final String path = this.uploadedPath.get();
    final String type = this.contentType.get();
    final String body = this.uploadedBody.get();
    assertEquals("/documents/", path);
    assertEquals("text/plain", type);
    final boolean system = body.contains("\n=== System ===\n");
    final boolean properties = body.contains("\n=== JVM Properties ===\n");
    final boolean threads = body.contains("\n=== Threads ===\n");
    final boolean noLog = body.contains("\n=== Latest Log ===\nNo log found\n");
    assertTrue(system);
    assertTrue(properties);
    assertTrue(threads);
    assertTrue(noLog);
  }

  @Test
  void failsWhenThePasteSiteAnswersWithAnError() {
    this.status = 500;
    final String site = this.pasteSite();
    final Path log = this.missingLog();
    final IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
      DumpUtils.createAndUploadDump(this.client, site, log)
    );
    final String message = exception.getMessage();
    assertEquals("Failed to upload the dump: The paste site answered with HTTP 500", message);
    final Throwable cause = exception.getCause();
    assertInstanceOf(IOException.class, cause);
  }

  @ParameterizedTest
  @ValueSource(strings = { "{}", "[]", "\"abc\"", "{\"key\":{}}", "{\"key\":null}" })
  void failsWhenThePasteSiteAnswersWithoutADocumentKey(final String response) {
    this.answer = response;
    final String site = this.pasteSite();
    final Path log = this.missingLog();
    final IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
      DumpUtils.createAndUploadDump(this.client, site, log)
    );
    final String message = exception.getMessage();
    assertEquals("The paste site did not answer with a document key: " + response, message);
  }

  @Test
  void failsWhenThePasteSiteDoesNotAnswerWithJson() {
    this.answer = "{\"key\":";
    final String site = this.pasteSite();
    final Path log = this.missingLog();
    final IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
      DumpUtils.createAndUploadDump(this.client, site, log)
    );
    final String message = exception.getMessage();
    assertEquals("The paste site did not answer with JSON: {\"key\":", message);
  }

  @Test
  void failsWhenThePasteSiteIsUnreachable() {
    final String site = this.pasteSite();
    this.server.stop(0);
    final Path log = this.missingLog();
    final IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
      DumpUtils.createAndUploadDump(this.client, site, log)
    );
    final String message = exception.getMessage();
    final boolean upload = message.startsWith("Failed to upload the dump: ");
    assertTrue(upload, message);
  }

  @Test
  void keepsTheInterruptWhenInterruptedWhileUploading() {
    this.release = new CountDownLatch(1);
    final String site = this.pasteSite();
    final Path log = this.missingLog();
    final Thread current = Thread.currentThread();
    current.interrupt();
    final IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
      DumpUtils.createAndUploadDump(this.client, site, log)
    );
    final boolean interrupted = Thread.interrupted();
    assertTrue(interrupted);
    final String message = exception.getMessage();
    assertEquals("Interrupted while uploading the dump", message);
  }

  @Test
  void uploadsToThePublicPasteSiteWithTheServerLog() {
    try (final MockedStatic<DumpUtils> dumps = Mockito.mockStatic(DumpUtils.class, Mockito.CALLS_REAL_METHODS)) {
      dumps.when(() -> DumpUtils.createAndUploadDump(any(HttpClient.class), any(String.class), any(Path.class))).thenReturn("link");
      final String url = DumpUtils.createAndUploadDump();
      assertEquals("link", url);
      final Path log = Path.of("logs", "latest.log");
      dumps.verify(() -> DumpUtils.createAndUploadDump(any(HttpClient.class), eq("https://paste.helpch.at/"), eq(log)));
    }
  }

  @Test
  void describesTheSystem() {
    final Path log = this.missingLog();
    final String dump = DumpUtils.createDumpContents(log);
    final String osName = System.getProperty("os.name");
    final Runtime runtime = Runtime.getRuntime();
    final int processors = runtime.availableProcessors();
    final Thread current = Thread.currentThread();
    final String threadName = current.getName();
    final boolean os = dump.contains("\nOS: " + osName + "\n");
    final boolean cores = dump.contains("\nProcessors: " + processors + "\n");
    final boolean thread = dump.contains("\nThread: " + threadName + " (RUNNABLE)\n");
    final boolean stack = dump.contains("\tat ");
    assertTrue(os);
    assertTrue(cores);
    assertTrue(thread);
    assertTrue(stack);
  }

  /**
   * Reads the number of a labelled line of the dump, such as {@code Free Memory (MB): 123}.
   *
   * @param dump  the dump
   * @param label the label of the line
   * @return the number the line reports
   */
  private static long readNumber(final String dump, final String label) {
    final String marker = "\n" + label + ": ";
    final int start = dump.indexOf(marker);
    assertTrue(start >= 0, "the dump has no " + label + " line: " + dump);
    final int valueStart = start + marker.length();
    final int end = dump.indexOf('\n', valueStart);
    final String value = dump.substring(valueStart, end);
    return Long.parseLong(value);
  }

  @Test
  void describesTheOperatingSystemAndTheJavaRuntime() {
    final Path log = this.missingLog();
    final String dump = DumpUtils.createDumpContents(log);
    final String osVersion = System.getProperty("os.version");
    final String architecture = System.getProperty("os.arch");
    final String javaVersion = System.getProperty("java.version");
    final String javaVendor = System.getProperty("java.vendor");
    final boolean version = dump.contains("\nOS Version: " + osVersion + "\n");
    final boolean arch = dump.contains("\nArchitecture: " + architecture + "\n");
    final boolean java = dump.contains("\nJava Version: " + javaVersion + "\n");
    final boolean vendor = dump.contains("\nJava Vendor: " + javaVendor + "\n");
    assertTrue(version, dump);
    assertTrue(arch, dump);
    assertTrue(java, dump);
    assertTrue(vendor, dump);
  }

  @Test
  void reportsTheMemoryInMegabytesAndTheUptime() {
    final Path log = this.missingLog();
    final String dump = DumpUtils.createDumpContents(log);
    final Runtime runtime = Runtime.getRuntime();
    final long maxMemory = runtime.maxMemory();
    final long expectedMax = maxMemory / MEGABYTE;
    final long free = readNumber(dump, "Free Memory (MB)");
    final long total = readNumber(dump, "Total Memory (MB)");
    final long reportedMax = readNumber(dump, "Max Memory (MB)");
    final long uptime = readNumber(dump, "Uptime (ms)");
    assertEquals(expectedMax, reportedMax);
    // the free memory never exceeds the heap the JVM holds, and that never exceeds the heap it may grow to
    assertTrue(free <= total, "free memory of " + free + " MB above the total of " + total + " MB");
    assertTrue(total <= expectedMax, "total memory of " + total + " MB above the maximum of " + expectedMax + " MB");
    assertTrue(uptime >= 0, "uptime of " + uptime + " ms");
  }

  @Test
  void redactsPropertiesThatLookSecret() {
    System.setProperty("mcav.test.Api-Token", "hunter2");
    System.setProperty("mcav.test.visible", "shown");
    try {
      final Path log = this.missingLog();
      final String dump = DumpUtils.createDumpContents(log);
      final boolean redacted = dump.contains("\nmcav.test.Api-Token: <redacted>\n");
      final boolean visible = dump.contains("\nmcav.test.visible: shown\n");
      final boolean leaked = dump.contains("hunter2");
      assertTrue(redacted);
      assertTrue(visible);
      assertFalse(leaked);
    } finally {
      System.clearProperty("mcav.test.Api-Token");
      System.clearProperty("mcav.test.visible");
    }
  }

  @Test
  void includesOnlyTheEndOfALongLog() throws IOException {
    final Path log = this.folder.resolve("latest.log");
    final List<String> lines = new ArrayList<>();
    for (int index = 0; index < 2500; index++) {
      lines.add("line " + index);
    }
    Files.write(log, lines, StandardCharsets.UTF_8);
    final String dump = DumpUtils.createDumpContents(log);
    final int start = dump.indexOf("=== Latest Log ===\n");
    final String section = dump.substring(start);
    final String[] sectionLines = section.split("\n");
    final List<String> logLines = Arrays.asList(sectionLines);
    final int lineCount = logLines.size();
    final List<String> tail = logLines.subList(1, lineCount);
    final int tailSize = tail.size();
    assertEquals(2000, tailSize);
    final String first = tail.getFirst();
    final String last = tail.getLast();
    assertEquals("line 500", first);
    assertEquals("line 2499", last);
  }

  @Test
  void includesAShortLogCompletely() throws IOException {
    final Path log = this.folder.resolve("latest.log");
    Files.writeString(log, "started\nstopped\n", StandardCharsets.UTF_8);
    final String dump = DumpUtils.createDumpContents(log);
    final boolean ends = dump.endsWith("=== Latest Log ===\nstarted\nstopped\n");
    assertTrue(ends);
  }

  @Test
  void reportsALogThatCannotBeRead() throws IOException {
    final Path log = this.folder.resolve("latest.log");
    final byte[] invalidUtf8 = { (byte) 0xC3, (byte) 0x28 };
    Files.write(log, invalidUtf8);
    final String dump = DumpUtils.createDumpContents(log);
    final boolean reported = dump.contains("=== Latest Log ===\nFailed to read the log: ");
    assertTrue(reported);
  }

  @Test
  void isNotInstantiable() {
    UtilityClassAssertions.assertNotInstantiable(DumpUtils.class);
  }

  @Test
  void redactsJvmArgumentsThatLookSecret() {
    final List<String> arguments = List.of(
      "-Xmx2G",
      "-Dsome.token=hunter2",
      "-Djavax.net.ssl.keyStorePassword=changeit",
      "-DDISCORD_SECRET=abc=def",
      "-javaagent:agent.jar=token=hunter3",
      "-Dfile.encoding=UTF-8",
      "-Dmcav.password",
      "-XX:+UseZGC"
    );
    final List<String> redacted = DumpUtils.redactArguments(arguments);
    final List<String> expected = List.of(
      "-Xmx2G",
      "-Dsome.token=<redacted>",
      "-Djavax.net.ssl.keyStorePassword=<redacted>",
      "-DDISCORD_SECRET=<redacted>",
      "-javaagent:agent.jar=<redacted>",
      "-Dfile.encoding=UTF-8",
      "-Dmcav.password",
      "-XX:+UseZGC"
    );
    assertEquals(expected, redacted);
  }

  @Test
  void listsTheJvmArgumentsWithSecretsRedacted() {
    final Path log = this.missingLog();
    final String dump = DumpUtils.createDumpContents(log);
    final RuntimeMXBean bean = ManagementFactory.getRuntimeMXBean();
    final List<String> arguments = bean.getInputArguments();
    final List<String> redacted = DumpUtils.redactArguments(arguments);
    final boolean listed = dump.contains("\nJVM Arguments: " + redacted + "\n");
    assertTrue(listed, dump);
  }
}
