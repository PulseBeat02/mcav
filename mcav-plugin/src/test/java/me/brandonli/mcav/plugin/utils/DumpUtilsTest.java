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
package me.brandonli.mcav.plugin.utils;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import me.brandonli.mcav.plugin.PluginException;
import me.brandonli.mcav.plugin.testing.UtilityClassAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

final class DumpUtilsTest {

  private static final long MEGABYTE = 1024L * 1024L;

  private static final int AWAIT_TIMEOUT_SECONDS = 10;

  private static final String ROOT_PATH = "/";
  private static final String DOCUMENTS_PATH = "/documents/";

  private static final String PUBLIC_ADDRESS = "203.0.113.7";
  private static final String REDACTED_ADDRESS = "<redacted-address>";

  private static final String SECRET_VALUE = "hunter2";

  private static final String LOG_FILE_NAME = "latest.log";
  private static final String SERVER_JAR_FILE_NAME = "paper.jar";
  private static final String LOGS_FOLDER_NAME = "logs";

  private static final String LINE_BREAK = "\n";

  private static final String COMMAND_PROPERTY = "sun.java.command";

  private static final int EPHEMERAL_PORT = 0;

  @TempDir
  private Path folder;

  private final HttpClient client = HttpClient.newHttpClient();
  private final AtomicReference<String> uploadedBody = new AtomicReference<>();
  private final AtomicReference<String> uploadedPath = new AtomicReference<>();
  private final AtomicReference<String> contentType = new AtomicReference<>();

  private HttpServer server;
  private final ExecutorService handlers = Executors.newSingleThreadExecutor();
  private final AtomicReference<AssertionError> handlerFailure = new AtomicReference<>();
  private volatile int status;
  private volatile String answer;
  private volatile CountDownLatch release;

  @BeforeEach
  void startPasteSite() throws IOException {
    this.status = 200;
    this.answer = "{\"key\":\"abc123\"}";
    this.release = new CountDownLatch(0);
    final InetAddress loopback = InetAddress.getLoopbackAddress();
    final InetSocketAddress address = new InetSocketAddress(loopback, EPHEMERAL_PORT);
    this.server = HttpServer.create(address, 0);
    this.server.createContext(ROOT_PATH, this::answerUpload);
    this.server.setExecutor(this.handlers);
    this.server.start();
  }

  @AfterEach
  void stopPasteSite() throws InterruptedException {
    this.release.countDown();
    this.server.stop(0);
    this.client.close();
    this.handlers.shutdown();

    final boolean terminated = this.handlers.awaitTermination(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertTrue(terminated, "the paste callbacks finish");

    final AssertionError handlerFailure = this.handlerFailure.get();
    assertEquals(null, handlerFailure, "HTTP handler assertions reach JUnit");
  }

  private void answerUpload(final HttpExchange exchange) throws IOException {
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
      final boolean released = this.release.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      try {
        assertTrue(released, "the test never let the paste site answer");

        final String requestMethod = exchange.getRequestMethod();
        assertEquals("POST", requestMethod, "a dump is uploaded by POST");
      } catch (final AssertionError exception) {
        this.handlerFailure.compareAndSet(null, exception);
      }
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

  @Test
  void shouldUploadTheDumpAndReturnItsLink() {
    final String site = this.pasteSite();
    final Path log = this.missingLog();
    final String url = DumpUtils.createAndUploadDump(this.client, site, log);
    assertEquals("%sabc123".formatted(site), url);

    final String path = this.uploadedPath.get();
    final String type = this.contentType.get();
    final String body = this.uploadedBody.get();
    assertEquals(DOCUMENTS_PATH, path);
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

  private String pasteSite() {
    final InetSocketAddress address = this.server.getAddress();
    final int port = address.getPort();
    return "http://127.0.0.1:%s%s".formatted(port, ROOT_PATH);
  }

  private Path missingLog() {
    return this.folder.resolve(LOG_FILE_NAME);
  }

  @Test
  void shouldFailWhenThePasteSiteAnswersWithAnError() {
    this.status = 500;
    final String site = this.pasteSite();
    final Path log = this.missingLog();
    final PluginException exception = assertThrows(PluginException.class, () -> DumpUtils.createAndUploadDump(this.client, site, log));

    final String message = exception.getMessage();
    assertEquals("The dump could not be uploaded!", message);

    final Throwable cause = exception.getCause();
    final IOException ioException = assertInstanceOf(IOException.class, cause);

    final String causeMessage = ioException.getMessage();
    assertEquals("The paste site answered with HTTP status 500, which is not a success!", causeMessage);
  }

  @ParameterizedTest
  @ValueSource(strings = { "{}", "[]", "\"abc\"", "{\"key\":{}}", "{\"key\":null}" })
  void shouldFailWhenThePasteSiteAnswersWithoutADocumentKey(final String response) {
    this.answer = response;
    final String site = this.pasteSite();
    final Path log = this.missingLog();
    final PluginException exception = assertThrows(PluginException.class, () -> DumpUtils.createAndUploadDump(this.client, site, log));

    final String message = exception.getMessage();
    assertEquals("Paste site answer %s has no document key!".formatted(response), message);
  }

  @Test
  void shouldFailWhenThePasteSiteDoesNotAnswerWithJson() {
    this.answer = "{\"key\":";
    final String site = this.pasteSite();
    final Path log = this.missingLog();
    final PluginException exception = assertThrows(PluginException.class, () -> DumpUtils.createAndUploadDump(this.client, site, log));

    final String message = exception.getMessage();
    assertEquals("Paste site answer {\"key\": is not JSON!", message);
  }

  @Test
  void shouldFailWhenThePasteSiteIsUnreachable() {
    final String site = this.pasteSite();
    this.server.stop(0);
    final Path log = this.missingLog();
    final PluginException exception = assertThrows(PluginException.class, () -> DumpUtils.createAndUploadDump(this.client, site, log));

    final String message = exception.getMessage();
    assertEquals("The dump could not be uploaded!", message);

    final Throwable cause = exception.getCause();
    assertInstanceOf(IOException.class, cause);
  }

  @Test
  void shouldKeepTheInterruptWhenInterruptedWhileUploading() {
    this.release = new CountDownLatch(1);
    final String site = this.pasteSite();
    final Path log = this.missingLog();
    final Thread current = Thread.currentThread();
    current.interrupt();
    final PluginException exception = assertThrows(PluginException.class, () -> DumpUtils.createAndUploadDump(this.client, site, log));

    final boolean interrupted = Thread.interrupted();
    assertTrue(interrupted);

    final String message = exception.getMessage();
    assertEquals("Interrupted while uploading the dump!", message);
  }

  @Test
  void shouldUploadToThePublicPasteSiteWithTheServerLog() {
    try (final MockedStatic<DumpUtils> dumps = Mockito.mockStatic(DumpUtils.class, Mockito.CALLS_REAL_METHODS)) {
      dumps.when(() -> DumpUtils.createAndUploadDump(any(HttpClient.class), any(String.class), any(Path.class))).thenReturn("link");
      final String url = DumpUtils.createAndUploadDump();
      assertEquals("link", url);

      final Path log = Path.of(LOGS_FOLDER_NAME, LOG_FILE_NAME);
      dumps.verify(() -> DumpUtils.createAndUploadDump(any(HttpClient.class), eq("https://paste.helpch.at/"), eq(log)));
    }
  }

  @Test
  void shouldDescribeTheSystem() {
    final Path log = this.missingLog();
    final String dump = DumpUtils.createDumpContents(log);
    final String osName = System.getProperty("os.name");
    final Runtime runtime = Runtime.getRuntime();
    final int processors = runtime.availableProcessors();
    final Thread current = Thread.currentThread();
    final String threadName = current.getName();
    final boolean os = dump.contains("\nOS: %s\n".formatted(osName));
    final boolean cores = dump.contains("\nProcessors: %s\n".formatted(processors));
    final boolean thread = dump.contains("\nThread: %s (RUNNABLE)\n".formatted(threadName));
    final boolean stack = dump.contains("\tat ");
    assertTrue(os);
    assertTrue(cores);
    assertTrue(thread);
    assertTrue(stack);
  }

  @Test
  void shouldDescribeTheOperatingSystemAndTheJavaRuntime() {
    final Path log = this.missingLog();
    final String dump = DumpUtils.createDumpContents(log);
    final String osVersion = System.getProperty("os.version");
    final String architecture = System.getProperty("os.arch");
    final String javaVersion = System.getProperty("java.version");
    final String javaVendor = System.getProperty("java.vendor");
    final boolean version = dump.contains("\nOS Version: %s\n".formatted(osVersion));
    final boolean namesArchitecture = dump.contains("\nArchitecture: %s\n".formatted(architecture));
    final boolean namesJavaVersion = dump.contains("\nJava Version: %s\n".formatted(javaVersion));
    final boolean vendor = dump.contains("\nJava Vendor: %s\n".formatted(javaVendor));
    assertTrue(version, dump);
    assertTrue(namesArchitecture, dump);
    assertTrue(namesJavaVersion, dump);
    assertTrue(vendor, dump);
  }

  @Test
  void shouldReportTheMemoryInMegabytesAndTheUptime() {
    final Path log = this.missingLog();
    final RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();
    final long beforeDump = runtimeBean.getUptime();
    final String dump = DumpUtils.createDumpContents(log);
    final long afterDump = runtimeBean.getUptime();
    final Runtime runtime = Runtime.getRuntime();
    final long maximumMemory = runtime.maxMemory();
    final long expectedMaximum = maximumMemory / MEGABYTE;
    final long free = this.readNumber(dump, "Free Memory (MB)");
    final long total = this.readNumber(dump, "Total Memory (MB)");
    final long reportedMaximum = this.readNumber(dump, "Max Memory (MB)");
    final long uptime = this.readNumber(dump, "Uptime (ms)");
    assertEquals(expectedMaximum, reportedMaximum);
    assertTrue(free <= total, "free memory of %s MB above the total of %s MB".formatted(free, total));
    assertTrue(total <= expectedMaximum, "total memory of %s MB above the maximum of %s MB".formatted(total, expectedMaximum));
    assertTrue(beforeDump > 0, "the fixture has a nonzero uptime");
    assertTrue(uptime >= beforeDump && uptime <= afterDump, "uptime of %s ms outside [%s, %s]".formatted(uptime, beforeDump, afterDump));
  }

  private long readNumber(final String dump, final String label) {
    final String marker = "\n%s: ".formatted(label);
    final int start = dump.indexOf(marker);
    assertTrue(start >= 0, "the dump has no %s line: %s".formatted(label, dump));

    final int valueStart = start + marker.length();
    final int end = dump.indexOf('\n', valueStart);
    final String value = dump.substring(valueStart, end);
    return Long.parseLong(value);
  }

  @Test
  void shouldRedactPropertiesThatLookSecret() {
    System.setProperty("mcav.test.Api-Token", SECRET_VALUE);
    System.setProperty("mcav.test.visible", "shown");
    try {
      final Path log = this.missingLog();
      final String dump = DumpUtils.createDumpContents(log);
      final boolean redacted = dump.contains("\nmcav.test.Api-Token: <redacted>\n");
      final boolean visible = dump.contains("\nmcav.test.visible: shown\n");
      final boolean leaked = dump.contains(SECRET_VALUE);
      assertTrue(redacted);
      assertTrue(visible);
      assertFalse(leaked);
    } finally {
      System.clearProperty("mcav.test.Api-Token");
      System.clearProperty("mcav.test.visible");
    }
  }

  @Test
  void shouldRedactThePlayerAddressesOfTheLog() {
    final String join = "[12:34:56 INFO]: Player[/203.0.113.7:50514] logged in with entity id 42";
    final String sixth = "[12:34:56 INFO]: Player[/[2001:db8::1]:50514] logged in with entity id 42";

    final String redactedJoin = DumpUtils.redactLogLine(join);
    final String redactedSixth = DumpUtils.redactLogLine(sixth);

    final boolean contained = redactedJoin.contains(PUBLIC_ADDRESS);
    assertFalse(contained, redactedJoin);

    final boolean redactedJoinContained = redactedJoin.contains(REDACTED_ADDRESS);
    assertTrue(redactedJoinContained, redactedJoin);

    final boolean prefixed = redactedJoin.startsWith("[12:34:56 INFO]: Player");
    assertTrue(prefixed, "the time of day is not an address: %s".formatted(redactedJoin));

    final boolean secondContained = redactedJoin.contains("entity id 42");
    assertTrue(secondContained, redactedJoin);

    final boolean redactedSixthContained = redactedSixth.contains("2001:db8");
    assertFalse(redactedSixthContained, redactedSixth);

    final boolean thirdContained = redactedSixth.contains(REDACTED_ADDRESS);
    assertTrue(thirdContained, redactedSixth);
  }

  // the JDK writes the zone of a link-local IPv6 address after a percent sign
  @Test
  void shouldRedactTheAddressOfAPlayerOnALinkLocalConnection() {
    final String shrunk = "[12:34:56 INFO]: aaa[/[0:0:0:0:0:0:0:0%0]:0] logged in with entity id 42 at ([world]1.5, 64.0, -3.5)";
    final String named = "[12:34:56 INFO]: Player[/[fe80:0:0:0:0:0:0:1%eth0]:50514] logged in with entity id 42";

    final String redactedShrunk = DumpUtils.redactLogLine(shrunk);
    final String redactedNamed = DumpUtils.redactLogLine(named);

    final boolean contained = redactedShrunk.contains("0:0:0:0:0:0:0:0%0");
    assertFalse(contained, redactedShrunk);

    final boolean redactedShrunkContained = redactedShrunk.contains(REDACTED_ADDRESS);
    assertTrue(redactedShrunkContained, redactedShrunk);

    final boolean redactedNamedContained = redactedNamed.contains("fe80:");
    assertFalse(redactedNamedContained, redactedNamed);

    final boolean secondContained = redactedNamed.contains("%eth0");
    assertFalse(secondContained, redactedNamed);

    final boolean thirdContained = redactedNamed.contains(REDACTED_ADDRESS);
    assertTrue(thirdContained, redactedNamed);
  }

  @Test
  void shouldRedactAnIpv6AddressThatTheLogWritesWithoutBrackets() {
    final String banned = "[12:34:56 INFO]: [Steve: Banned IP 2001:db8::1: Banned by an operator.]";

    final String redacted = DumpUtils.redactLogLine(banned);

    assertEquals("[12:34:56 INFO]: [Steve: Banned IP <redacted-address>: Banned by an operator.]", redacted);
  }

  @Test
  void shouldRedactTheUserTheQueryAndTheFragmentOfEveryWebAddress() {
    final String command =
      "[12:34:56 INFO]: Steve issued server command: /mcav browser create @a 640x360 1 5x3 0 NEAREST_COLOR NONE https://alice:open@example.com/page?code=abc#frag";
    final String notice = "[12:34:56 INFO]: [HelperSession] Browser: Refused a navigation to myapp://callback?code=abc";
    final String video = "[12:34:56 INFO]: Failed to start https://cdn.example.com/clip.mp4?Expires=1&Signature=abc for 2 viewers";
    final String plain = "[12:34:56 INFO]: Steve issued server command: /mcav video play @a https://example.com/clip.mp4";
    final String numeric = "[12:34:56 INFO]: Browser: The browser could not load http://203.0.113.7:8080/admin?session=1";

    final String redacted = DumpUtils.redactLogLine(command);
    assertEquals(
      "[12:34:56 INFO]: Steve issued server command: /mcav browser create @a 640x360 1 5x3 0 NEAREST_COLOR NONE https://<redacted>@example.com/page?<redacted>",
      redacted
    );

    final String dumpUtilsRedacted = DumpUtils.redactLogLine(notice);
    assertEquals("[12:34:56 INFO]: [HelperSession] Browser: Refused a navigation to myapp://callback?<redacted>", dumpUtilsRedacted);

    final String secondRedacted = DumpUtils.redactLogLine(video);
    assertEquals("[12:34:56 INFO]: Failed to start https://cdn.example.com/clip.mp4?<redacted> for 2 viewers", secondRedacted);

    final String thirdRedacted = DumpUtils.redactLogLine(plain);
    assertEquals(plain, thirdRedacted, "an address without secrets is what a bug report is about");

    final String fourthRedacted = DumpUtils.redactLogLine(numeric);
    assertEquals("[12:34:56 INFO]: Browser: The browser could not load http://<redacted-address>/admin?<redacted>", fourthRedacted);
  }

  @Test
  void shouldRedactWhatPlayersTypedAfterTheCommandsOfOtherPlugins() {
    final String login = "[12:34:56 INFO]: Steve issued server command: /login hunter2";
    final String own = "[12:34:56 INFO]: Steve issued server command: /mcav video map @a FFMPEG NONE 640x360 5x3 0 FILTER_LITE  clip.mp4";
    final String bare = "[12:34:56 INFO]: Steve issued server command: /spawn";
    final String trailing = "[12:34:56 INFO]: Steve issued server command: /spawn ";

    final String redactedLogin = DumpUtils.redactLogLine(login);
    final String keptOwn = DumpUtils.redactLogLine(own);
    final String keptBare = DumpUtils.redactLogLine(bare);

    assertEquals("[12:34:56 INFO]: Steve issued server command: /login <redacted>", redactedLogin);
    assertEquals(own, keptOwn, "the arguments of this plugin are what a bug report is about");
    assertEquals(bare, keptBare, "a command without arguments has nothing to redact");

    final String keptTrailing = DumpUtils.redactLogLine(trailing);
    assertEquals(trailing, keptTrailing, "blank arguments have nothing to redact either");
  }

  @Test
  void shouldRedactTheAddressesAndCommandsOfTheUploadedLog() throws IOException {
    final Path log = this.folder.resolve(LOG_FILE_NAME);
    final List<String> lines = List.of(
      "[12:34:56 INFO]: Steve[/203.0.113.7:50514] logged in",
      "[12:34:57 INFO]: Steve issued server command: /login hunter2"
    );
    Files.write(log, lines, StandardCharsets.UTF_8);

    final String dump = DumpUtils.createDumpContents(log);

    final boolean contained = dump.contains(PUBLIC_ADDRESS);
    assertFalse(contained, dump);

    final boolean dumpContained = dump.contains(SECRET_VALUE);
    assertFalse(dumpContained, dump);

    final boolean secondContained = dump.contains(REDACTED_ADDRESS);
    assertTrue(secondContained, dump);
  }

  @Test
  void shouldIncludeOnlyTheEndOfALongLog() throws IOException {
    final Path log = this.folder.resolve(LOG_FILE_NAME);
    final List<String> lines = new ArrayList<>();
    for (int index = 0; index < 2500; index++) {
      lines.add("line %s".formatted(index));
    }
    Files.write(log, lines, StandardCharsets.UTF_8);
    final String dump = DumpUtils.createDumpContents(log);
    final int start = dump.indexOf("=== Latest Log ===\n");
    final String section = dump.substring(start);
    final String[] sectionLines = section.split(LINE_BREAK);
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
  void shouldIncludeAShortLogCompletely() throws IOException {
    final Path log = this.folder.resolve(LOG_FILE_NAME);
    Files.writeString(log, "started\nstopped\n", StandardCharsets.UTF_8);
    final String dump = DumpUtils.createDumpContents(log);
    final boolean ends = dump.endsWith("=== Latest Log ===\nstarted\nstopped\n");
    assertTrue(ends);
  }

  @Test
  void shouldReportALogThatCannotBeRead() throws IOException {
    final Path log = this.folder.resolve(LOG_FILE_NAME);
    final byte[] invalidUtf8 = { (byte) 0xC3, (byte) 0x28 };
    Files.write(log, invalidUtf8);
    final String dump = DumpUtils.createDumpContents(log);
    final boolean reported = dump.contains("=== Latest Log ===\nFailed to read the log: ");
    assertTrue(reported);
  }

  @Test
  void shouldNotBeInstantiable() {
    UtilityClassAssertions.assertNotInstantiable(DumpUtils.class);
  }

  @Test
  void shouldRedactJvmArgumentsThatLookSecret() {
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
  void shouldListTheJvmArgumentsWithSecretsRedacted() {
    final Path log = this.missingLog();
    final String dump = DumpUtils.createDumpContents(log);
    final RuntimeMXBean bean = ManagementFactory.getRuntimeMXBean();
    final List<String> arguments = bean.getInputArguments();
    final List<String> redacted = DumpUtils.redactArguments(arguments);
    final boolean listed = dump.contains("\nJVM Arguments: %s\n".formatted(redacted));
    assertTrue(listed, dump);
  }

  @ParameterizedTest
  @ValueSource(
    strings = {
      "paper.jar --world flat --api-token topsecret hidden-tail",
      "paper.jar --api-token=\"topsecret hidden-tail\" --world world",
      "paper.jar\t--password\t'topsecret hidden-tail'",
      "paper.jar -javaagent:agent.jar=token=topsecret hidden-tail",
    }
  )
  void shouldRedactTheWholeSecretFromPropertiesAndLogs(final String command) throws IOException {
    final String property = COMMAND_PROPERTY;
    final String previous = System.getProperty(property);
    final Path log = this.folder.resolve(LOG_FILE_NAME);
    Files.writeString(log, command);
    System.setProperty(property, command);
    try {
      final String dump = DumpUtils.createDumpContents(log);
      final boolean firstPart = dump.contains("topsecret");
      final boolean trailingPart = dump.contains("hidden-tail");
      final boolean keptProgram = dump.contains(SERVER_JAR_FILE_NAME);
      final boolean redacted = dump.contains("<redacted>");
      assertFalse(firstPart);
      assertFalse(trailingPart, "quoted or ambiguous trailing secret text must also be omitted");
      assertTrue(keptProgram);
      assertTrue(redacted);
    } finally {
      if (previous == null) {
        System.clearProperty(property);
      } else {
        System.setProperty(property, previous);
      }
    }
  }

  @Test
  void shouldRedactASecretThatHidesInsideAPropertyValue() {
    // the dump is public, and sun.java.command holds the whole command line under a harmless name, so redacting by name fails
    final String property = COMMAND_PROPERTY;
    final String previous = System.getProperty(property);
    System.setProperty(property, "paper.jar --api-token=hunter2 --world world");
    try {
      final Path log = this.missingLog();
      final String dump = DumpUtils.createDumpContents(log);
      final boolean leaked = dump.contains(SECRET_VALUE);
      final boolean redacted = dump.contains("--api-token=<redacted>");
      final boolean keptTheRest = dump.contains(SERVER_JAR_FILE_NAME);
      assertFalse(leaked, "a secret inside a property value must never reach the paste site");
      assertTrue(redacted, dump);
      assertTrue(keptTheRest, "only the secret is removed, the rest of the value stays useful");
    } finally {
      if (previous == null) {
        System.clearProperty(property);
      } else {
        System.setProperty(property, previous);
      }
    }
  }
}
