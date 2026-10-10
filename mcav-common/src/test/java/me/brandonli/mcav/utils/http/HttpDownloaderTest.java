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
package me.brandonli.mcav.utils.http;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import me.brandonli.mcav.testing.LocalHttpServer;
import me.brandonli.mcav.testing.UtilityClassAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Tests {@link HttpDownloader} against a server on the loopback interface.
 */
final class HttpDownloaderTest {

  private static final byte[] CONTENT = "hello download".getBytes(StandardCharsets.UTF_8);
  private static final String CONTENT_SHA256 = "f13fd89cc6417f1028614173a449ca08607af977ad51d788e8749198273fa7c1";
  private static final Duration NO_DELAY = Duration.ofMillis(1);

  @TempDir
  private Path directory;

  private static URI unusedPort() throws IOException {
    final int port;
    try (final ServerSocket socket = new ServerSocket(0)) {
      port = socket.getLocalPort();
    }
    return URI.create("http://127.0.0.1:" + port + "/file");
  }

  private static String contentSha256() throws IOException {
    final MessageDigest digest = HttpDownloader.createDigest("SHA-256");
    final byte[] hash = digest.digest(CONTENT);
    final HexFormat hex = HexFormat.of();
    return hex.formatHex(hash);
  }

  @Test
  void rejectsUnsupportedUrisBeforeAllocatingAnHttpClient() {
    final URI unsupported = URI.create("file:///tmp/media.mp4");
    try (final MockedStatic<HttpClient> clients = Mockito.mockStatic(HttpClient.class)) {
      assertThrows(IllegalArgumentException.class, () -> HttpDownloader.openStream(unsupported));
      clients.verifyNoInteractions();
    }
  }

  @Test
  void downloadsFilesAtomically() throws IOException {
    try (final LocalHttpServer server = LocalHttpServer.start()) {
      server.respond("/file", 200, CONTENT);
      final URI uri = server.uri("/file");
      final Path destination = this.directory.resolve("nested/file.bin");
      HttpDownloader.download(uri, destination);
      final byte[] content = Files.readAllBytes(destination);
      final Path nested = this.directory.resolve("nested");
      final List<Path> partFiles = partFiles(nested);
      assertArrayEquals(CONTENT, content);
      final boolean partFilesEmpty = partFiles.isEmpty();
      assertTrue(partFilesEmpty, partFiles::toString);
    }
  }

  @Test
  void verifiesTheChecksum() throws IOException {
    final String expected = contentSha256();
    assertEquals(CONTENT_SHA256, expected, "the constant is the SHA-256 of the content");
    try (final LocalHttpServer server = LocalHttpServer.start()) {
      server.respond("/file", 200, CONTENT);
      final URI uri = server.uri("/file");
      final Path verified = this.directory.resolve("verified.bin");
      final Path upperCase = this.directory.resolve("upper.bin");
      final Path blank = this.directory.resolve("blank.bin");
      final Path mismatch = this.directory.resolve("mismatch.bin");
      final String upperExpected = expected.toUpperCase(Locale.ROOT);
      HttpDownloader.download(uri, verified, expected);
      HttpDownloader.download(uri, upperCase, upperExpected);
      HttpDownloader.download(uri, blank, " ");
      final int requestsBeforeMismatch = server.getRequestCount("/file");
      final String wrongHash = CONTENT_SHA256.replace('c', 'd');
      assertThrows(ChecksumMismatchException.class, () -> HttpDownloader.download(uri, mismatch, wrongHash));
      final int requestsAfterMismatch = server.getRequestCount("/file");
      final boolean mismatchExists = Files.exists(mismatch);
      final List<Path> partFiles = partFiles(this.directory);
      assertEquals(requestsBeforeMismatch + 1, requestsAfterMismatch, "a checksum mismatch is not retried");
      assertFalse(mismatchExists);
      final boolean partFilesEmpty = partFiles.isEmpty();
      assertTrue(partFilesEmpty, "a failed attempt leaves no partial file behind");
    }
  }

  @Test
  void retriesServerErrorsAndGivesUpAfterThreeAttempts() throws IOException {
    try (final LocalHttpServer server = LocalHttpServer.start()) {
      server.respond("/flaky", 503, CONTENT);
      server.respond("/flaky", 200, CONTENT);
      server.respond("/broken", 500, CONTENT);
      final URI flaky = server.uri("/flaky");
      final URI broken = server.uri("/broken");
      final Path flakyDestination = this.directory.resolve("flaky.bin");
      final Path brokenDestination = this.directory.resolve("broken.bin");
      HttpDownloader.download(flaky, flakyDestination, null, NO_DELAY);
      final IOException exception = assertThrows(IOException.class, () ->
        HttpDownloader.download(broken, brokenDestination, null, NO_DELAY)
      );
      final Throwable cause = exception.getCause();
      final int flakyRequests = server.getRequestCount("/flaky");
      final int brokenRequests = server.getRequestCount("/broken");
      final boolean flakyExists = Files.exists(flakyDestination);
      assertTrue(flakyExists);
      assertEquals(2, flakyRequests);
      assertEquals(3, brokenRequests);
      assertInstanceOf(HttpStatusException.class, cause);
    }
  }

  @Test
  void keepsTheExistingFileWhenADownloadFails() throws IOException {
    try (final LocalHttpServer server = LocalHttpServer.start()) {
      server.respond("/broken", 500, CONTENT);
      final URI broken = server.uri("/broken");
      final Path destination = this.directory.resolve("existing.bin");
      Files.writeString(destination, "previous version");
      assertThrows(IOException.class, () -> HttpDownloader.download(broken, destination, null, NO_DELAY));
      final String content = Files.readString(destination);
      final List<Path> partFiles = partFiles(this.directory);
      assertEquals("previous version", content, "a failed download never replaces the existing file");
      final boolean partFilesEmpty = partFiles.isEmpty();
      assertTrue(partFilesEmpty, partFiles::toString);
    }
  }

  @Test
  void doesNotRetryClientErrors() {
    try (final LocalHttpServer server = LocalHttpServer.start()) {
      final URI missing = server.uri("/missing");
      final Path destination = this.directory.resolve("missing.bin");
      final HttpStatusException exception = assertThrows(HttpStatusException.class, () -> HttpDownloader.download(missing, destination));
      final int statusCode = exception.getStatusCode();
      final int requests = server.getRequestCount("/missing");
      assertEquals(404, statusCode);
      assertEquals(1, requests);
    }
  }

  @Test
  void stopsADownloadThatIsLargerThanAllowed() throws IOException {
    try (final LocalHttpServer server = LocalHttpServer.start()) {
      final byte[] big = new byte[256 * 1024];
      server.respond("/big", 200, big);
      final URI uri = server.uri("/big");
      final Path destination = this.directory.resolve("big.bin");

      final DownloadTooLargeException failure = assertThrows(DownloadTooLargeException.class, () ->
        HttpDownloader.download(uri, destination, 64L * 1024L)
      );

      final String message = failure.getMessage();
      final boolean namesTheLimit = message.contains("larger than the 65536 bytes");
      assertTrue(namesTheLimit, message);
      final boolean written = Files.exists(destination);
      assertFalse(written, "the file is published only after a complete download");
      final List<Path> leftovers = partFiles(this.directory);
      assertTrue(leftovers.isEmpty(), leftovers::toString);
      final int requests = server.getRequestCount("/big");
      assertEquals(1, requests, "a download that is too large is not retried");
    }
  }

  @Test
  void downloadsAFileThatFitsTheLimit() throws IOException {
    try (final LocalHttpServer server = LocalHttpServer.start()) {
      server.respond("/file", 200, CONTENT);
      final URI uri = server.uri("/file");
      final Path destination = this.directory.resolve("small.bin");

      HttpDownloader.download(uri, destination, CONTENT.length);

      final byte[] content = Files.readAllBytes(destination);
      assertArrayEquals(CONTENT, content, "a download of exactly the limit is kept");
    }
  }

  @Test
  void aPinnedDownloadChecksItsHashAndItsSize() throws IOException {
    try (final LocalHttpServer server = LocalHttpServer.start()) {
      server.respond("/file", 200, CONTENT);
      server.respond("/big", 200, new byte[256 * 1024]);
      final String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(CONTENT));
      final Path destination = this.directory.resolve("pinned.bin");
      HttpDownloader.download(server.uri("/file"), destination, sha256, CONTENT.length);
      assertArrayEquals(CONTENT, Files.readAllBytes(destination));
      final Path big = this.directory.resolve("big.bin");
      assertThrows(DownloadTooLargeException.class, () -> HttpDownloader.download(server.uri("/big"), big, sha256, CONTENT.length));
      assertFalse(Files.exists(big), "nothing larger than the pin is kept");
      final Path other = this.directory.resolve("other.bin");
      final String wrong = "0".repeat(64);
      assertThrows(ChecksumMismatchException.class, () -> HttpDownloader.download(server.uri("/file"), other, wrong, CONTENT.length));
      assertThrows(IllegalArgumentException.class, () -> HttpDownloader.download(server.uri("/file"), other, sha256, 0));
      assertThrows(NullPointerException.class, () -> HttpDownloader.download(server.uri("/file"), other, (String) null, 1));
    } catch (final NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }

  @Test
  void refusesANonPositiveSizeLimit() {
    final URI uri = URI.create("http://example.com/file");
    final Path destination = this.directory.resolve("unused.bin");
    assertThrows(IllegalArgumentException.class, () -> HttpDownloader.download(uri, destination, 0));
  }

  @Test
  void decidesWhichFailuresAreWorthRetrying() {
    final URI uri = URI.create("http://example.com/");
    final IOException network = new IOException("connection reset");
    final IOException checksum = new ChecksumMismatchException("bad hash");
    final IOException tooLarge = new DownloadTooLargeException("too large");
    final boolean networkRetryable = HttpDownloader.isRetryable(network);
    final boolean checksumRetryable = HttpDownloader.isRetryable(checksum);
    final boolean tooLargeRetryable = HttpDownloader.isRetryable(tooLarge);
    final boolean serverRetryable = HttpDownloader.isRetryable(new HttpStatusException(500, uri));
    final boolean timeoutRetryable = HttpDownloader.isRetryable(new HttpStatusException(408, uri));
    final boolean throttledRetryable = HttpDownloader.isRetryable(new HttpStatusException(429, uri));
    final boolean notFoundRetryable = HttpDownloader.isRetryable(new HttpStatusException(404, uri));
    final boolean redirectRetryable = HttpDownloader.isRetryable(new RedirectRefusedException("elsewhere"));
    final boolean slowRetryable = HttpDownloader.isRetryable(new DownloadTooSlowException("trickling"));
    assertTrue(networkRetryable);
    assertFalse(checksumRetryable);
    assertFalse(tooLargeRetryable, "the same bytes would arrive again");
    assertTrue(serverRetryable);
    assertTrue(timeoutRetryable);
    assertTrue(throttledRetryable);
    assertFalse(notFoundRetryable);
    assertFalse(redirectRetryable, "a mirror that redirects elsewhere does so again");
    assertFalse(slowRetryable, "a mirror that trickles does so again");
  }

  @Test
  void aPinnedDownloadFollowsARedirectWithinItsOwnHostOnly() throws IOException {
    try (final LocalHttpServer server = LocalHttpServer.start(); final LocalHttpServer other = LocalHttpServer.start()) {
      server.respond("/file", 200, CONTENT);
      server.respondRedirect("/moved", 302, "/file");
      other.respond("/file", 200, CONTENT);
      server.respondRedirect("/away", 302, other.uri("/file").toString());
      final Path moved = this.directory.resolve("moved.bin");
      HttpDownloader.download(server.uri("/moved"), moved, CONTENT_SHA256, CONTENT.length);
      assertArrayEquals(CONTENT, Files.readAllBytes(moved));
      final Path away = this.directory.resolve("away.bin");
      final IOException refused = assertThrows(IOException.class, () ->
        HttpDownloader.download(server.uri("/away"), away, CONTENT_SHA256, CONTENT.length)
      );
      assertInstanceOf(RedirectRefusedException.class, refused);
      assertEquals(1, server.getRequestCount("/away"), "not tried again");
      assertEquals(0, other.getRequestCount("/file"), "the other host is never asked");
      assertFalse(Files.exists(away));
      HttpDownloader.download(server.uri("/away"), away, CONTENT_SHA256);
      assertArrayEquals(CONTENT, Files.readAllBytes(away));
    }
  }

  @Test
  void aPinnedDownloadFollowsEveryKindOfRedirectFiveTimesAtMost() throws IOException {
    try (final LocalHttpServer server = LocalHttpServer.start()) {
      server.respond("/file", 200, CONTENT);
      for (final int status : new int[] { 301, 302, 303, 307, 308 }) {
        server.respondRedirect("/" + status, status, "/file");
        try (final InputStream body = HttpDownloader.openPinnedStream(server.uri("/" + status))) {
          assertArrayEquals(CONTENT, body.readAllBytes(), "after a " + status);
        }
      }
      server.respondRedirect("/loop", 307, "/loop");
      final IOException loop = assertThrows(IOException.class, () -> openAndClosePinned(server.uri("/loop")));
      assertInstanceOf(RedirectRefusedException.class, loop);
      assertEquals(6, server.getRequestCount("/loop"), "the first request and five redirects");
      server.respond("/nowhere", 302, new byte[0]);
      assertEquals(302, assertThrows(HttpStatusException.class, () -> openAndClosePinned(server.uri("/nowhere"))).getStatusCode());
      server.respondRedirect("/choices", 300, "/file");
      assertEquals(300, assertThrows(HttpStatusException.class, () -> openAndClosePinned(server.uri("/choices"))).getStatusCode());
    }
  }

  private static void openAndClosePinned(final URI uri) throws IOException {
    HttpDownloader.openPinnedStream(uri).close();
  }

  @Test
  void anOriginIsItsSchemeItsHostAndItsPort() {
    final URI page = URI.create("https://mirror.example/debian/pool/a.deb");
    assertTrue(HttpDownloader.isSameOrigin(page, URI.create("https://MIRROR.example:443/file/b.deb")));
    assertTrue(HttpDownloader.isSameOrigin(URI.create("http://mirror.example/a"), URI.create("http://mirror.example:80/b")));
    assertFalse(HttpDownloader.isSameOrigin(page, URI.create("http://mirror.example/a.deb")), "another scheme");
    assertFalse(HttpDownloader.isSameOrigin(page, URI.create("https://other.example/a.deb")), "another host");
    assertFalse(HttpDownloader.isSameOrigin(page, URI.create("https://mirror.example:8443/a.deb")), "another port");
  }

  @Test
  void aPinnedDownloadThatTricklesIsGivenUpAtItsDeadlineWithoutAnotherAttempt() throws IOException {
    try (final LocalHttpServer server = LocalHttpServer.start()) {
      server.respondTrickling("/slow", 200, CONTENT, 50);
      final Path slow = this.directory.resolve("slow.bin");
      final Duration deadline = Duration.ofMillis(200);
      final IOException failure = assertThrows(IOException.class, () ->
        HttpDownloader.download(server.uri("/slow"), slow, CONTENT_SHA256, NO_DELAY, Duration.ofSeconds(10), CONTENT.length, deadline)
      );
      assertInstanceOf(DownloadTooSlowException.class, failure);
      assertEquals(1, server.getRequestCount("/slow"));
      assertFalse(Files.exists(slow));
    }
  }

  @Test
  void aPinnedDownloadOfTheLargestSizeHasADeadlineFarAway() throws IOException {
    try (final LocalHttpServer server = LocalHttpServer.start()) {
      server.respond("/file", 200, CONTENT);
      final Path destination = this.directory.resolve("unbounded.bin");
      HttpDownloader.download(server.uri("/file"), destination, CONTENT_SHA256, HttpDownloader.NO_SIZE_LIMIT);
      assertArrayEquals(CONTENT, Files.readAllBytes(destination));
    }
  }

  @Test
  void aPinnedDownloadMayTakeAMinuteAndASecondForEvery64KibOfItsSize() {
    assertEquals(Duration.ofMinutes(1), HttpDownloader.pinnedDeadline(1));
    assertEquals(Duration.ofSeconds(62), HttpDownloader.pinnedDeadline(2 * 64 * 1024));
    assertEquals(Duration.ofSeconds(60 + 3051), HttpDownloader.pinnedDeadline(200_000_000L));
  }

  @Test
  void stopsRetryingWhenInterrupted() throws IOException {
    final URI unreachable = unusedPort();
    final Path destination = this.directory.resolve("interrupted.bin");
    final Thread current = Thread.currentThread();
    current.interrupt();
    final IOException exception = assertThrows(IOException.class, () -> HttpDownloader.download(unreachable, destination, null, NO_DELAY));
    final boolean interrupted = Thread.interrupted();
    final String message = exception.getMessage();
    assertTrue(interrupted, "the interrupt must be restored");
    final boolean reportsTheInterrupt = message.startsWith("Interrupted");
    assertTrue(reportsTheInterrupt, message);
  }

  @Test
  void rejectsDestinationsWithoutAFileName() {
    final URI uri = URI.create("http://127.0.0.1/file");
    final Path root = this.directory.getRoot();
    final Path destination = this.directory.resolve("file");
    assertThrows(IllegalArgumentException.class, () -> HttpDownloader.download(uri, root));
    assertThrows(NullPointerException.class, () -> HttpDownloader.download(null, destination));
    assertThrows(NullPointerException.class, () -> HttpDownloader.download(uri, null));
  }

  @Test
  void opensStreamsThatCloseTheirClient() throws IOException, InterruptedException {
    try (final LocalHttpServer server = LocalHttpServer.start()) {
      server.respond("/file", 200, CONTENT);
      final URI uri = server.uri("/file");
      final InputStream stream = HttpDownloader.openStream(uri);
      final HttpDownloader.ClientClosingStream closingStream = assertInstanceOf(HttpDownloader.ClientClosingStream.class, stream);
      final HttpClient client = closingStream.getClient();
      try (stream) {
        final int first = stream.read();
        final byte[] rest = new byte[CONTENT.length - 1];
        final int read = stream.readNBytes(rest, 0, rest.length);
        assertEquals(CONTENT[0], first);
        assertEquals(rest.length, read);
      }
      final Duration shutdownLimit = Duration.ofSeconds(5);
      final boolean terminated = client.awaitTermination(shutdownLimit);
      assertTrue(terminated, "closing the stream shuts down its client");
    }
  }

  @Test
  void usesAFreshTemporaryFileForEveryDownload() throws IOException {
    try (final LocalHttpServer server = LocalHttpServer.start()) {
      server.respond("/file", 200, CONTENT);
      final URI uri = server.uri("/file");
      final Path destination = this.directory.resolve("file.bin");
      final Path oldPartName = this.directory.resolve("file.bin.part");
      Files.createDirectories(oldPartName);
      HttpDownloader.download(uri, destination, null, NO_DELAY);
      final byte[] content = Files.readAllBytes(destination);
      final int requests = server.getRequestCount("/file");
      assertArrayEquals(CONTENT, content);
      assertEquals(1, requests);
    }
  }

  @Test
  void downloadsTheSameFileConcurrentlyWithoutMixingTheDownloads() throws Exception {
    final CountDownLatch releaseResponses = new CountDownLatch(1);
    try (final LocalHttpServer server = LocalHttpServer.start()) {
      server.respondWhenOpened("/file", 200, CONTENT, releaseResponses);
      final URI uri = server.uri("/file");
      final Path destination = this.directory.resolve("shared.bin");
      try (final ExecutorService executor = Executors.newFixedThreadPool(2)) {
        final Future<?> first = executor.submit(() -> {
          HttpDownloader.download(uri, destination, CONTENT_SHA256, NO_DELAY);
          return null;
        });
        final Future<?> second = executor.submit(() -> {
          HttpDownloader.download(uri, destination, CONTENT_SHA256, NO_DELAY);
          return null;
        });
        final Duration requestWait = Duration.ofSeconds(10);
        final boolean bothArrived = server.awaitRequests("/file", 2, requestWait);
        assertTrue(bothArrived, "the server did not receive the requests in time");
        releaseResponses.countDown();
        first.get(10, TimeUnit.SECONDS);
        second.get(10, TimeUnit.SECONDS);
      }
      final byte[] content = Files.readAllBytes(destination);
      final List<Path> partFiles = partFiles(this.directory);
      assertArrayEquals(CONTENT, content);
      final boolean partFilesEmpty = partFiles.isEmpty();
      assertTrue(partFilesEmpty, partFiles::toString);
    }
  }

  @Test
  void doesNotRetryAFileThatCannotBeMovedIntoPlace() throws IOException {
    try (final LocalHttpServer server = LocalHttpServer.start()) {
      server.respond("/file", 200, CONTENT);
      final URI uri = server.uri("/file");
      final Path occupied = this.directory.resolve("occupied");
      Files.createDirectories(occupied);
      final Path keptPath = occupied.resolve("kept.txt");
      final Path kept = Files.createFile(keptPath);
      assertThrows(IOException.class, () -> HttpDownloader.download(uri, occupied, null, NO_DELAY));
      final int requests = server.getRequestCount("/file");
      final boolean keptExists = Files.exists(kept);
      final List<Path> partFiles = partFiles(this.directory);
      assertEquals(1, requests, "a failed move is not a network failure and is not retried");
      assertTrue(keptExists);
      final boolean partFilesEmpty = partFiles.isEmpty();
      assertTrue(partFilesEmpty, partFiles::toString);
    }
  }

  @Test
  void givesUpOnABodyThatStopsArriving() {
    try (final LocalHttpServer server = LocalHttpServer.start()) {
      final long announcedLength = CONTENT.length * 10L;
      server.respondStalling("/stalled", 200, CONTENT, announcedLength);
      final URI uri = server.uri("/stalled");
      final Path destination = this.directory.resolve("stalled.bin");
      final Duration idleTimeout = Duration.ofMillis(300);
      final Duration limit = Duration.ofSeconds(30);
      final IOException exception = assertTimeoutPreemptively(limit, () ->
        assertThrows(IOException.class, () ->
          HttpDownloader.download(uri, destination, null, NO_DELAY, idleTimeout, HttpDownloader.NO_SIZE_LIMIT)
        )
      );
      final Throwable cause = exception.getCause();
      final int requests = server.getRequestCount("/stalled");
      final boolean destinationExists = Files.exists(destination);
      assertInstanceOf(HttpTimeoutException.class, cause);
      assertEquals(3, requests, "a stalled body is a network failure and is retried");
      assertFalse(destinationExists);
    }
  }

  @Test
  void stopsAtOnceWhenInterruptedWhileTheBodyArrives() throws Exception {
    try (final LocalHttpServer server = LocalHttpServer.start()) {
      final long announcedLength = CONTENT.length * 10L;
      server.respondStalling("/slow", 200, CONTENT, announcedLength);
      final URI uri = server.uri("/slow");
      final Path destination = this.directory.resolve("slow.bin");
      final Duration longRetryDelay = Duration.ofMinutes(1);
      final Duration longIdleTimeout = Duration.ofMinutes(1);
      final AtomicReference<IOException> failure = new AtomicReference<>();
      final Runnable download = () -> {
        try {
          HttpDownloader.download(uri, destination, null, longRetryDelay, longIdleTimeout, HttpDownloader.NO_SIZE_LIMIT);
        } catch (final IOException exception) {
          failure.set(exception);
        }
      };
      final Thread downloader = new Thread(download, "interrupted download");
      downloader.start();
      final Duration wait = Duration.ofSeconds(10);
      final boolean requested = server.awaitRequests("/slow", 1, wait);

      downloader.interrupt();

      final boolean ended = downloader.join(wait);
      final IOException exception = failure.get();
      final int requests = server.getRequestCount("/slow");
      final List<Path> parts = partFiles(this.directory);
      final boolean partsEmpty = parts.isEmpty();
      final boolean destinationExists = Files.exists(destination);
      assertTrue(requested);
      assertTrue(ended, "an interrupted download waits neither for data nor for a retry");
      assertNotNull(exception);
      assertEquals(1, requests, "an interrupted download is not retried");
      assertTrue(partsEmpty, parts::toString);
      assertFalse(destinationExists);
    }
  }

  private static List<Path> partFiles(final Path folder) throws IOException {
    try (final Stream<Path> files = Files.list(folder)) {
      final Stream<Path> parts = files.filter(file -> {
        final Path fileName = file.getFileName();
        final String name = fileName.toString();
        return name.endsWith(".part") && Files.isRegularFile(file);
      });
      return parts.toList();
    }
  }

  /**
   * Opens the body of a URL and closes it again right away, so a stream that opens unexpectedly never leaks.
   */
  private static void openAndClose(final URI uri) throws IOException {
    try (final InputStream stream = HttpDownloader.openStream(uri)) {
      assertNotNull(stream, "opening either fails or yields a stream");
    }
  }

  @Test
  void closingAResponseStreamClosesItsBodyEvenWhenTheClientOwnsNoExchange() throws IOException {
    final AtomicBoolean closed = new AtomicBoolean();
    final InputStream body = new ByteArrayInputStream(new byte[] { 1 }) {
      @Override
      public void close() throws IOException {
        closed.set(true);
        super.close();
      }
    };
    final HttpClient client = Mockito.mock(HttpClient.class);
    final InputStream response = new HttpDownloader.ClientClosingStream(body, client);
    response.close();
    final boolean bodyClosed = closed.get();
    assertTrue(bodyClosed);
    Mockito.verify(client).shutdownNow();
  }

  @Test
  void openStreamReportsFailures() throws IOException {
    final URI unreachable = unusedPort();
    try (final LocalHttpServer server = LocalHttpServer.start()) {
      final URI missing = server.uri("/missing");
      assertThrows(HttpStatusException.class, () -> openAndClose(missing));
    }
    assertThrows(IOException.class, () -> openAndClose(unreachable));
    assertThrows(NullPointerException.class, () -> openAndClose(null));
    final Thread current = Thread.currentThread();
    current.interrupt();
    final IOException exception = assertThrows(IOException.class, () -> openAndClose(unreachable));
    final boolean interrupted = Thread.interrupted();
    final Throwable cause = exception.getCause();
    assertTrue(interrupted);
    assertInstanceOf(InterruptedException.class, cause);
  }

  @Test
  void fetchesText() throws IOException {
    try (final LocalHttpServer server = LocalHttpServer.start()) {
      server.respond("/text", 200, CONTENT);
      final URI text = server.uri("/text");
      final URI missing = server.uri("/missing");
      final Optional<String> fetched = HttpDownloader.fetchText(text);
      final Optional<String> notFound = HttpDownloader.fetchText(missing);
      final Optional<String> expectedText = Optional.of("hello download");
      final boolean notFoundEmpty = notFound.isEmpty();
      assertEquals(expectedText, fetched);
      assertTrue(notFoundEmpty);
      final Thread current = Thread.currentThread();
      current.interrupt();
      assertThrows(IOException.class, () -> HttpDownloader.fetchText(text));
      final boolean interrupted = Thread.interrupted();
      assertTrue(interrupted);
    }
    assertThrows(NullPointerException.class, () -> HttpDownloader.fetchText(null));
  }

  @Test
  void createsConfiguredClientsAndRequests() {
    final URI uri = URI.create("https://example.com/file");
    final HttpRequest request = HttpDownloader.createRequest(uri);
    final HttpHeaders headers = request.headers();
    final Optional<String> userAgent = headers.firstValue("User-Agent");
    final String method = request.method();
    try (final HttpClient client = HttpDownloader.createClient()) {
      final HttpClient.Redirect redirect = client.followRedirects();
      assertEquals(HttpClient.Redirect.NORMAL, redirect);
    }
    assertEquals("GET", method);
    final boolean userAgentPresent = userAgent.isPresent();
    assertTrue(userAgentPresent);
    assertThrows(NullPointerException.class, () -> HttpDownloader.createRequest(null));
    assertThrows(IOException.class, () -> HttpDownloader.createDigest("NO-SUCH-ALGORITHM"));
  }

  @Test
  void cannotBeInstantiated() throws ReflectiveOperationException {
    UtilityClassAssertions.assertNotInstantiable(HttpDownloader.class);
  }
}
