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
import me.brandonli.mcav.json.ytdlp.format.URLParseDump;
import me.brandonli.mcav.media.player.metadata.OriginalAudioMetadata;
import org.checkerframework.checker.nullness.qual.Nullable;
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
 * Tests {@link HttpResultImpl} and the {@link HttpResult} factories without starting a web server; the running
 * server is tested by {@link HttpServerTest}.
 */
final class HttpResultImplTest {

  private static final long TIMEOUT_MILLIS = 10_000;
  private static final int LAST_CHUNK_VALUE = 20;

  @TempDir
  private Path directory;

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
    final VerificationWithTimeout withinTimeout = Mockito.timeout(TIMEOUT_MILLIS);
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
      System.clearProperty(HttpResultImpl.LOGGING_SYSTEM_PROPERTY);
    } else {
      System.setProperty(HttpResultImpl.LOGGING_SYSTEM_PROPERTY, previous);
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

  private static WebSocketSession stuckSession(final CountDownLatch writeEntered, final CountDownLatch releaseWrite) throws IOException {
    final WebSocketSession stuck = Mockito.mock(WebSocketSession.class);
    Mockito.when(stuck.getId()).thenReturn("stuck");
    Mockito.when(stuck.isOpen()).thenReturn(true);
    Mockito.doAnswer(_ -> {
      writeEntered.countDown();
      final boolean released = releaseWrite.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
      assertTrue(released, "the test releases the stuck write");
      return null;
    })
      .when(stuck)
      .sendMessage(ArgumentMatchers.any());
    return stuck;
  }

  private void applySingleByteChunks(final HttpResultImpl http, final int first, final int last) {
    for (int value = first; value <= last; value++) {
      final byte[] chunk = { (byte) value };
      final ByteBuffer samples = ByteBuffer.wrap(chunk);
      http.applyFilter(samples, this.metadata);
    }
  }

  private static void assertReceivedEverySingleByteChunk(final BlockingQueue<byte[]> sent) throws InterruptedException {
    for (int value = 1; value <= LAST_CHUNK_VALUE; value++) {
      final byte[] received = sent.poll(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
      final byte[] expected = { (byte) value };
      assertArrayEquals(expected, received);
    }
  }

  @Test
  void portFactoryUsesLocalhost() {
    final HttpResult http = HttpResult.port(3000);
    final String url = http.getFullUrl();
    final boolean running = http.isRunning();
    assertInstanceOf(HttpResultImpl.class, http);
    assertEquals("http://localhost:3000/", url);
    assertFalse(running);
  }

  @Test
  void domainFactoryUsesPortEighty() {
    final HttpResult http = HttpResult.domain("play.example.com");
    final String url = http.getFullUrl();
    assertEquals("http://play.example.com:80/", url);
  }

  @Test
  void directoryFactoryKeepsDomainAndPort() {
    final HttpResult http = HttpResult.http("example.com", 65535, this.directory);
    final String url = http.getFullUrl();
    assertEquals("http://example.com:65535/", url);
  }

  @Test
  void factoriesRejectInvalidArguments() {
    assertThrows(NullPointerException.class, () -> HttpResult.http(null, 80));
    assertThrows(IllegalArgumentException.class, () -> HttpResult.http(" ", 80));
    assertThrows(IllegalArgumentException.class, () -> HttpResult.http("localhost", 0));
    assertThrows(IllegalArgumentException.class, () -> HttpResult.http("localhost", 65536));
    assertThrows(IllegalArgumentException.class, () -> HttpResult.port(-1));
    assertThrows(NullPointerException.class, () -> HttpResult.domain(null));
    assertThrows(NullPointerException.class, () -> HttpResult.http(null, 80, this.directory));
    assertThrows(IllegalArgumentException.class, () -> HttpResult.http("", 80, this.directory));
    assertThrows(IllegalArgumentException.class, () -> HttpResult.http("localhost", 0, this.directory));
    assertThrows(IllegalArgumentException.class, () -> HttpResult.http("localhost", 70000, this.directory));
    assertThrows(NullPointerException.class, () -> HttpResult.http("localhost", 80, null));
  }

  @Test
  void resolvesTheStaticLocationOfThePage() throws IOException {
    final String bundled = HttpResultImpl.staticLocation(null);
    assertEquals("classpath:/static/", bundled);

    final Path existing = this.directory.resolve("out");
    Files.createDirectory(existing);
    final String existingLocation = HttpResultImpl.staticLocation(existing);
    final URI existingUri = existing.toUri();
    final String existingText = existingUri.toString();
    assertEquals(existingText, existingLocation);
    assertTrue(existingLocation.endsWith("/"));

    final Path missing = this.directory.resolve("missing");
    final String missingLocation = HttpResultImpl.staticLocation(missing);
    final URI missingUri = missing.toUri();
    final String missingText = missingUri.toString();
    assertEquals(missingText + "/", missingLocation);
  }

  @Test
  void reportsTheConfiguredPortAndIgnoresStopWhileNotRunning() {
    final HttpResultImpl http = new HttpResultImpl("localhost", 4321, null);
    final int port = http.getLocalPort();
    assertEquals(4321, port);
    assertDoesNotThrow(http::stop);
    final boolean running = http.isRunning();
    assertFalse(running);
  }

  @Test
  void turnsAwayAndClosesAHandshakeThatArrivesAfterTheServerStopped() throws Exception {
    final HttpResultImpl http = new HttpResultImpl("localhost", 8080, null);
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
  void survivesAHandshakeWhoseSessionCannotBeClosedAnyMore() throws Exception {
    final HttpResultImpl http = new HttpResultImpl("localhost", 8080, null);
    http.stop();
    final WebSocketSession broken = Mockito.mock(WebSocketSession.class);
    Mockito.when(broken.getId()).thenReturn("broken");
    Mockito.doThrow(new IOException("already gone")).when(broken).close(ArgumentMatchers.any());

    assertDoesNotThrow(() -> http.addListener(broken));
    final int count = http.getListenerCount();
    assertEquals(0, count);
  }

  @Test
  void acceptsSamplesWithoutListeners() {
    final HttpResultImpl http = new HttpResultImpl("localhost", 8080, null);
    final ByteBuffer samples = ByteBuffer.allocate(16);
    final boolean result = http.applyFilter(samples, this.metadata);
    final int count = http.getListenerCount();
    assertFalse(result, "the filter only reads the sample, so it reports no change");
    assertEquals(0, count);
  }

  @Test
  void rejectsNullSamplesAndMetadata() {
    final HttpResultImpl http = new HttpResultImpl("localhost", 8080, null);
    final ByteBuffer samples = ByteBuffer.allocate(16);
    assertThrows(NullPointerException.class, () -> http.applyFilter(null, this.metadata));
    assertThrows(NullPointerException.class, () -> http.applyFilter(samples, null));
  }

  @Test
  void sendsTheRemainingSamplesToEveryListenerWithoutConsumingThem() throws Exception {
    final HttpResultImpl http = new HttpResultImpl("localhost", 8080, null);
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
    final boolean result = http.applyFilter(samples, this.metadata);
    final int position = samples.position();
    assertFalse(result, "the filter only reads the sample, so it reports no change");
    assertEquals(2, position);

    final byte[] expected = { 1, 2, 3, 4 };
    final byte[] firstBytes = firstSent.poll(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
    final byte[] secondBytes = secondSent.poll(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
    assertArrayEquals(expected, firstBytes);
    assertArrayEquals(expected, secondBytes);
    http.removeListener(first);
    http.removeListener(second);
  }

  @Test
  void aListenerWithABlockedWriteDoesNotStallTheAudioThreadOrTheOtherListeners() throws Exception {
    final HttpResultImpl http = new HttpResultImpl("localhost", 8080, null);
    final CountDownLatch stuckWriteEntered = new CountDownLatch(1);
    final CountDownLatch releaseStuckWrite = new CountDownLatch(1);
    final WebSocketSession stuck = stuckSession(stuckWriteEntered, releaseStuckWrite);
    final BlockingQueue<byte[]> healthySent = new LinkedBlockingQueue<>();
    final WebSocketSession healthy = session("healthy", healthySent);
    http.addListener(stuck);
    http.addListener(healthy);
    try {
      this.applySingleByteChunks(http, 1, 1);
      final boolean entered = stuckWriteEntered.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
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
  void removingAListenerStopsIt() throws Exception {
    final HttpResultImpl http = new HttpResultImpl("localhost", 8080, null);
    final BlockingQueue<byte[]> sent = new LinkedBlockingQueue<>();
    final WebSocketSession session = session("listener", sent);
    http.addListener(session);
    final AudioListener listener = http.getListener("listener");
    assertNotNull(listener, "the listener of a connected browser is kept");

    http.removeListener(session);
    final byte[] chunk = { 1, 2, 3, 4 };
    final boolean queued = listener.offer(chunk);
    assertFalse(queued, "a listener that was removed is stopped, so it takes no further chunks");
  }

  @Test
  void closesTheSpringContextWithTheClassLoaderOfTheLibrary() {
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
    final ClassLoader own = HttpResultImpl.class.getClassLoader();
    final ClassLoader caller = new ClassLoader(own) {};
    try {
      current.setContextClassLoader(caller);
      HttpResultImpl.closeWithOwnClassLoader(context);
      final ClassLoader after = current.getContextClassLoader();
      final List<ClassLoader> expected = List.of(own);
      assertEquals(expected, loadersWhileClosing, "Spring looks its own resources up through the context class loader");
      assertSame(caller, after, "the distinct class loader of the caller is restored");
    } finally {
      current.setContextClassLoader(before);
    }
  }

  @Test
  void restoresTheCallersClassLoaderWhenClosingTheSpringContextFails() {
    final ConfigurableApplicationContext context = Mockito.mock(ConfigurableApplicationContext.class);
    final IllegalStateException failure = new IllegalStateException("context close failed");
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
    final ClassLoader own = HttpResultImpl.class.getClassLoader();
    final ClassLoader caller = new ClassLoader(own) {};
    try {
      current.setContextClassLoader(caller);
      final IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> HttpResultImpl.closeWithOwnClassLoader(context));
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
  void theHostAndPortFactoryTakesEveryPortFromOneTo65535() {
    assertThrows(IllegalArgumentException.class, () -> HttpResult.http("example.com", 0));
    assertThrows(IllegalArgumentException.class, () -> HttpResult.http("example.com", 65536));
    assertDoesNotThrow(() -> HttpResult.http("example.com", 1));
    assertDoesNotThrow(() -> HttpResult.http("example.com", 65535));
  }

  @Test
  void removingAListenerTwiceIsHarmless() throws Exception {
    final HttpResultImpl http = new HttpResultImpl("localhost", 8080, null);
    final BlockingQueue<byte[]> sent = new LinkedBlockingQueue<>();
    final WebSocketSession listener = session("listener", sent);
    http.addListener(listener);
    http.removeListener(listener);
    http.removeListener(listener);
    final int count = http.getListenerCount();
    assertEquals(0, count);

    final ByteBuffer samples = ByteBuffer.allocate(4);
    http.applyFilter(samples, this.metadata);
    final byte[] none = sent.poll(50, TimeUnit.MILLISECONDS);
    assertNull(none);
  }

  @Test
  void dropsListenersWhoseSessionClosed() throws Exception {
    final HttpResultImpl http = new HttpResultImpl("localhost", 8080, null);
    final BlockingQueue<byte[]> sent = new LinkedBlockingQueue<>();
    final WebSocketSession closed = session("closed", sent);
    http.addListener(closed);
    Mockito.when(closed.isOpen()).thenReturn(false);
    final ByteBuffer samples = ByteBuffer.allocate(4);
    final boolean result = http.applyFilter(samples, this.metadata);
    final int count = http.getListenerCount();
    assertFalse(result, "the filter only reads the sample, so it reports no change");
    assertEquals(0, count);
    verifyClosed(closed, CloseStatus.SESSION_NOT_RELIABLE);
  }

  @Test
  void dropsListenersWhoseWritesFail() throws Exception {
    final HttpResultImpl http = new HttpResultImpl("localhost", 8080, null);
    final WebSocketSession broken = Mockito.mock(WebSocketSession.class);
    Mockito.when(broken.getId()).thenReturn("broken");
    Mockito.when(broken.isOpen()).thenReturn(true);
    Mockito.doThrow(new IOException("broken pipe")).when(broken).sendMessage(ArgumentMatchers.any());
    http.addListener(broken);
    final ByteBuffer samples = ByteBuffer.allocate(4);
    http.applyFilter(samples, this.metadata);
    // the listener is removed before the thread that closes its session starts
    verifyClosed(broken, CloseStatus.SESSION_NOT_RELIABLE);
    final int count = http.getListenerCount();
    assertEquals(0, count);
  }

  @Test
  void aReplacedListenerIsNotRemovedWhenItsOldConnectionFails() throws Exception {
    final HttpResultImpl http = new HttpResultImpl("localhost", 8080, null);
    final WebSocketSession broken = Mockito.mock(WebSocketSession.class);
    Mockito.when(broken.getId()).thenReturn("same-id");
    Mockito.when(broken.isOpen()).thenReturn(true);
    final CountDownLatch replaced = new CountDownLatch(1);
    Mockito.doAnswer(_ -> {
      final boolean ready = replaced.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
      assertTrue(ready);
      throw new IOException("broken pipe");
    })
      .when(broken)
      .sendMessage(ArgumentMatchers.any());
    http.addListener(broken);
    final ByteBuffer samples = ByteBuffer.allocate(4);
    http.applyFilter(samples, this.metadata);

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
  void showsInformationAboutTheCurrentMedia() {
    final HttpResultImpl http = new HttpResultImpl("localhost", 8080, null);
    final MediaInfo initial = http.getCurrentMedia();
    assertSame(MediaInfo.EMPTY, initial);

    final URLParseDump dump = MediaInfoTest.completeDump();
    http.setCurrentMedia(dump);
    final MediaInfo fromDump = http.getCurrentMedia();
    final Map<String, Object> json = fromDump.toJson();
    final Object title = json.get("title");
    assertEquals("Song", title);

    final MediaInfo titled = MediaInfo.titled("clip.mp4");
    http.setCurrentMedia(titled);
    final MediaInfo current = http.getCurrentMedia();
    assertSame(titled, current);

    http.setCurrentMedia((MediaInfo) null);
    final MediaInfo cleared = http.getCurrentMedia();
    assertSame(MediaInfo.EMPTY, cleared);
    assertThrows(NullPointerException.class, () -> http.setCurrentMedia((URLParseDump) null));
  }

  @Test
  void builderStartsWithTheDefaults() {
    final HttpResultBuilder builder = HttpResult.builder();
    final HttpResult built = builder.build();
    final HttpResultImpl http = assertInstanceOf(HttpResultImpl.class, built);
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
  void builderAppliesEverySettingToTheServersItBuilds() throws UnknownHostException {
    final HttpResultBuilder builder = HttpResult.builder();
    final byte[] raw = { 10, 0, 0, 7 };
    final InetAddress address = InetAddress.getByAddress(raw);
    final HttpResultBuilder afterDomain = builder.domain("play.example.com");
    final HttpResultBuilder afterPort = builder.port(8080);
    final HttpResultBuilder afterDirectory = builder.directory(this.directory);
    final HttpResultBuilder afterAddress = builder.bindAddress(address);
    assertSame(builder, afterDomain);
    assertSame(builder, afterPort);
    assertSame(builder, afterDirectory);
    assertSame(builder, afterAddress);

    final HttpResult built = builder.build();
    final HttpResultImpl http = assertInstanceOf(HttpResultImpl.class, built);
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
  void builderRejectsInvalidSettingsAndKeepsThePreviousOnes() {
    final HttpResultBuilder builder = HttpResult.builder();
    assertThrows(NullPointerException.class, () -> builder.domain(null));
    assertThrows(IllegalArgumentException.class, () -> builder.domain(" "));
    assertThrows(IllegalArgumentException.class, () -> builder.port(0));
    assertThrows(IllegalArgumentException.class, () -> builder.port(65536));
    assertThrows(NullPointerException.class, () -> builder.directory(null));
    assertThrows(NullPointerException.class, () -> builder.bindAddress(null));

    final HttpResultBuilder lowest = builder.port(1);
    assertSame(builder, lowest);
    final HttpResultBuilder highest = builder.port(65535);
    assertSame(builder, highest);
    final HttpResult http = builder.build();
    final String url = http.getFullUrl();
    assertEquals("http://localhost:65535/", url);
  }

  @Test
  void bracketsIpv6AddressesInTheUrl() {
    final HttpResult loopback = HttpResult.http("::1", 8080);
    final HttpResult bracketed = HttpResult.http("[::1]", 8080);
    final HttpResult zoned = HttpResult.http("fe80::1%eth0", 8080);
    final HttpResult ipv4 = HttpResult.http("127.0.0.1", 8080);
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
    assertEquals(8080, port);
  }

  @Test
  void keepsTheLoggingOfTheHostUnlessAnotherLoggingSystemWasChosen() {
    final String property = HttpResultImpl.LOGGING_SYSTEM_PROPERTY;
    assertEquals("org.springframework.boot.logging.LoggingSystem", property);
    final String previous = System.getProperty(property);
    try {
      System.clearProperty(property);
      HttpResultImpl.keepTheLoggingOfTheHost();
      final String disabled = System.getProperty(property);
      assertEquals("none", disabled);

      final String chosen = "org.springframework.boot.logging.java.JavaLoggingSystem";
      System.setProperty(property, chosen);
      HttpResultImpl.keepTheLoggingOfTheHost();
      final String kept = System.getProperty(property);
      assertEquals(chosen, kept, "a logging system chosen by the user is respected");
    } finally {
      restoreLoggingSystem(previous);
    }
  }
}
