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
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import me.brandonli.mcav.json.ytdlp.format.UrlParseDump;
import me.brandonli.mcav.media.player.metadata.OriginalAudioMetadata;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.mockito.verification.VerificationWithTimeout;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * Tests {@link SpringHttpResult} and the {@link HttpResult} factories without starting a web server; the running
 * server is tested by {@link HttpServerTest}.
 */
final class SpringHttpResultTest {

  private static final long TIMEOUT_MILLISECONDS = 10_000;

  private static final int LAST_CHUNK_VALUE = 20;

  private static final String LOCALHOST = "localhost";
  private static final String EXAMPLE_DOMAIN = "example.com";
  private static final String PUBLIC_DOMAIN = "play.example.com";
  private static final String BLANK_DOMAIN = " ";

  private static final int PORT = 8080;
  private static final int MAXIMUM_PORT = 65535;

  private static final String BUNDLED_PAGE_LOCATION = "classpath:/mcav/http/website/";
  private static final String PATH_SEPARATOR = "/";

  private static final String SESSION_ID = "listener";
  private static final String BROKEN_SESSION_ID = "broken";

  private static final int SAMPLE_BYTES = 4;

  @TempDir
  private Path directory;

  private final WorkerAssertions workers = new WorkerAssertions();

  private final OriginalAudioMetadata metadata = Mockito.mock(OriginalAudioMetadata.class);

  /**
   * Verifies that a session is closed with a status within the timeout, because sessions are closed on another
   * thread.
   *
   * @param session the mocked session
   * @param status  the expected close status
   * @throws IOException never, the mock is only verified
   */
  static void verifyClosed(final WebSocketSession session, final CloseStatus status) throws IOException {
    final VerificationWithTimeout withinTimeout = Mockito.timeout(TIMEOUT_MILLISECONDS);
    final WebSocketSession verified = Mockito.verify(session, withinTimeout);
    verified.close(status);
  }

  /**
   * Restores the logging system property of Spring Boot to the value it had before a test.
   *
   * @param previous the previous value, or null if it was not set
   */
  static void restoreLoggingSystem(final @Nullable String previous) {
    if (previous == null) {
      System.clearProperty(SpringHttpResult.LOGGING_SYSTEM_PROPERTY);
    } else {
      System.setProperty(SpringHttpResult.LOGGING_SYSTEM_PROPERTY, previous);
    }
  }

  private static WebSocketSession session(final String id, final BlockingQueue<byte[]> sent) throws IOException {
    final WebSocketSession session = Mockito.mock(WebSocketSession.class);
    Mockito.when(session.getId()).thenReturn(id);
    Mockito.when(session.isOpen()).thenReturn(true);
    Mockito.doAnswer(invocation -> {
      final WebSocketMessage<?> message = invocation.getArgument(0);
      final BinaryMessage binary = (BinaryMessage) message;
      final ByteBuffer payload = binary.getPayload();
      final int remaining = payload.remaining();
      final byte[] bytes = new byte[remaining];
      payload.get(bytes);
      sent.add(bytes);
      return null;
    })
      .when(session)
      .sendMessage(ArgumentMatchers.any());
    return session;
  }

  private WebSocketSession stuckSession(final CountDownLatch writeEntered, final CountDownLatch releaseWrite) throws IOException {
    final WebSocketSession stuck = Mockito.mock(WebSocketSession.class);
    Mockito.when(stuck.getId()).thenReturn("stuck");
    Mockito.when(stuck.isOpen()).thenReturn(true);
    Mockito.doAnswer(_ -> {
      this.workers.observeCurrentThread();
      writeEntered.countDown();
      final boolean released = releaseWrite.await(TIMEOUT_MILLISECONDS, TimeUnit.MILLISECONDS);
      assertTrue(released, "the test releases the stuck write");
      return null;
    })
      .when(stuck)
      .sendMessage(ArgumentMatchers.any());
    return stuck;
  }

  @AfterEach
  void assertWorkersFinished() throws InterruptedException {
    this.workers.assertFinished();
  }

  private void applySingleByteChunks(final SpringHttpResult http, final int first, final int last) {
    for (int value = first; value <= last; value++) {
      final byte[] chunk = { (byte) value };
      final ByteBuffer samples = ByteBuffer.wrap(chunk);
      http.applyFilter(samples, this.metadata);
    }
  }

  private static void assertReceivedEverySingleByteChunk(final BlockingQueue<byte[]> sent) throws InterruptedException {
    for (int value = 1; value <= LAST_CHUNK_VALUE; value++) {
      final byte[] received = sent.poll(TIMEOUT_MILLISECONDS, TimeUnit.MILLISECONDS);
      final byte[] expected = { (byte) value };
      assertArrayEquals(expected, received);
    }
  }

  @Test
  void shouldUseLocalhostInThePortFactory() {
    final HttpResult http = HttpResult.port(3000);
    final String url = http.getFullUrl();
    final boolean running = http.isRunning();
    assertInstanceOf(SpringHttpResult.class, http);
    assertEquals("http://localhost:3000/", url);
    assertFalse(running);
  }

  @Test
  void shouldUsePortEightyInTheDomainFactory() {
    final HttpResult http = HttpResult.domain(PUBLIC_DOMAIN);
    final String url = http.getFullUrl();
    assertEquals("http://play.example.com:80/", url);
  }

  @Test
  void shouldKeepTheDomainAndPortInTheDirectoryFactory() {
    final HttpResult http = HttpResult.http(EXAMPLE_DOMAIN, MAXIMUM_PORT, this.directory);
    final String url = http.getFullUrl();
    assertEquals("http://example.com:65535/", url);
  }

  @Test
  void shouldRejectInvalidArgumentsInTheFactories() {
    assertThrows(NullPointerException.class, () -> HttpResult.http(null, 80));
    assertThrows(IllegalArgumentException.class, () -> HttpResult.http(BLANK_DOMAIN, 80));
    assertThrows(IllegalArgumentException.class, () -> HttpResult.http(LOCALHOST, 0));
    assertThrows(IllegalArgumentException.class, () -> HttpResult.http(LOCALHOST, MAXIMUM_PORT + 1));
    assertThrows(IllegalArgumentException.class, () -> HttpResult.port(-1));
    assertThrows(NullPointerException.class, () -> HttpResult.domain(null));
    assertThrows(NullPointerException.class, () -> HttpResult.http(null, 80, this.directory));
    assertThrows(IllegalArgumentException.class, () -> HttpResult.http("", 80, this.directory));
    assertThrows(IllegalArgumentException.class, () -> HttpResult.http(LOCALHOST, 0, this.directory));
    assertThrows(IllegalArgumentException.class, () -> HttpResult.http(LOCALHOST, 70000, this.directory));
    assertThrows(NullPointerException.class, () -> HttpResult.http(LOCALHOST, 80, null));
  }

  @Test
  void shouldResolveTheStaticLocationOfThePage() throws IOException {
    final String bundled = SpringHttpResult.staticLocation(null);
    assertEquals(BUNDLED_PAGE_LOCATION, bundled);

    final Path existing = this.directory.resolve("out");
    Files.createDirectory(existing);
    final String existingLocation = SpringHttpResult.staticLocation(existing);
    final URI existingUri = existing.toUri();
    final String existingText = existingUri.toString();
    final boolean endsWithASlash = existingLocation.endsWith(PATH_SEPARATOR);
    assertEquals(existingText, existingLocation);
    assertTrue(endsWithASlash);

    final Path missing = this.directory.resolve("missing");
    final String missingLocation = SpringHttpResult.staticLocation(missing);
    final URI missingUri = missing.toUri();
    final String missingText = missingUri.toString();
    assertEquals(missingText + PATH_SEPARATOR, missingLocation);
  }

  @Test
  void shouldReportTheConfiguredPortAndIgnoreStopWhileNotRunning() {
    final SpringHttpResult http = new SpringHttpResult(LOCALHOST, 4321, null);
    final int port = http.getLocalPort();
    assertEquals(4321, port);
    assertDoesNotThrow(http::stop);
    final boolean running = http.isRunning();
    assertFalse(running);
  }

  @Test
  void shouldTurnAwayAndCloseAHandshakeThatArrivesAfterTheServerStopped() throws Exception {
    final SpringHttpResult http = new SpringHttpResult(LOCALHOST, PORT, null);
    final BlockingQueue<byte[]> sent = new LinkedBlockingQueue<>();
    final WebSocketSession early = session("early", sent);
    http.addListener(early);
    final int countWhileAccepting = http.getListenerCount();
    assertEquals(1, countWhileAccepting);

    http.stop();
    final WebSocketSession late = session("late", sent);
    http.addListener(late);
    final int countAfterStop = http.getListenerCount();
    assertEquals(0, countAfterStop, "stopping closes existing listeners and rejects later handshakes");
    verifyClosed(early, CloseStatus.GOING_AWAY);
    verifyClosed(late, CloseStatus.GOING_AWAY);
  }

  @Test
  void shouldSurviveAHandshakeWhoseSessionCannotBeClosedAnyMore() throws Exception {
    final SpringHttpResult http = new SpringHttpResult(LOCALHOST, PORT, null);
    http.stop();
    final WebSocketSession broken = Mockito.mock(WebSocketSession.class);
    Mockito.when(broken.getId()).thenReturn(BROKEN_SESSION_ID);
    Mockito.doThrow(new IOException("already gone!")).when(broken).close(ArgumentMatchers.any());

    assertDoesNotThrow(() -> http.addListener(broken));
    final int count = http.getListenerCount();
    assertEquals(0, count);
  }

  @Test
  void shouldAcceptSamplesWithoutListeners() {
    final SpringHttpResult http = new SpringHttpResult(LOCALHOST, PORT, null);
    final ByteBuffer samples = ByteBuffer.allocate(SAMPLE_BYTES);
    final boolean changed = http.applyFilter(samples, this.metadata);
    final int count = http.getListenerCount();
    assertFalse(changed, "the filter only reads the sample, so it reports no change");
    assertEquals(0, count);
  }

  @Test
  void shouldRejectNullSamplesAndMetadata() {
    final SpringHttpResult http = new SpringHttpResult(LOCALHOST, PORT, null);
    final ByteBuffer samples = ByteBuffer.allocate(SAMPLE_BYTES);
    assertThrows(NullPointerException.class, () -> http.applyFilter(null, this.metadata));
    assertThrows(NullPointerException.class, () -> http.applyFilter(samples, null));
  }

  @Test
  void shouldSendTheRemainingSamplesToEveryListenerWithoutConsumingThem() throws Exception {
    final SpringHttpResult http = new SpringHttpResult(LOCALHOST, PORT, null);
    final BlockingQueue<byte[]> firstSent = new LinkedBlockingQueue<>();
    final BlockingQueue<byte[]> secondSent = new LinkedBlockingQueue<>();
    final WebSocketSession first = session("first", firstSent);
    final WebSocketSession second = session("second", secondSent);
    http.addListener(first);
    http.addListener(second);
    final int count = http.getListenerCount();
    assertEquals(2, count);

    final byte[] raw = { 9, 9, 1, 2, 3, 4 };
    final ByteBuffer samples = ByteBuffer.wrap(raw);
    samples.position(2);
    final boolean changed = http.applyFilter(samples, this.metadata);
    final int position = samples.position();
    assertFalse(changed, "the filter only reads the sample, so it reports no change");
    assertEquals(2, position);

    final byte[] expected = { 1, 2, 3, 4 };
    final byte[] firstBytes = firstSent.poll(TIMEOUT_MILLISECONDS, TimeUnit.MILLISECONDS);
    final byte[] secondBytes = secondSent.poll(TIMEOUT_MILLISECONDS, TimeUnit.MILLISECONDS);
    assertArrayEquals(expected, firstBytes);
    assertArrayEquals(expected, secondBytes);
    http.removeListener(first);
    http.removeListener(second);
  }

  @Test
  void shouldNotStallTheAudioThreadOrTheOtherListenersWhenAListenerWriteIsBlocked() throws Exception {
    final SpringHttpResult http = new SpringHttpResult(LOCALHOST, PORT, null);
    final CountDownLatch stuckWriteEntered = new CountDownLatch(1);
    final CountDownLatch releaseStuckWrite = new CountDownLatch(1);
    final WebSocketSession stuck = this.stuckSession(stuckWriteEntered, releaseStuckWrite);
    final BlockingQueue<byte[]> healthySent = new LinkedBlockingQueue<>();
    final WebSocketSession healthy = session("healthy", healthySent);
    http.addListener(stuck);
    http.addListener(healthy);
    try {
      this.applySingleByteChunks(http, 1, 1);
      final boolean entered = stuckWriteEntered.await(TIMEOUT_MILLISECONDS, TimeUnit.MILLISECONDS);
      assertTrue(entered);

      final Duration limit = Duration.ofSeconds(2);
      assertTimeoutPreemptively(limit, () -> this.applySingleByteChunks(http, 2, LAST_CHUNK_VALUE));
      assertReceivedEverySingleByteChunk(healthySent);
      final int count = http.getListenerCount();
      assertEquals(2, count);
    } finally {
      releaseStuckWrite.countDown();
      http.removeListener(stuck);
      http.removeListener(healthy);
    }
  }

  @Test
  void shouldStopAListenerWhenRemovingIt() throws Exception {
    final SpringHttpResult http = new SpringHttpResult(LOCALHOST, PORT, null);
    final BlockingQueue<byte[]> sent = new LinkedBlockingQueue<>();
    final WebSocketSession session = session(SESSION_ID, sent);
    http.addListener(session);
    final AudioListener listener = http.getListener(SESSION_ID);
    assertNotNull(listener, "the listener of a connected browser is kept");

    http.removeListener(session);
    final byte[] chunk = { 1, 2, 3, 4 };
    final boolean queued = listener.offer(chunk);
    assertFalse(queued, "a listener that was removed is stopped, so it takes no further chunks");
  }

  @Test
  void shouldCloseTheSpringContextWithTheClassLoaderOfTheLibrary() {
    final ConfigurableApplicationContext context = Mockito.mock(ConfigurableApplicationContext.class);
    final List<ClassLoader> loadersWhileClosing = new ArrayList<>();
    Mockito.doAnswer(invocation -> {
      final Thread closing = Thread.currentThread();
      final ClassLoader loader = closing.getContextClassLoader();
      loadersWhileClosing.add(loader);
      return null;
    })
      .when(context)
      .close();

    final Thread current = Thread.currentThread();
    final ClassLoader before = current.getContextClassLoader();
    final ClassLoader own = SpringHttpResult.class.getClassLoader();
    final ClassLoader caller = new ClassLoader(own) {};
    try {
      current.setContextClassLoader(caller);
      SpringHttpResult.closeWithOwnClassLoader(context);
      final ClassLoader after = current.getContextClassLoader();
      final List<ClassLoader> expected = List.of(own);
      assertEquals(expected, loadersWhileClosing, "Spring looks its own resources up through the context class loader");
      assertSame(caller, after, "the distinct class loader of the caller is restored");
    } finally {
      current.setContextClassLoader(before);
    }
  }

  @Test
  void shouldRestoreTheCallersClassLoaderWhenClosingTheSpringContextFails() {
    final ConfigurableApplicationContext context = Mockito.mock(ConfigurableApplicationContext.class);
    final IllegalStateException failure = new IllegalStateException("context close failed!");
    final List<ClassLoader> loadersWhileClosing = new ArrayList<>();
    Mockito.doAnswer(invocation -> {
      final Thread closing = Thread.currentThread();
      final ClassLoader loader = closing.getContextClassLoader();
      loadersWhileClosing.add(loader);
      throw failure;
    })
      .when(context)
      .close();

    final Thread current = Thread.currentThread();
    final ClassLoader before = current.getContextClassLoader();
    final ClassLoader own = SpringHttpResult.class.getClassLoader();
    final ClassLoader caller = new ClassLoader(own) {};
    try {
      current.setContextClassLoader(caller);
      final IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
        SpringHttpResult.closeWithOwnClassLoader(context)
      );
      final ClassLoader after = current.getContextClassLoader();
      final List<ClassLoader> expected = List.of(own);
      assertSame(failure, thrown, "the original context failure is preserved");
      assertEquals(expected, loadersWhileClosing, "closing runs with the library loader even when it fails");
      assertSame(caller, after, "failed context cleanup must restore the distinct caller loader");
    } finally {
      current.setContextClassLoader(before);
    }
  }

  @Test
  void shouldTakeEveryPortFromOneTo65535InTheHostAndPortFactory() {
    assertThrows(IllegalArgumentException.class, () -> HttpResult.http(EXAMPLE_DOMAIN, 0));
    assertThrows(IllegalArgumentException.class, () -> HttpResult.http(EXAMPLE_DOMAIN, MAXIMUM_PORT + 1));
    assertDoesNotThrow(() -> HttpResult.http(EXAMPLE_DOMAIN, 1));
    assertDoesNotThrow(() -> HttpResult.http(EXAMPLE_DOMAIN, MAXIMUM_PORT));
  }

  @Test
  void shouldIgnoreRemovingAListenerTwice() throws Exception {
    final SpringHttpResult http = new SpringHttpResult(LOCALHOST, PORT, null);
    final BlockingQueue<byte[]> sent = new LinkedBlockingQueue<>();
    final WebSocketSession listener = session(SESSION_ID, sent);
    http.addListener(listener);
    http.removeListener(listener);
    http.removeListener(listener);
    final int count = http.getListenerCount();
    assertEquals(0, count);

    final ByteBuffer samples = ByteBuffer.allocate(SAMPLE_BYTES);
    http.applyFilter(samples, this.metadata);
    final byte[] none = sent.poll(50, TimeUnit.MILLISECONDS);
    assertNull(none);
  }

  @Test
  void shouldDropListenersWhoseSessionClosed() throws Exception {
    final SpringHttpResult http = new SpringHttpResult(LOCALHOST, PORT, null);
    final BlockingQueue<byte[]> sent = new LinkedBlockingQueue<>();
    final WebSocketSession closed = session("closed", sent);
    http.addListener(closed);
    Mockito.when(closed.isOpen()).thenReturn(false);
    final ByteBuffer samples = ByteBuffer.allocate(SAMPLE_BYTES);
    final boolean changed = http.applyFilter(samples, this.metadata);
    final int count = http.getListenerCount();
    assertFalse(changed, "the filter only reads the sample, so it reports no change");
    assertEquals(0, count);
    verifyClosed(closed, CloseStatus.SESSION_NOT_RELIABLE);
  }

  @Test
  void shouldDropListenersWhoseWritesFail() throws Exception {
    final SpringHttpResult http = new SpringHttpResult(LOCALHOST, PORT, null);
    final WebSocketSession broken = Mockito.mock(WebSocketSession.class);
    Mockito.when(broken.getId()).thenReturn(BROKEN_SESSION_ID);
    Mockito.when(broken.isOpen()).thenReturn(true);
    Mockito.doThrow(new IOException("broken pipe!")).when(broken).sendMessage(ArgumentMatchers.any());
    http.addListener(broken);
    final ByteBuffer samples = ByteBuffer.allocate(SAMPLE_BYTES);
    http.applyFilter(samples, this.metadata);
    verifyClosed(broken, CloseStatus.SESSION_NOT_RELIABLE);
    final int count = http.getListenerCount();
    assertEquals(0, count);
  }

  @Test
  void shouldKeepAReplacedListenerWhenItsOldConnectionFails() throws Exception {
    final SpringHttpResult http = new SpringHttpResult(LOCALHOST, PORT, null);
    final WebSocketSession broken = Mockito.mock(WebSocketSession.class);
    Mockito.when(broken.getId()).thenReturn("same-id");
    Mockito.when(broken.isOpen()).thenReturn(true);
    final CountDownLatch replaced = new CountDownLatch(1);
    final CountDownLatch sendEntered = new CountDownLatch(1);
    Mockito.doAnswer(_ -> {
      this.workers.observeCurrentThread();
      sendEntered.countDown();
      final boolean ready = replaced.await(TIMEOUT_MILLISECONDS, TimeUnit.MILLISECONDS);
      assertTrue(ready);
      throw new IOException("broken pipe!");
    })
      .when(broken)
      .sendMessage(ArgumentMatchers.any());
    http.addListener(broken);
    final ByteBuffer samples = ByteBuffer.allocate(SAMPLE_BYTES);
    http.applyFilter(samples, this.metadata);

    final boolean sending = sendEntered.await(TIMEOUT_MILLISECONDS, TimeUnit.MILLISECONDS);
    assertTrue(sending, "the old connection is sending before replacement");
    final BlockingQueue<byte[]> sent = new LinkedBlockingQueue<>();
    final WebSocketSession replacement = session("same-id", sent);
    http.addListener(replacement);
    replaced.countDown();
    verifyClosed(broken, CloseStatus.SESSION_NOT_RELIABLE);
    final int count = http.getListenerCount();
    assertEquals(1, count);
    http.removeListener(replacement);
  }

  @Test
  void shouldShowInformationAboutTheCurrentMedia() {
    final SpringHttpResult http = new SpringHttpResult(LOCALHOST, PORT, null);
    final MediaInformation initial = http.getCurrentMedia();
    assertSame(MediaInformation.EMPTY, initial);

    final UrlParseDump dump = MediaInformationTest.completeDump();
    http.setCurrentMedia(dump);
    final MediaInformation fromDump = http.getCurrentMedia();
    final Map<String, Object> json = fromDump.toJson();
    final Object title = json.get("title");
    assertEquals("Song", title);

    final MediaInformation titled = MediaInformation.titled("clip.mp4");
    http.setCurrentMedia(titled);
    final MediaInformation current = http.getCurrentMedia();
    assertSame(titled, current);

    http.setCurrentMedia((MediaInformation) null);
    final MediaInformation cleared = http.getCurrentMedia();
    assertSame(MediaInformation.EMPTY, cleared);
    assertThrows(NullPointerException.class, () -> http.setCurrentMedia((UrlParseDump) null));
  }

  @Test
  void shouldStartTheBuilderWithTheDefaults() {
    final HttpResultBuilder builder = HttpResult.builder();
    final HttpResult built = builder.build();
    final SpringHttpResult http = assertInstanceOf(SpringHttpResult.class, built);
    final String url = http.getFullUrl();
    final InetAddress bindAddress = http.getBindAddress();
    final Path pageDirectory = http.getDirectory();
    final boolean running = http.isRunning();
    assertEquals("http://localhost:80/", url);
    assertNull(bindAddress, "the server listens on every network interface by default");
    assertNull(pageDirectory, "the bundled page is served by default");
    assertFalse(running);
  }

  @Test
  void shouldApplyEveryBuilderSettingToTheServersItBuilds() throws UnknownHostException {
    final HttpResultBuilder builder = HttpResult.builder();
    final byte[] raw = { 10, 0, 0, 7 };
    final InetAddress address = InetAddress.getByAddress(raw);
    final HttpResultBuilder afterDomain = builder.domain(PUBLIC_DOMAIN);
    final HttpResultBuilder afterPort = builder.port(PORT);
    final HttpResultBuilder afterDirectory = builder.directory(this.directory);
    final HttpResultBuilder afterAddress = builder.bindAddress(address);
    assertSame(builder, afterDomain);
    assertSame(builder, afterPort);
    assertSame(builder, afterDirectory);
    assertSame(builder, afterAddress);

    final HttpResult built = builder.build();
    final SpringHttpResult http = assertInstanceOf(SpringHttpResult.class, built);
    final String url = http.getFullUrl();
    final InetAddress bindAddress = http.getBindAddress();
    final Path pageDirectory = http.getDirectory();
    assertEquals("http://play.example.com:8080/", url);
    assertSame(address, bindAddress);
    assertEquals(this.directory, pageDirectory);

    builder.port(9090);
    final String unchanged = http.getFullUrl();
    assertEquals("http://play.example.com:8080/", unchanged, "a built server keeps its settings");
  }

  @Test
  void shouldRejectInvalidBuilderSettingsAndKeepThePreviousOnes() {
    final HttpResultBuilder builder = HttpResult.builder();
    assertThrows(NullPointerException.class, () -> builder.domain(null));
    assertThrows(IllegalArgumentException.class, () -> builder.domain(BLANK_DOMAIN));
    assertThrows(IllegalArgumentException.class, () -> builder.port(0));
    assertThrows(IllegalArgumentException.class, () -> builder.port(MAXIMUM_PORT + 1));
    assertThrows(NullPointerException.class, () -> builder.directory(null));
    assertThrows(NullPointerException.class, () -> builder.bindAddress(null));

    final HttpResultBuilder lowest = builder.port(1);
    assertSame(builder, lowest);
    final HttpResultBuilder highest = builder.port(MAXIMUM_PORT);
    assertSame(builder, highest);
    final HttpResult http = builder.build();
    final String url = http.getFullUrl();
    assertEquals("http://localhost:65535/", url);
  }

  @Test
  void shouldBracketIpv6AddressesInTheUrl() {
    final HttpResult loopback = HttpResult.http("::1", PORT);
    final HttpResult bracketed = HttpResult.http("[::1]", PORT);
    final HttpResult zoned = HttpResult.http("fe80::1%eth0", PORT);
    final HttpResult ipv4 = HttpResult.http("127.0.0.1", PORT);
    final String loopbackUrl = loopback.getFullUrl();
    final String bracketedUrl = bracketed.getFullUrl();
    final String zonedUrl = zoned.getFullUrl();
    final String ipv4Url = ipv4.getFullUrl();
    assertEquals("http://[::1]:8080/", loopbackUrl);
    assertEquals("http://[::1]:8080/", bracketedUrl);
    assertEquals("http://[fe80::1%25eth0]:8080/", zonedUrl);
    assertEquals("http://127.0.0.1:8080/", ipv4Url);

    final URI parsed = URI.create(loopbackUrl);
    final String host = parsed.getHost();
    final int port = parsed.getPort();
    assertEquals("[::1]", host);
    assertEquals(PORT, port);
  }

  @Test
  void shouldKeepTheLoggingOfTheHostUnlessAnotherLoggingSystemWasChosen() {
    final String property = SpringHttpResult.LOGGING_SYSTEM_PROPERTY;
    assertEquals("org.springframework.boot.logging.LoggingSystem", property);
    final String previous = System.getProperty(property);
    try {
      System.clearProperty(property);
      SpringHttpResult.keepTheLoggingOfTheHost();
      final String disabled = System.getProperty(property);
      assertEquals("none", disabled);

      final String chosen = "org.springframework.boot.logging.java.JavaLoggingSystem";
      System.setProperty(property, chosen);
      SpringHttpResult.keepTheLoggingOfTheHost();
      final String kept = System.getProperty(property);
      assertEquals(chosen, kept, "a logging system chosen by the user is respected");
    } finally {
      restoreLoggingSystem(previous);
    }
  }
}
