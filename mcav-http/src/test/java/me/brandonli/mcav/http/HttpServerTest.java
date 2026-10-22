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
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import me.brandonli.mcav.http.testing.Await;
import me.brandonli.mcav.json.ytdlp.format.UrlParseDump;
import me.brandonli.mcav.media.player.metadata.OriginalAudioMetadata;
import org.apache.coyote.AbstractProtocol;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

/**
 * Tests {@link SpringHttpResult} together with {@link HttpServerApplication}, {@link MediaController} and
 * {@link AudioWebSocketHandler} on a real web server bound to a free port of the loopback interface.
 */
final class HttpServerTest {

  private static final long TIMEOUT_SECONDS = 10;
  private static final long SLOW_TIMEOUT_SECONDS = 60;

  private static final String LOCALHOST = "localhost";
  private static final String LOOPBACK_HOST = "127.0.0.1";
  private static final String FOREIGN_HOST = "192.0.2.1";

  private static final String ADDRESS_PROPERTY = "server.address=%s";
  private static final List<String> LOOPBACK = List.of(ADDRESS_PROPERTY.formatted(LOOPBACK_HOST));

  private static final int EPHEMERAL_PORT = 0;
  private static final int DEFAULT_BACKLOG = 50;

  private static final String HTTP_SCHEME = "http";
  private static final String WEBSOCKET_SCHEME = "ws";

  private static final String ROOT_PATH = "/";
  private static final String INDEX_PATH = "/index.html";
  private static final String MISSING_PATH = "/missing.html";
  private static final String MEDIA_PATH = "/media";
  private static final String AUDIO_PATH = "/audio";

  private static final String PAGE_DIRECTORY_NAME = "page";
  private static final String OTHER_DIRECTORY_NAME = "other";
  private static final String INDEX_FILE = "index.html";
  private static final String SECRET_FILE = "secret.txt";

  private static final String PAGE_TEMPLATE = "<!doctype html><title>%s</title>";
  private static final String DIRECTORY_PAGE_TITLE = "directory test page";

  private static final String SECRET = "top secret content next to the page directory";

  private static final int CHUNK_LENGTH = 4096;

  private static final String START_FAILURE_PREFIX = "Web server on port %s failed with \"";
  private static final String START_FAILURE_SUFFIX = "\" while starting!";

  private static final String ROOT_LOGGER_NAME = "";

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
  private static SpringHttpResult server;
  private static HttpClient client;

  private final WorkerAssertions workers = new WorkerAssertions();

  private final OriginalAudioMetadata metadata = Mockito.mock(OriginalAudioMetadata.class);

  @AfterEach
  void assertWorkersFinished() throws InterruptedException {
    this.workers.assertFinished();
  }

  @BeforeAll
  static void startTheServer() throws IOException {
    pageDirectory = temporary.resolve(PAGE_DIRECTORY_NAME);
    Files.createDirectories(pageDirectory);
    final Path secret = temporary.resolve(SECRET_FILE);
    Files.writeString(secret, SECRET);
    final Path index = pageDirectory.resolve(INDEX_FILE);
    final String page = PAGE_TEMPLATE.formatted(DIRECTORY_PAGE_TITLE);
    Files.writeString(index, page);
    server = new SpringHttpResult(LOCALHOST, EPHEMERAL_PORT, pageDirectory, LOOPBACK);
    server.start();
    client = HttpClient.newHttpClient();
  }

  @AfterAll
  static void stopTheServer() {
    server.stop();
    client.close();
  }

  private static URI uri(final SpringHttpResult http, final String scheme, final String path) {
    final int port = http.getLocalPort();
    return uri(port, scheme, path);
  }

  private static URI uri(final int port, final String scheme, final String path) {
    final String address = "%s://%s:%s%s".formatted(scheme, LOOPBACK_HOST, port, path);
    return URI.create(address);
  }

  private static HttpResponse<String> get(final SpringHttpResult http, final String path) throws IOException, InterruptedException {
    final URI target = uri(http, HTTP_SCHEME, path);
    final HttpRequest.Builder builder = HttpRequest.newBuilder(target);
    final Duration timeout = Duration.ofSeconds(TIMEOUT_SECONDS);
    builder.timeout(timeout);
    final HttpRequest request = builder.build();
    final HttpResponse.BodyHandler<String> handler = HttpResponse.BodyHandlers.ofString();
    return client.send(request, handler);
  }

  private static WebSocket connect(final SpringHttpResult http, final RecordingListener listener) throws Exception {
    final URI target = uri(http, WEBSOCKET_SCHEME, AUDIO_PATH);
    final WebSocket.Builder builder = client.newWebSocketBuilder();
    final CompletableFuture<WebSocket> future = builder.buildAsync(target, listener);
    return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
  }

  private static void awaitListeners(final SpringHttpResult http, final int count) {
    Await.until("%s browsers listen".formatted(count), () -> http.getListenerCount() == count);
  }

  private static void closeNormally(final WebSocket socket) throws Exception {
    final CompletableFuture<WebSocket> closing = socket.sendClose(WebSocket.NORMAL_CLOSURE, "done");
    closing.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
  }

  private static int freePort() throws IOException {
    final InetAddress loopback = InetAddress.getLoopbackAddress();
    try (final ServerSocket socket = new ServerSocket(EPHEMERAL_PORT, DEFAULT_BACKLOG, loopback)) {
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

    private byte[] nextMessage() throws InterruptedException {
      final byte[] message = this.messages.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertNotNull(message, "no audio message arrived");
      return message;
    }

    private int awaitCloseCode() throws Exception {
      return this.closeCode.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private boolean isClosed() {
      return this.closeCode.isDone();
    }
  }

  /**
   * A logging handler that only marks the logging configuration of the JVM, so a test can see whether it was
   * replaced.
   */
  private static final class MarkerHandler extends Handler {

    @Override
    public void publish(final LogRecord record) {}

    @Override
    public void flush() {}

    @Override
    public void close() {}
  }

  @Test
  void shouldListenOnAFreePortOfTheLoopbackInterface() {
    final boolean running = server.isRunning();
    final int port = server.getLocalPort();
    assertTrue(running);
    assertNotEquals(0, port);
  }

  @Test
  void shouldServeTheWebPageFromTheDirectory() throws Exception {
    final HttpResponse<String> root = get(server, ROOT_PATH);
    final HttpResponse<String> index = get(server, INDEX_PATH);
    final HttpResponse<String> missing = get(server, MISSING_PATH);
    final int rootStatus = root.statusCode();
    final String rootBody = root.body();
    final int indexStatus = index.statusCode();
    final String indexBody = index.body();
    final int missingStatus = missing.statusCode();
    final boolean rootServesThePage = rootBody.contains(DIRECTORY_PAGE_TITLE);
    final boolean indexServesThePage = indexBody.contains(DIRECTORY_PAGE_TITLE);
    assertEquals(HttpURLConnection.HTTP_OK, rootStatus);
    assertTrue(rootServesThePage);
    assertEquals(HttpURLConnection.HTTP_OK, indexStatus);
    assertTrue(indexServesThePage);
    assertEquals(HttpURLConnection.HTTP_NOT_FOUND, missingStatus);
  }

  @Test
  void shouldServeEmptyMediaAsJson() throws Exception {
    server.setCurrentMedia((MediaInformation) null);
    final HttpResponse<String> empty = get(server, MEDIA_PATH);
    final int status = empty.statusCode();
    final String emptyBody = empty.body();
    final HttpHeaders headers = empty.headers();
    final Optional<String> contentType = headers.firstValue("Content-Type");
    final Optional<String> expectedType = Optional.of("application/json");
    assertEquals(HttpURLConnection.HTTP_OK, status);
    assertEquals("{\"duration\":0,\"view_count\":0,\"like_count\":0}", emptyBody);
    assertEquals(expectedType, contentType);
  }

  @Test
  void shouldServeTheCompleteMediaAsJson() throws Exception {
    final UrlParseDump dump = MediaInformationTest.completeDump();
    server.setCurrentMedia(dump);
    try {
      final HttpResponse<String> complete = get(server, MEDIA_PATH);
      final String completeBody = complete.body();
      final String expected =
        "{\"title\":\"Song\",\"uploader\":\"Artist\",\"channel\":\"Artist Channel\",\"uploader_id\":\"@artist\",\"description\":\"A description\",\"thumbnail\":\"https://example.com/thumbnail.jpg\",\"duration\":216,\"view_count\":3000000000,\"like_count\":50,\"upload_date\":\"20240101\"}";
      assertEquals(expected, completeBody);
    } finally {
      server.setCurrentMedia((MediaInformation) null);
    }
  }

  @Test
  void shouldEscapeTheMediaInformationInJson() throws Exception {
    final MediaInformation titled = MediaInformation.titled("Quote \" and ünïcode");
    server.setCurrentMedia(titled);
    try {
      final HttpResponse<String> escaped = get(server, MEDIA_PATH);
      final String escapedBody = escaped.body();
      assertEquals("{\"title\":\"Quote \\\" and ünïcode\",\"duration\":0,\"view_count\":0,\"like_count\":0}", escapedBody);
    } finally {
      server.setCurrentMedia((MediaInformation) null);
    }
  }

  private static byte[] countingChunk() {
    final byte[] chunk = new byte[CHUNK_LENGTH];
    for (int index = 0; index < chunk.length; index++) {
      chunk[index] = (byte) index;
    }
    return chunk;
  }

  @Test
  void shouldStreamTheSamplesToEveryListenerInOrder() throws Exception {
    final RecordingListener first = new RecordingListener();
    final RecordingListener second = new RecordingListener();
    final WebSocket firstSocket = connect(server, first);
    final WebSocket secondSocket = connect(server, second);
    awaitListeners(server, 2);

    final byte[] chunkOne = countingChunk();
    final byte[] chunkTwo = { 42 };
    final ByteBuffer samplesOne = ByteBuffer.wrap(chunkOne);
    final ByteBuffer samplesTwo = ByteBuffer.wrap(chunkTwo);
    final boolean changedOne = server.applyFilter(samplesOne, this.metadata);
    final boolean changedTwo = server.applyFilter(samplesTwo, this.metadata);
    assertFalse(changedOne, "the filter only reads the sample, so it reports no change");
    assertFalse(changedTwo, "the filter only reads the sample, so it reports no change");

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
  void shouldIgnoreMessagesFromBrowsers() throws Exception {
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

  private static void assertServesTheBundledPage(final SpringHttpResult http) throws Exception {
    final HttpResponse<String> page = get(http, ROOT_PATH);
    final String body = page.body();
    final boolean servesTheBundledPage = body.contains("bundled test page");
    assertTrue(servesTheBundledPage, "the bundled page is served from classpath:/mcav/http/website/");
  }

  private static void assertStopsWithGoingAway(final SpringHttpResult http, final int port) throws Exception {
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

    final URI stoppedTarget = uri(port, HTTP_SCHEME, MEDIA_PATH);
    final HttpRequest.Builder stoppedBuilder = HttpRequest.newBuilder(stoppedTarget);
    final HttpRequest stoppedRequest = stoppedBuilder.build();
    final HttpResponse.BodyHandler<Void> discarding = HttpResponse.BodyHandlers.discarding();
    assertThrows(IOException.class, () -> client.send(stoppedRequest, discarding));
  }

  private static void assertRestarts(final SpringHttpResult http) throws Exception {
    http.start();
    final boolean runningAgain = http.isRunning();
    assertTrue(runningAgain);
    final HttpResponse<String> media = get(http, MEDIA_PATH);
    final int status = media.statusCode();
    assertEquals(HttpURLConnection.HTTP_OK, status);
  }

  @Test
  void shouldStartInTheBackgroundStopWithGoingAwayAndRestart() throws Exception {
    final SpringHttpResult http = new SpringHttpResult(LOCALHOST, EPHEMERAL_PORT, null, LOOPBACK);
    final boolean runningBeforeStart = http.isRunning();
    assertFalse(runningBeforeStart);
    final CompletableFuture<Void> started = http.startAsync();
    started.get(SLOW_TIMEOUT_SECONDS, TimeUnit.SECONDS);
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
  void shouldRefuseAHandshakeThatFinishesWhileTheServerIsStopping() throws Exception {
    final SpringHttpResult http = new SpringHttpResult(LOCALHOST, EPHEMERAL_PORT, null, LOOPBACK);
    http.start();
    try {
      final CountDownLatch insideHandshake = new CountDownLatch(1);
      final CountDownLatch serverStopped = new CountDownLatch(1);
      final AtomicBoolean firstIdentifier = new AtomicBoolean(true);
      final WebSocketSession late = Mockito.mock(WebSocketSession.class);
      Mockito.when(late.isOpen()).thenReturn(true);
      Mockito.when(late.getId()).thenAnswer(invocation -> {
        final boolean first = firstIdentifier.getAndSet(false);
        if (first) {
          insideHandshake.countDown();
          serverStopped.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
        return "late";
      });

      final Thread handshake = new Thread(() -> http.addListener(late), "late-handshake");
      handshake.start();
      final boolean parked = insideHandshake.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertTrue(parked, "the handshake must be inside addListener before the server stops");

      http.stop();
      serverStopped.countDown();
      final Duration limit = Duration.ofSeconds(TIMEOUT_SECONDS);
      final boolean finished = handshake.join(limit);
      assertTrue(finished, "the handshake must finish instead of parking on a queue nothing signals");

      final int listeners = http.getListenerCount();
      assertEquals(0, listeners, "a listener registered during the shutdown must not survive it");
      SpringHttpResultTest.verifyClosed(late, CloseStatus.GOING_AWAY);
    } finally {
      http.stop();
    }
  }

  @Test
  void shouldRestoreTheContextClassLoaderAfterStartingAndStopping() throws Exception {
    final Thread thread = Thread.currentThread();
    final ClassLoader original = thread.getContextClassLoader();
    final SpringHttpResult http = new SpringHttpResult(LOCALHOST, EPHEMERAL_PORT, pageDirectory, LOOPBACK);
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
  void shouldFailWithAnHttpExceptionWhenThePortIsInUse() throws Exception {
    final InetAddress loopback = InetAddress.getLoopbackAddress();
    try (final ServerSocket occupied = new ServerSocket(EPHEMERAL_PORT, DEFAULT_BACKLOG, loopback)) {
      final int port = occupied.getLocalPort();
      final SpringHttpResult http = new SpringHttpResult(LOCALHOST, port, null, LOOPBACK);
      final Thread thread = Thread.currentThread();
      final ClassLoader before = thread.getContextClassLoader();
      final HttpException exception = assertThrows(HttpException.class, http::start);
      final ClassLoader after = thread.getContextClassLoader();
      final String message = exception.getMessage();
      final Throwable cause = exception.getCause();
      final boolean running = http.isRunning();
      final String expectedStart = START_FAILURE_PREFIX.formatted(port);
      final boolean namesThePort = message.startsWith(expectedStart);
      final boolean endsOnWords = message.endsWith(START_FAILURE_SUFFIX);
      assertTrue(namesThePort, message);
      assertTrue(endsOnWords, message);
      assertInstanceOf(RuntimeException.class, cause);
      assertFalse(running);
      assertSame(before, after);
    }
  }

  @Test
  void shouldReportTheFailuresOfAnAsynchronousStart() {
    final String foreignAddress = ADDRESS_PROPERTY.formatted(FOREIGN_HOST);
    final List<String> properties = List.of(foreignAddress);
    final SpringHttpResult http = new SpringHttpResult(LOCALHOST, EPHEMERAL_PORT, null, properties);
    final CompletableFuture<Void> started = http.startAsync();
    final ExecutionException exception = assertThrows(ExecutionException.class, () -> started.get(SLOW_TIMEOUT_SECONDS, TimeUnit.SECONDS));
    final Throwable cause = exception.getCause();
    assertInstanceOf(HttpException.class, cause);
    final boolean running = http.isRunning();
    assertFalse(running);
  }

  @Test
  void shouldNeverServeFilesOutsideThePageDirectory() throws Exception {
    for (final String attack : TRAVERSAL_ATTACKS) {
      final HttpResponse<String> response = get(server, attack);
      final int status = response.statusCode();
      final String body = response.body();
      final boolean leaked = body.contains(SECRET);
      assertFalse(leaked, "%s leaked the file outside the page directory".formatted(attack));
      assertTrue(status >= HttpURLConnection.HTTP_BAD_REQUEST, "%s answered %s".formatted(attack, status));
    }
  }

  @Test
  void shouldApplyACopyOfTheExtraPropertiesAfterTheDefaults() throws Exception {
    final Path other = temporary.resolve(OTHER_DIRECTORY_NAME);
    Files.createDirectories(other);
    final Path index = other.resolve(INDEX_FILE);
    final String otherPage = PAGE_TEMPLATE.formatted("other page");
    Files.writeString(index, otherPage);
    final String location = server.staticLocation(other);
    final List<String> properties = new ArrayList<>(LOOPBACK);
    final String locationProperty = "spring.web.resources.static-locations=%s".formatted(location);
    properties.add(locationProperty);
    final SpringHttpResult http = new SpringHttpResult(LOCALHOST, EPHEMERAL_PORT, pageDirectory, properties);
    properties.clear();
    try {
      http.start();
      final HttpResponse<String> page = get(http, ROOT_PATH);
      final int status = page.statusCode();
      final String body = page.body();
      assertEquals(HttpURLConnection.HTTP_OK, status, "server.address=127.0.0.1 of the copied list is in effect");
      final Field contextField = SpringHttpResult.class.getDeclaredField("context");
      contextField.setAccessible(true);
      final WebServerApplicationContext context = (WebServerApplicationContext) contextField.get(http);
      final TomcatWebServer webServer = (TomcatWebServer) context.getWebServer();
      final AbstractProtocol<?> protocol = (AbstractProtocol<?>) webServer.getTomcat().getConnector().getProtocolHandler();
      final InetAddress expectedAddress = InetAddress.ofLiteral(LOOPBACK_HOST);
      final InetAddress address = protocol.getAddress();
      assertEquals(expectedAddress, address, "the connector binds only to the copied address");

      final boolean servesTheOtherPage = body.contains("other page");
      final boolean servesTheDirectoryPage = body.contains(DIRECTORY_PAGE_TITLE);
      assertTrue(servesTheOtherPage, "an extra property overrides the default location");
      assertFalse(servesTheDirectoryPage);
    } finally {
      http.stop();
    }
  }

  @Test
  void shouldListenOnTheBindAddressOfTheBuilder() throws Exception {
    final InetAddress loopback = InetAddress.ofLiteral(LOOPBACK_HOST);
    final int port = freePort();
    final HttpResultBuilder builder = HttpResult.builder();
    builder.port(port);
    builder.directory(pageDirectory);
    builder.bindAddress(loopback);
    final HttpResult built = builder.build();
    final SpringHttpResult http = assertInstanceOf(SpringHttpResult.class, built);
    try {
      http.start();
      final int localPort = http.getLocalPort();
      final HttpResponse<String> page = get(http, ROOT_PATH);
      final int status = page.statusCode();
      final String body = page.body();
      final boolean servesThePage = body.contains(DIRECTORY_PAGE_TITLE);
      assertEquals(port, localPort);
      assertEquals(HttpURLConnection.HTTP_OK, status);
      assertTrue(servesThePage);
    } finally {
      http.stop();
    }
  }

  @Test
  void shouldFailToStartOnABindAddressOfAnotherMachine() throws Exception {
    final InetAddress foreign = InetAddress.ofLiteral(FOREIGN_HOST);
    final int port = freePort();
    final HttpResultBuilder builder = HttpResult.builder();
    builder.port(port);
    builder.bindAddress(foreign);
    final HttpResult http = builder.build();
    final HttpException exception = assertThrows(HttpException.class, http::start);
    final String message = exception.getMessage();
    final boolean running = http.isRunning();
    final String expectedStart = START_FAILURE_PREFIX.formatted(port);
    final boolean namesThePort = message.startsWith(expectedStart);
    final boolean endsOnWords = message.endsWith(START_FAILURE_SUFFIX);
    assertTrue(namesThePort, message);
    assertTrue(endsOnWords, message);
    assertFalse(running);
  }

  /**
   * Creates a session whose writes block until released and which, like the WebSocket sessions of Spring, waits
   * for the write in progress when it is closed.
   */
  private WebSocketSession stuckSession(final CountDownLatch writeEntered, final CountDownLatch releaseWrite) throws IOException {
    final WebSocketSession stuck = Mockito.mock(WebSocketSession.class);
    Mockito.when(stuck.getId()).thenReturn("stuck");
    Mockito.when(stuck.isOpen()).thenReturn(true);
    Mockito.doAnswer(_ -> {
      this.workers.observeCurrentThread();
      writeEntered.countDown();
      final boolean released = releaseWrite.await(SLOW_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertTrue(released, "the test releases the stuck write");
      return null;
    })
      .when(stuck)
      .sendMessage(ArgumentMatchers.any());
    Mockito.doAnswer(_ -> {
      this.workers.observeCurrentThread();
      final boolean released = releaseWrite.await(SLOW_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertTrue(released, "the test releases the stuck write");
      return null;
    })
      .when(stuck)
      .close(ArgumentMatchers.any());
    return stuck;
  }

  @Test
  void shouldStopWithoutWaitingForAListenerWhoseWriteIsStuck() throws Exception {
    final SpringHttpResult http = new SpringHttpResult(LOCALHOST, EPHEMERAL_PORT, pageDirectory, LOOPBACK);
    final CountDownLatch writeEntered = new CountDownLatch(1);
    final CountDownLatch releaseWrite = new CountDownLatch(1);
    final WebSocketSession stuck = this.stuckSession(writeEntered, releaseWrite);
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
      SpringHttpResultTest.verifyClosed(stuck, CloseStatus.GOING_AWAY);

      final Duration startLimit = Duration.ofSeconds(SLOW_TIMEOUT_SECONDS);
      assertTimeoutPreemptively(startLimit, http::start, "start must not wait for the stuck write either");
      final boolean restarted = http.isRunning();
      assertTrue(restarted);
    } finally {
      releaseWrite.countDown();
      http.stop();
    }
  }

  private static void assertStartAndStopKeepTheLogging(final SpringHttpResult http, final Logger root) {
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
  void shouldLeaveTheLoggingOfTheJvmUnchangedWhenStarting() {
    final String property = SpringHttpResult.LOGGING_SYSTEM_PROPERTY;
    final String previousProperty = System.getProperty(property);
    final Logger root = Logger.getLogger(ROOT_LOGGER_NAME);
    final Level previousLevel = root.getLevel();
    final Handler marker = new MarkerHandler();
    root.addHandler(marker);
    root.setLevel(Level.CONFIG);
    final SpringHttpResult http = new SpringHttpResult(LOCALHOST, EPHEMERAL_PORT, pageDirectory, LOOPBACK);
    try {
      System.clearProperty(property);
      assertStartAndStopKeepTheLogging(http, root);
      final String chosen = System.getProperty(property);
      assertEquals("none", chosen);
    } finally {
      http.stop();
      root.removeHandler(marker);
      root.setLevel(previousLevel);
      SpringHttpResultTest.restoreLoggingSystem(previousProperty);
    }
  }
}
