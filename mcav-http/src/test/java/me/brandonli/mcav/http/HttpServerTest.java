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
package me.brandonli.mcav.http;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import me.brandonli.mcav.http.testing.Await;
import me.brandonli.mcav.json.ytdlp.format.URLParseDump;
import me.brandonli.mcav.media.player.metadata.OriginalAudioMetadata;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

/**
 * Tests {@link HttpResultImpl} together with {@link HttpServerApplication}, {@link MediaController} and
 * {@link AudioWebSocketHandler} on a real web server bound to a free port of the loopback interface.
 */
final class HttpServerTest {

  private static final long TIMEOUT_SECONDS = 10;
  private static final List<String> LOOPBACK = List.of("server.address=127.0.0.1");

  private static final String SECRET = "top secret content next to the page directory";

  private static final List<String> TRAVERSAL_ATTACKS = List.of(
    "/../secret.txt",
    "/page/../../secret.txt",
    "/..%2fsecret.txt",
    "/..%2Fsecret.txt",
    "/%2e%2e/secret.txt",
    "/%2E%2E/secret.txt",
    "/%2e%2e%2fsecret.txt",
    "/.%2e/secret.txt",
    "/..%5csecret.txt",
    "/..%5Csecret.txt",
    "/%2e%2e%5csecret.txt",
    "/%252e%252e/secret.txt",
    "/%252e%252e%252fsecret.txt",
    "/..;/secret.txt",
    "/%c0%ae%c0%ae/secret.txt"
  );

  @TempDir
  private static Path temporary;

  private static Path pageDirectory;
  private static HttpResultImpl server;
  private static HttpClient client;

  private final OriginalAudioMetadata metadata = Mockito.mock(OriginalAudioMetadata.class);

  @BeforeAll
  static void startTheServer() throws IOException {
    pageDirectory = temporary.resolve("page");
    Files.createDirectories(pageDirectory);
    final Path secret = temporary.resolve("secret.txt");
    Files.writeString(secret, SECRET);
    final Path index = pageDirectory.resolve("index.html");
    Files.writeString(index, "<!doctype html><title>directory test page</title>");
    server = new HttpResultImpl("localhost", 0, pageDirectory, LOOPBACK);
    server.start();
    client = HttpClient.newHttpClient();
  }

  @AfterAll
  static void stopTheServer() {
    server.stop();
    client.close();
  }

  private static URI uri(final HttpResultImpl http, final String scheme, final String path) {
    final int port = http.getLocalPort();
    return URI.create(scheme + "://127.0.0.1:" + port + path);
  }

  private static HttpResponse<String> get(final HttpResultImpl http, final String path) throws IOException, InterruptedException {
    final URI target = uri(http, "http", path);
    final HttpRequest.Builder builder = HttpRequest.newBuilder(target);
    final Duration timeout = Duration.ofSeconds(TIMEOUT_SECONDS);
    builder.timeout(timeout);
    final HttpRequest request = builder.build();
    final HttpResponse.BodyHandler<String> handler = HttpResponse.BodyHandlers.ofString();
    return client.send(request, handler);
  }

  private static WebSocket connect(final HttpResultImpl http, final RecordingListener listener) throws Exception {
    final URI target = uri(http, "ws", "/audio");
    final WebSocket.Builder builder = client.newWebSocketBuilder();
    final CompletableFuture<WebSocket> future = builder.buildAsync(target, listener);
    return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
  }

  private static void awaitListeners(final HttpResultImpl http, final int count) {
    Await.until(count + " browsers listen", () -> http.getListenerCount() == count);
  }

  private static void closeNormally(final WebSocket socket) throws Exception {
    final CompletableFuture<WebSocket> closing = socket.sendClose(WebSocket.NORMAL_CLOSURE, "done");
    closing.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
  }

  private static int freePort() throws IOException {
    final InetAddress loopback = InetAddress.getLoopbackAddress();
    try (final ServerSocket socket = new ServerSocket(0, 1, loopback)) {
      return socket.getLocalPort();
    }
  }

  /**
   * Collects the complete binary messages and the close code a WebSocket client receives.
   */
  private static final class RecordingListener implements WebSocket.Listener {

    private final BlockingQueue<byte[]> messages = new LinkedBlockingQueue<>();
    private final CompletableFuture<Integer> closeCode = new CompletableFuture<>();
    private final ByteArrayOutputStream partial = new ByteArrayOutputStream();

    @Override
    public CompletionStage<?> onBinary(final WebSocket webSocket, final ByteBuffer data, final boolean last) {
      final int remaining = data.remaining();
      final byte[] bytes = new byte[remaining];
      data.get(bytes);
      this.partial.writeBytes(bytes);
      if (last) {
        final byte[] message = this.partial.toByteArray();
        this.messages.add(message);
        this.partial.reset();
      }
      webSocket.request(1);
      return null;
    }

    @Override
    public CompletionStage<?> onClose(final WebSocket webSocket, final int statusCode, final String reason) {
      this.closeCode.complete(statusCode);
      return null;
    }

    @Override
    public void onError(final WebSocket webSocket, final Throwable error) {
      this.closeCode.completeExceptionally(error);
    }

    byte[] nextMessage() throws InterruptedException {
      final byte[] message = this.messages.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertNotNull(message, "no audio message arrived");
      return message;
    }

    int awaitCloseCode() throws Exception {
      return this.closeCode.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    boolean isClosed() {
      return this.closeCode.isDone();
    }
  }

  /**
   * A logging handler that only marks the logging configuration of the JVM, so a test can see whether it was
   * replaced.
   */
  private static final class MarkerHandler extends Handler {

    @Override
    public void publish(final LogRecord record) {
      // only marks the configuration
    }

    @Override
    public void flush() {
      // nothing buffered
    }

    @Override
    public void close() {
      // nothing to release
    }
  }

  @Test
  void listensOnAFreePortOfTheLoopbackInterface() {
    final boolean running = server.isRunning();
    final int port = server.getLocalPort();
    assertTrue(running);
    assertNotEquals(0, port);
  }

  @Test
  void servesTheWebPageFromTheDirectory() throws Exception {
    final HttpResponse<String> root = get(server, "/");
    final HttpResponse<String> index = get(server, "/index.html");
    final HttpResponse<String> missing = get(server, "/missing.html");
    final int rootStatus = root.statusCode();
    final String rootBody = root.body();
    final int indexStatus = index.statusCode();
    final String indexBody = index.body();
    final int missingStatus = missing.statusCode();
    assertEquals(200, rootStatus);
    assertTrue(rootBody.contains("directory test page"));
    assertEquals(200, indexStatus);
    assertTrue(indexBody.contains("directory test page"));
    assertEquals(404, missingStatus);
  }

  @Test
  void servesEmptyMediaAsJson() throws Exception {
    server.setCurrentMedia((MediaInfo) null);
    final HttpResponse<String> empty = get(server, "/media");
    final int status = empty.statusCode();
    final String emptyBody = empty.body();
    final HttpHeaders headers = empty.headers();
    final Optional<String> contentType = headers.firstValue("Content-Type");
    final Optional<String> expectedType = Optional.of("application/json");
    assertEquals(200, status);
    assertEquals("{\"duration\":0,\"view_count\":0,\"like_count\":0}", emptyBody);
    assertEquals(expectedType, contentType);
  }

  @Test
  void servesTheCompleteMediaAsJson() throws Exception {
    final URLParseDump dump = MediaInfoTest.completeDump();
    server.setCurrentMedia(dump);
    try {
      final HttpResponse<String> complete = get(server, "/media");
      final String completeBody = complete.body();
      final String expected =
        "{\"title\":\"Song\",\"uploader\":\"Artist\",\"channel\":\"Artist Channel\",\"uploader_id\":\"@artist\"," +
        "\"description\":\"A description\",\"thumbnail\":\"https://example.com/thumbnail.jpg\",\"duration\":216," +
        "\"view_count\":3000000000,\"like_count\":50,\"upload_date\":\"20240101\"}";
      assertEquals(expected, completeBody);
    } finally {
      server.setCurrentMedia((MediaInfo) null);
    }
  }

  @Test
  void escapesTheMediaInformationInJson() throws Exception {
    final MediaInfo titled = MediaInfo.titled("Quote \" and ünïcode");
    server.setCurrentMedia(titled);
    try {
      final HttpResponse<String> escaped = get(server, "/media");
      final String escapedBody = escaped.body();
      assertEquals("{\"title\":\"Quote \\\" and ünïcode\",\"duration\":0,\"view_count\":0,\"like_count\":0}", escapedBody);
    } finally {
      server.setCurrentMedia((MediaInfo) null);
    }
  }

  private static byte[] countingChunk() {
    final byte[] chunk = new byte[4096];
    for (int index = 0; index < chunk.length; index++) {
      chunk[index] = (byte) index;
    }
    return chunk;
  }

  @Test
  void streamsTheSamplesToEveryListenerInOrder() throws Exception {
    final RecordingListener first = new RecordingListener();
    final RecordingListener second = new RecordingListener();
    final WebSocket firstSocket = connect(server, first);
    final WebSocket secondSocket = connect(server, second);
    awaitListeners(server, 2);

    final byte[] chunkOne = countingChunk();
    final byte[] chunkTwo = { 42 };
    final ByteBuffer samplesOne = ByteBuffer.wrap(chunkOne);
    final ByteBuffer samplesTwo = ByteBuffer.wrap(chunkTwo);
    final boolean resultOne = server.applyFilter(samplesOne, this.metadata);
    final boolean resultTwo = server.applyFilter(samplesTwo, this.metadata);
    assertTrue(resultOne);
    assertTrue(resultTwo);

    final List<RecordingListener> listeners = List.of(first, second);
    for (final RecordingListener listener : listeners) {
      final byte[] receivedOne = listener.nextMessage();
      final byte[] receivedTwo = listener.nextMessage();
      assertArrayEquals(chunkOne, receivedOne);
      assertArrayEquals(chunkTwo, receivedTwo);
    }
    closeNormally(firstSocket);
    awaitListeners(server, 1);
    closeNormally(secondSocket);
    awaitListeners(server, 0);
  }

  @Test
  void ignoresMessagesFromBrowsers() throws Exception {
    final RecordingListener listener = new RecordingListener();
    final WebSocket socket = connect(server, listener);
    awaitListeners(server, 1);
    final CompletableFuture<WebSocket> text = socket.sendText("hello", true);
    text.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    final byte[] raw = { 1, 2, 3 };
    final ByteBuffer payload = ByteBuffer.wrap(raw);
    final CompletableFuture<WebSocket> binary = socket.sendBinary(payload, true);
    binary.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

    final byte[] chunk = "still connected".getBytes(StandardCharsets.US_ASCII);
    final ByteBuffer samples = ByteBuffer.wrap(chunk);
    server.applyFilter(samples, this.metadata);
    final byte[] received = listener.nextMessage();
    assertArrayEquals(chunk, received);
    final int count = server.getListenerCount();
    assertEquals(1, count);
    final boolean closed = listener.isClosed();
    assertFalse(closed);
    closeNormally(socket);
    awaitListeners(server, 0);
  }

  private static void assertServesTheBundledPage(final HttpResultImpl http) throws Exception {
    final HttpResponse<String> page = get(http, "/");
    final String body = page.body();
    assertTrue(body.contains("bundled test page"), "the bundled page is served from classpath:/static/");
  }

  private static void assertStopsWithGoingAway(final HttpResultImpl http, final int port) throws Exception {
    final RecordingListener listener = new RecordingListener();
    connect(http, listener);
    awaitListeners(http, 1);
    http.stop();
    final int closeCode = listener.awaitCloseCode();
    assertEquals(1001, closeCode);
    final boolean runningAfterStop = http.isRunning();
    final int listenersAfterStop = http.getListenerCount();
    final int portAfterStop = http.getLocalPort();
    assertFalse(runningAfterStop);
    assertEquals(0, listenersAfterStop);
    assertEquals(0, portAfterStop);

    final URI stoppedTarget = URI.create("http://127.0.0.1:" + port + "/media");
    final HttpRequest.Builder stoppedBuilder = HttpRequest.newBuilder(stoppedTarget);
    final HttpRequest stoppedRequest = stoppedBuilder.build();
    final HttpResponse.BodyHandler<Void> discarding = HttpResponse.BodyHandlers.discarding();
    assertThrows(IOException.class, () -> client.send(stoppedRequest, discarding));
  }

  private static void assertRestarts(final HttpResultImpl http) throws Exception {
    http.start();
    final boolean runningAgain = http.isRunning();
    assertTrue(runningAgain);
    final HttpResponse<String> media = get(http, "/media");
    final int status = media.statusCode();
    assertEquals(200, status);
  }

  @Test
  void startsInTheBackgroundStopsWithGoingAwayAndRestarts() throws Exception {
    final HttpResultImpl http = new HttpResultImpl("localhost", 0, null, LOOPBACK);
    final boolean runningBeforeStart = http.isRunning();
    assertFalse(runningBeforeStart);
    final CompletableFuture<Void> started = http.startAsync();
    started.get(60, TimeUnit.SECONDS);
    try {
      final boolean running = http.isRunning();
      final int port = http.getLocalPort();
      assertTrue(running);
      http.start();
      final int portAfterSecondStart = http.getLocalPort();
      assertEquals(port, portAfterSecondStart);

      assertServesTheBundledPage(http);
      assertStopsWithGoingAway(http, port);
      http.stop();
      assertRestarts(http);
    } finally {
      http.stop();
    }
  }

  @Test
  void restoresTheContextClassLoaderAfterStartingAndStopping() throws Exception {
    final Thread thread = Thread.currentThread();
    final ClassLoader original = thread.getContextClassLoader();
    final HttpResultImpl http = new HttpResultImpl("localhost", 0, pageDirectory, LOOPBACK);
    try (final URLClassLoader custom = new URLClassLoader(new URL[0], null)) {
      thread.setContextClassLoader(custom);
      http.start();
      final ClassLoader afterStart = thread.getContextClassLoader();
      http.stop();
      final ClassLoader afterStop = thread.getContextClassLoader();
      assertSame(custom, afterStart);
      assertSame(custom, afterStop);
    } finally {
      thread.setContextClassLoader(original);
      http.stop();
    }
  }

  @Test
  void failsWithAnHttpExceptionWhenThePortIsInUse() throws Exception {
    final InetAddress loopback = InetAddress.getLoopbackAddress();
    try (final ServerSocket occupied = new ServerSocket(0, 50, loopback)) {
      final int port = occupied.getLocalPort();
      final HttpResultImpl http = new HttpResultImpl("localhost", port, null, LOOPBACK);
      final Thread thread = Thread.currentThread();
      final ClassLoader before = thread.getContextClassLoader();
      final HttpException exception = assertThrows(HttpException.class, http::start);
      final ClassLoader after = thread.getContextClassLoader();
      final String message = exception.getMessage();
      final Throwable cause = exception.getCause();
      final boolean running = http.isRunning();
      assertTrue(message.startsWith("Failed to start the web server on port " + port + ": "), message);
      assertInstanceOf(RuntimeException.class, cause);
      assertFalse(running);
      assertSame(before, after);
    }
  }

  @Test
  void asyncStartReportsFailures() {
    // 192.0.2.1 is TEST-NET-1: never an address of this machine, and parsing it needs no DNS lookup
    final List<String> properties = List.of("server.address=192.0.2.1");
    final HttpResultImpl http = new HttpResultImpl("localhost", 0, null, properties);
    final CompletableFuture<Void> started = http.startAsync();
    final ExecutionException exception = assertThrows(ExecutionException.class, () -> started.get(60, TimeUnit.SECONDS));
    final Throwable cause = exception.getCause();
    assertInstanceOf(HttpException.class, cause);
    final boolean running = http.isRunning();
    assertFalse(running);
  }

  @Test
  void neverServesFilesOutsideThePageDirectory() throws Exception {
    for (final String attack : TRAVERSAL_ATTACKS) {
      final HttpResponse<String> response = get(server, attack);
      final int status = response.statusCode();
      final String body = response.body();
      assertFalse(body.contains(SECRET), attack + " leaked the file outside the page directory");
      assertTrue(status >= 400, attack + " answered " + status);
    }
  }

  @Test
  void appliesACopyOfTheExtraPropertiesAfterTheDefaults() throws Exception {
    final Path other = temporary.resolve("other");
    Files.createDirectories(other);
    final Path index = other.resolve("index.html");
    Files.writeString(index, "<!doctype html><title>other page</title>");
    final String location = HttpResultImpl.staticLocation(other);
    final List<String> properties = new ArrayList<>(LOOPBACK);
    properties.add("spring.web.resources.static-locations=" + location);
    final HttpResultImpl http = new HttpResultImpl("localhost", 0, pageDirectory, properties);
    properties.clear();
    try {
      http.start();
      final HttpResponse<String> page = get(http, "/");
      final int status = page.statusCode();
      final String body = page.body();
      assertEquals(200, status, "server.address=127.0.0.1 of the copied list is in effect");
      assertTrue(body.contains("other page"), "an extra property overrides the default location");
      assertFalse(body.contains("directory test page"));
    } finally {
      http.stop();
    }
  }

  @Test
  void listensOnTheBindAddressOfTheBuilder() throws Exception {
    final byte[] raw = { 127, 0, 0, 1 };
    final InetAddress loopback = InetAddress.getByAddress(raw);
    final int port = freePort();
    final HttpResultBuilder builder = HttpResult.builder();
    builder.port(port);
    builder.directory(pageDirectory);
    builder.bindAddress(loopback);
    final HttpResult built = builder.build();
    final HttpResultImpl http = assertInstanceOf(HttpResultImpl.class, built);
    try {
      http.start();
      final int localPort = http.getLocalPort();
      final HttpResponse<String> page = get(http, "/");
      final int status = page.statusCode();
      final String body = page.body();
      assertEquals(port, localPort);
      assertEquals(200, status);
      assertTrue(body.contains("directory test page"));
    } finally {
      http.stop();
    }
  }

  @Test
  void failsToStartOnABindAddressOfAnotherMachine() throws Exception {
    // TEST-NET-1 is never assigned to this machine
    final byte[] raw = { (byte) 192, 0, 2, 1 };
    final InetAddress foreign = InetAddress.getByAddress(raw);
    final int port = freePort();
    final HttpResultBuilder builder = HttpResult.builder();
    builder.port(port);
    builder.bindAddress(foreign);
    final HttpResult http = builder.build();
    final HttpException exception = assertThrows(HttpException.class, http::start);
    final String message = exception.getMessage();
    final boolean running = http.isRunning();
    assertTrue(message.startsWith("Failed to start the web server on port " + port + ": "), message);
    assertFalse(running);
  }

  /**
   * Creates a session whose writes block until released and which, like the WebSocket sessions of Spring, waits
   * for the write in progress when it is closed.
   */
  private static WebSocketSession stuckSession(final CountDownLatch writeEntered, final CountDownLatch releaseWrite) throws IOException {
    final WebSocketSession stuck = Mockito.mock(WebSocketSession.class);
    Mockito.when(stuck.getId()).thenReturn("stuck");
    Mockito.when(stuck.isOpen()).thenReturn(true);
    Mockito.doAnswer(_ -> {
      writeEntered.countDown();
      final boolean released = releaseWrite.await(60, TimeUnit.SECONDS);
      assertTrue(released, "the test releases the stuck write");
      return null;
    })
      .when(stuck)
      .sendMessage(ArgumentMatchers.any());
    Mockito.doAnswer(_ -> {
      final boolean released = releaseWrite.await(60, TimeUnit.SECONDS);
      assertTrue(released, "the test releases the stuck write");
      return null;
    })
      .when(stuck)
      .close(ArgumentMatchers.any());
    return stuck;
  }

  @Test
  void stopDoesNotWaitForAListenerWhoseWriteIsStuck() throws Exception {
    final HttpResultImpl http = new HttpResultImpl("localhost", 0, pageDirectory, LOOPBACK);
    final CountDownLatch writeEntered = new CountDownLatch(1);
    final CountDownLatch releaseWrite = new CountDownLatch(1);
    final WebSocketSession stuck = stuckSession(writeEntered, releaseWrite);
    try {
      http.start();
      http.addListener(stuck);
      final byte[] raw = { 1, 2, 3, 4 };
      final ByteBuffer samples = ByteBuffer.wrap(raw);
      http.applyFilter(samples, this.metadata);
      final boolean entered = writeEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertTrue(entered);

      final Duration limit = Duration.ofSeconds(TIMEOUT_SECONDS);
      assertTimeoutPreemptively(limit, http::stop, "stop must not wait for the stuck write");
      final boolean running = http.isRunning();
      final int listeners = http.getListenerCount();
      assertFalse(running);
      assertEquals(0, listeners);
      HttpResultImplTest.verifyClosed(stuck, CloseStatus.GOING_AWAY);

      final Duration startLimit = Duration.ofSeconds(60);
      assertTimeoutPreemptively(startLimit, http::start, "start must not wait for the stuck write either");
      final boolean restarted = http.isRunning();
      assertTrue(restarted);
    } finally {
      releaseWrite.countDown();
      http.stop();
    }
  }

  private static void assertStartAndStopKeepTheLogging(final HttpResultImpl http, final Logger root) {
    final Handler[] before = root.getHandlers();
    http.start();
    final Level levelWhileRunning = root.getLevel();
    final Handler[] handlersWhileRunning = root.getHandlers();
    http.stop();
    final Level levelAfterStop = root.getLevel();
    final Handler[] handlersAfterStop = root.getHandlers();
    assertEquals(Level.CONFIG, levelWhileRunning);
    assertArrayEquals(before, handlersWhileRunning);
    assertEquals(Level.CONFIG, levelAfterStop);
    assertArrayEquals(before, handlersAfterStop);
  }

  @Test
  void startingLeavesTheLoggingOfTheJvmUnchanged() {
    final String property = HttpResultImpl.LOGGING_SYSTEM_PROPERTY;
    final String previousProperty = System.getProperty(property);
    final Logger root = Logger.getLogger("");
    final Level previousLevel = root.getLevel();
    final Handler marker = new MarkerHandler();
    root.addHandler(marker);
    root.setLevel(Level.CONFIG);
    final HttpResultImpl http = new HttpResultImpl("localhost", 0, pageDirectory, LOOPBACK);
    try {
      // as in a fresh JVM, where no server set the property yet
      System.clearProperty(property);
      assertStartAndStopKeepTheLogging(http, root);
      final String chosen = System.getProperty(property);
      assertEquals("none", chosen);
    } finally {
      http.stop();
      root.removeHandler(marker);
      root.setLevel(previousLevel);
      HttpResultImplTest.restoreLoggingSystem(previousProperty);
    }
  }
}
