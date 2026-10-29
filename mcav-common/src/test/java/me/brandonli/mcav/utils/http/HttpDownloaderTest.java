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
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
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
import me.brandonli.mcav.utils.FileOperationException;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

final class HttpDownloaderTest {

  private static final String CONTENT_TEXT = "hello download";
  private static final byte[] CONTENT = CONTENT_TEXT.getBytes(StandardCharsets.UTF_8);
  private static final String CONTENT_SHA256 = "f13fd89cc6417f1028614173a449ca08607af977ad51d788e8749198273fa7c1";
  private static final Duration NO_DELAY = Duration.ofMillis(1);

  private static final String SHA256_ALGORITHM = "SHA-256";

  private static final int MAXIMUM_ATTEMPTS = 3;

  private static final long WAIT_TIMEOUT_SECONDS = 10L;

  private static final int HTTP_OK = 200;
  private static final int HTTP_FOUND = 302;
  private static final int HTTP_NOT_FOUND = 404;
  private static final int HTTP_SERVER_ERROR = 500;

  private static final int BIG_BODY_LENGTH = 256 * 1024;

  private static final long ANNOUNCED_LENGTH_FACTOR = 10L;

  private static final String FILE_ROUTE = "/file";
  private static final String BROKEN_ROUTE = "/broken";
  private static final String SLOW_ROUTE = "/slow";
  private static final String STALLED_ROUTE = "/stalled";
  private static final String BIG_ROUTE = "/big";
  private static final String MISSING_ROUTE = "/missing";
  private static final String MOVED_ROUTE = "/moved";

  private static final String NESTED_DIRECTORY = "nested";
  private static final String NESTED_FILE_PATH = "nested/file.bin";
  private static final String VERIFIED_FILE_NAME = "verified.bin";
  private static final String UPPER_CASE_FILE_NAME = "upper.bin";
  private static final String BLANK_FILE_NAME = "blank.bin";
  private static final String MISMATCH_FILE_NAME = "mismatch.bin";
  private static final String FLAKY_FILE_NAME = "flaky.bin";
  private static final String BROKEN_FILE_NAME = "broken.bin";
  private static final String STALLED_FILE_NAME = "stalled.bin";
  private static final String EXISTING_FILE_NAME = "existing.bin";
  private static final String MISSING_FILE_NAME = "missing.bin";
  private static final String BIG_FILE_NAME = "big.bin";
  private static final String SMALL_FILE_NAME = "small.bin";
  private static final String PINNED_FILE_NAME = "pinned.bin";
  private static final String OTHER_FILE_NAME = "other.bin";
  private static final String UNUSED_FILE_NAME = "unused.bin";
  private static final String MOVED_FILE_NAME = "moved.bin";
  private static final String AWAY_FILE_NAME = "away.bin";
  private static final String SLOW_FILE_NAME = "slow.bin";
  private static final String UNBOUNDED_FILE_NAME = "unbounded.bin";
  private static final String INTERRUPTED_FILE_NAME = "interrupted.bin";
  private static final String BARE_FILE_NAME = "file";
  private static final String FILE_NAME = "file.bin";
  private static final String SHARED_FILE_NAME = "shared.bin";
  private static final String OCCUPIED_DIRECTORY = "occupied";
  private static final String KEPT_FILE_NAME = "kept.txt";
  private static final String BLOCKER_FILE_NAME = "blocker";
  private static final String PART_FILE_EXTENSION = ".part";

  @TempDir
  private Path directory;

  private static URI unusedPort() throws IOException {
    final int port;
    try (final ServerSocket socket = new ServerSocket(0)) {
      port = socket.getLocalPort();
    }

    return URI.create("http://127.0.0.1:" + port + FILE_ROUTE);
  }

  private static String contentSha256() throws IOException {
    final MessageDigest digest = HttpDownloader.createDigest(SHA256_ALGORITHM);
    final byte[] hash = digest.digest(CONTENT);
    final HexFormat hex = HexFormat.of();
    return hex.formatHex(hash);
  }

  @Test
  void shouldRejectUnsupportedUrisBeforeAllocatingAnHttpClient() {
    final URI unsupported = URI.create("file:///tmp/media.mp4");
    try (final MockedStatic<HttpClient> clients = Mockito.mockStatic(HttpClient.class)) {
      assertThrows(IllegalArgumentException.class, () -> HttpDownloader.openStream(unsupported));

      clients.verifyNoInteractions();
    }
  }

  @Test
  void shouldDownloadFilesAtomically() throws IOException {
    try (final LocalHttpServer server = LocalHttpServer.start()) {
      server.respond(FILE_ROUTE, HTTP_OK, CONTENT);
      final URI uri = server.uri(FILE_ROUTE);
      final Path destination = this.directory.resolve(NESTED_FILE_PATH);
      HttpDownloader.download(uri, destination);
      final byte[] content = Files.readAllBytes(destination);
      final Path nested = this.directory.resolve(NESTED_DIRECTORY);
      final List<Path> partFiles = partFiles(nested);
      assertArrayEquals(CONTENT, content);

      final boolean partFilesEmpty = partFiles.isEmpty();
      assertTrue(partFilesEmpty, partFiles::toString);
    }
  }

  @Test
  void shouldVerifyTheChecksum() throws IOException {
    final String expected = contentSha256();
    assertEquals(CONTENT_SHA256, expected, "the constant is the SHA-256 of the content");

    try (final LocalHttpServer server = LocalHttpServer.start()) {
      server.respond(FILE_ROUTE, HTTP_OK, CONTENT);
      final URI uri = server.uri(FILE_ROUTE);
      final Path verified = this.directory.resolve(VERIFIED_FILE_NAME);
      final Path upperCase = this.directory.resolve(UPPER_CASE_FILE_NAME);
      final Path blank = this.directory.resolve(BLANK_FILE_NAME);
      final Path mismatch = this.directory.resolve(MISMATCH_FILE_NAME);
      final String upperExpected = expected.toUpperCase(Locale.ROOT);
      HttpDownloader.download(uri, verified, expected);
      HttpDownloader.download(uri, upperCase, upperExpected);
      HttpDownloader.download(uri, blank, " ");
      final int requestsBeforeMismatch = server.getRequestCount(FILE_ROUTE);
      final String wrongHash = CONTENT_SHA256.replace('c', 'd');
      assertThrows(ChecksumMismatchException.class, () -> HttpDownloader.download(uri, mismatch, wrongHash));

      final int requestsAfterMismatch = server.getRequestCount(FILE_ROUTE);
      final boolean mismatchExists = Files.exists(mismatch);
      final List<Path> partFiles = partFiles(this.directory);
      assertEquals(requestsBeforeMismatch + 1, requestsAfterMismatch, "a checksum mismatch is not retried");
      assertFalse(mismatchExists);

      final boolean partFilesEmpty = partFiles.isEmpty();
      assertTrue(partFilesEmpty, "a failed attempt leaves no partial file behind");
    }
  }

  @Test
  void shouldRetryServerErrorsAndGiveUpAfterThreeAttempts() throws IOException {
    try (final LocalHttpServer server = LocalHttpServer.start()) {
      server.respond("/flaky", 503, CONTENT);
      server.respond("/flaky", HTTP_OK, CONTENT);
      server.respond(BROKEN_ROUTE, HTTP_SERVER_ERROR, CONTENT);
      final URI flaky = server.uri("/flaky");
      final URI broken = server.uri(BROKEN_ROUTE);
      final Path flakyDestination = this.directory.resolve(FLAKY_FILE_NAME);
      final Path brokenDestination = this.directory.resolve(BROKEN_FILE_NAME);
      HttpDownloader.download(flaky, flakyDestination, null, NO_DELAY);
      final DownloadException exception = assertThrows(DownloadException.class, () ->
        HttpDownloader.download(broken, brokenDestination, null, NO_DELAY)
      );
      final Throwable cause = exception.getCause();
      final int flakyRequests = server.getRequestCount("/flaky");
      final int brokenRequests = server.getRequestCount(BROKEN_ROUTE);
      final boolean flakyExists = Files.exists(flakyDestination);
      assertTrue(flakyExists);
      assertEquals(2, flakyRequests);
      assertEquals(MAXIMUM_ATTEMPTS, brokenRequests);
      assertInstanceOf(HttpStatusException.class, cause);
    }
  }

  @Test
  void shouldWaitLongerBeforeEachRetryButNotAfterTheLastAttempt() throws Exception {
    try (final LocalHttpServer server = LocalHttpServer.start(); final ExecutorService executor = Executors.newSingleThreadExecutor()) {
      server.respond(BROKEN_ROUTE, HTTP_SERVER_ERROR, CONTENT);
      final URI broken = server.uri(BROKEN_ROUTE);
      final Path destination = this.directory.resolve(BROKEN_FILE_NAME);
      final Duration retryDelay = Duration.ofMillis(500);
      final long startedAt = System.nanoTime();
      final Future<Long> failedAt = executor.submit(() -> failAndTime(broken, destination, retryDelay));

      final boolean secondAttemptEarly = server.awaitRequests(BROKEN_ROUTE, 2, Duration.ofMillis(250));
      assertFalse(secondAttemptEarly, "the second attempt waits for the retry delay");

      final boolean thirdAttempt = server.awaitRequests(BROKEN_ROUTE, MAXIMUM_ATTEMPTS, Duration.ofSeconds(WAIT_TIMEOUT_SECONDS));
      final long thirdAttemptAt = System.nanoTime();
      final long failedAtNanos = failedAt.get();
      final Duration afterLastAttempt = Duration.ofNanos(failedAtNanos - thirdAttemptAt);
      final boolean failedPromptly = afterLastAttempt.compareTo(retryDelay) < 0;
      assertTrue(thirdAttempt);
      assertTrue(failedPromptly, "no wait after the last attempt, but " + afterLastAttempt);

      final Duration untilFailure = Duration.ofNanos(failedAtNanos - startedAt);
      final Duration growingWaits = retryDelay.multipliedBy(3);
      final boolean waitedLonger = untilFailure.compareTo(growingWaits) >= 0;
      assertTrue(waitedLonger, "the second wait is twice the first, but the attempts took " + untilFailure);
    }
  }

  private static long failAndTime(final URI uri, final Path destination, final Duration retryDelay) {
    assertThrows(DownloadException.class, () -> HttpDownloader.download(uri, destination, null, retryDelay));

    return System.nanoTime();
  }

  @Test
  void shouldGiveUpOnAnErrorWhoseBodyStalls() {
    try (final LocalHttpServer server = LocalHttpServer.start()) {
      server.respondStalling(STALLED_ROUTE, HTTP_SERVER_ERROR, CONTENT, CONTENT.length + 1);
      final URI stalled = server.uri(STALLED_ROUTE);
      final Path destination = this.directory.resolve(STALLED_FILE_NAME);

      final DownloadException failure = assertTimeoutPreemptively(Duration.ofSeconds(WAIT_TIMEOUT_SECONDS), () ->
        assertThrows(DownloadException.class, () -> HttpDownloader.download(stalled, destination, null, NO_DELAY))
      );
      final Throwable cause = failure.getCause();
      assertInstanceOf(HttpStatusException.class, cause);
    }
  }

  @Test
  void shouldKeepTheExistingFileWhenADownloadFails() throws IOException {
    try (final LocalHttpServer server = LocalHttpServer.start()) {
      server.respond(BROKEN_ROUTE, HTTP_SERVER_ERROR, CONTENT);
      final URI broken = server.uri(BROKEN_ROUTE);
      final Path destination = this.directory.resolve(EXISTING_FILE_NAME);
      Files.writeString(destination, "previous version");
      assertThrows(DownloadException.class, () -> HttpDownloader.download(broken, destination, null, NO_DELAY));

      final String content = Files.readString(destination);
      final List<Path> partFiles = partFiles(this.directory);
      assertEquals("previous version", content, "a failed download never replaces the existing file");

      final boolean partFilesEmpty = partFiles.isEmpty();
      assertTrue(partFilesEmpty, partFiles::toString);
    }
  }

  @Test
  @ResourceLock(Resources.SYSTEM_ERR)
  void shouldLogEveryFailedAttempt() throws IOException {
    final PrintStream standardError = System.err;
    final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
    try (final LocalHttpServer server = LocalHttpServer.start()) {
      server.respond(BROKEN_ROUTE, HTTP_SERVER_ERROR, CONTENT);
      final URI broken = server.uri(BROKEN_ROUTE);
      final Path destination = this.directory.resolve(BROKEN_FILE_NAME);
      assertThrows(DownloadException.class, () -> HttpDownloader.download(broken, destination, null, NO_DELAY));
    } finally {
      System.setErr(standardError);
    }

    final String log = captured.toString(StandardCharsets.UTF_8);
    final boolean reported = log.contains("on attempt 3 of 3.");
    assertTrue(reported, log);
  }

  @Test
  void shouldNotRetryClientErrors() {
    try (final LocalHttpServer server = LocalHttpServer.start()) {
      final URI missing = server.uri(MISSING_ROUTE);
      final Path destination = this.directory.resolve(MISSING_FILE_NAME);
      final HttpStatusException exception = assertThrows(HttpStatusException.class, () -> HttpDownloader.download(missing, destination));
      final int statusCode = exception.getStatusCode();
      final int requests = server.getRequestCount(MISSING_ROUTE);
      assertEquals(HTTP_NOT_FOUND, statusCode);
      assertEquals(1, requests);
    }
  }

  @Test
  void shouldStopADownloadThatIsLargerThanAllowed() throws IOException {
    try (final LocalHttpServer server = LocalHttpServer.start()) {
      final byte[] big = new byte[BIG_BODY_LENGTH];
      server.respond(BIG_ROUTE, HTTP_OK, big);
      final URI uri = server.uri(BIG_ROUTE);
      final Path destination = this.directory.resolve(BIG_FILE_NAME);

      final DownloadTooLargeException failure = assertThrows(DownloadTooLargeException.class, () ->
        HttpDownloader.download(uri, destination, 64L * 1024L)
      );

      final String message = failure.getMessage();
      final boolean namesTheLimit = message.contains("larger than the 65536 bytes");
      assertTrue(namesTheLimit, message);

      final boolean written = Files.exists(destination);
      assertFalse(written, "the file is published only after a complete download");

      final List<Path> leftovers = partFiles(this.directory);
      final boolean noLeftovers = leftovers.isEmpty();
      assertTrue(noLeftovers, leftovers::toString);

      final int requests = server.getRequestCount(BIG_ROUTE);
      assertEquals(1, requests, "a download that is too large is not retried");
    }
  }

  @Test
  void shouldDownloadAFileThatFitsTheLimit() throws IOException {
    try (final LocalHttpServer server = LocalHttpServer.start()) {
      server.respond(FILE_ROUTE, HTTP_OK, CONTENT);
      final URI uri = server.uri(FILE_ROUTE);
      final Path destination = this.directory.resolve(SMALL_FILE_NAME);

      HttpDownloader.download(uri, destination, CONTENT.length);

      final byte[] content = Files.readAllBytes(destination);
      assertArrayEquals(CONTENT, content, "a download of exactly the limit is kept");
    }
  }

  @Test
  void shouldCheckTheHashAndTheSizeOfAPinnedDownload() throws IOException {
    try (final LocalHttpServer server = LocalHttpServer.start()) {
      server.respond(FILE_ROUTE, HTTP_OK, CONTENT);
      server.respond(BIG_ROUTE, HTTP_OK, new byte[BIG_BODY_LENGTH]);
      final String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance(SHA256_ALGORITHM).digest(CONTENT));
      final Path destination = this.directory.resolve(PINNED_FILE_NAME);
      HttpDownloader.download(server.uri(FILE_ROUTE), destination, sha256, CONTENT.length);
      final byte[] content = Files.readAllBytes(destination);
      assertArrayEquals(CONTENT, content);

      final Path big = this.directory.resolve(BIG_FILE_NAME);
      assertThrows(DownloadTooLargeException.class, () -> HttpDownloader.download(server.uri(BIG_ROUTE), big, sha256, CONTENT.length));

      final boolean bigKept = Files.exists(big);
      assertFalse(bigKept, "nothing larger than the pin is kept");

      final Path other = this.directory.resolve(OTHER_FILE_NAME);
      final String wrong = "0".repeat(64);
      assertThrows(ChecksumMismatchException.class, () -> HttpDownloader.download(server.uri(FILE_ROUTE), other, wrong, CONTENT.length));
      assertThrows(IllegalArgumentException.class, () -> HttpDownloader.download(server.uri(FILE_ROUTE), other, sha256, 0));
      assertThrows(NullPointerException.class, () -> HttpDownloader.download(server.uri(FILE_ROUTE), other, (String) null, 1));
    } catch (final NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }

  @Test
  void shouldRefuseANonPositiveSizeLimit() {
    final URI uri = URI.create("http://example.com/file");
    final Path destination = this.directory.resolve(UNUSED_FILE_NAME);
    assertThrows(IllegalArgumentException.class, () -> HttpDownloader.download(uri, destination, 0));
  }

  @Test
  void shouldDecideWhichFailuresAreWorthRetrying() {
    final URI uri = URI.create("http://example.com/");
    final IOException network = new IOException("connection reset!");
    final DownloadException checksum = new ChecksumMismatchException("bad hash!");
    final DownloadException tooLarge = new DownloadTooLargeException("too large!");
    final boolean networkRetryable = HttpDownloader.isRetryable(network);
    final boolean checksumRetryable = HttpDownloader.isRetryable(checksum);
    final boolean tooLargeRetryable = HttpDownloader.isRetryable(tooLarge);
    final boolean serverRetryable = HttpDownloader.isRetryable(new HttpStatusException(HTTP_SERVER_ERROR, uri));
    final boolean timeoutRetryable = HttpDownloader.isRetryable(new HttpStatusException(408, uri));
    final boolean throttledRetryable = HttpDownloader.isRetryable(new HttpStatusException(429, uri));
    final boolean notFoundRetryable = HttpDownloader.isRetryable(new HttpStatusException(HTTP_NOT_FOUND, uri));
    final boolean redirectRetryable = HttpDownloader.isRetryable(new RedirectRefusedException("elsewhere!"));
    final boolean slowRetryable = HttpDownloader.isRetryable(new DownloadTooSlowException("trickling!"));
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
  void shouldFollowARedirectOfAPinnedDownloadWithinItsOwnHostOnly() throws IOException {
    try (final LocalHttpServer server = LocalHttpServer.start(); final LocalHttpServer other = LocalHttpServer.start()) {
      server.respond(FILE_ROUTE, HTTP_OK, CONTENT);
      server.respondRedirect(MOVED_ROUTE, HTTP_FOUND, FILE_ROUTE);
      other.respond(FILE_ROUTE, HTTP_OK, CONTENT);
      server.respondRedirect("/away", HTTP_FOUND, other.uri(FILE_ROUTE).toString());
      final Path moved = this.directory.resolve(MOVED_FILE_NAME);
      HttpDownloader.download(server.uri(MOVED_ROUTE), moved, CONTENT_SHA256, CONTENT.length);
      final byte[] movedContent = Files.readAllBytes(moved);
      assertArrayEquals(CONTENT, movedContent);
      final Path away = this.directory.resolve(AWAY_FILE_NAME);
      final DownloadException refused = assertThrows(DownloadException.class, () ->
        HttpDownloader.download(server.uri("/away"), away, CONTENT_SHA256, CONTENT.length)
      );
      assertInstanceOf(RedirectRefusedException.class, refused);
      final int awayRequests = server.getRequestCount("/away");
      assertEquals(1, awayRequests, "not tried again");
      final int otherRequests = other.getRequestCount(FILE_ROUTE);
      assertEquals(0, otherRequests, "the other host is never asked");
      final boolean awayKept = Files.exists(away);
      assertFalse(awayKept);
      HttpDownloader.download(server.uri("/away"), away, CONTENT_SHA256);
      final byte[] awayContent = Files.readAllBytes(away);
      assertArrayEquals(CONTENT, awayContent);
    }
  }

  @Test
  void shouldFollowEveryKindOfRedirectOfAPinnedDownloadFiveTimesAtMost() throws IOException {
    try (final LocalHttpServer server = LocalHttpServer.start()) {
      server.respond(FILE_ROUTE, HTTP_OK, CONTENT);
      for (final int status : new int[] { 301, 302, 303, 307, 308 }) {
        this.assertPinnedRedirectIsFollowed(server, status);
      }

      server.respondRedirect("/loop", 307, "/loop");
      final DownloadException loop = assertThrows(DownloadException.class, () -> openAndClosePinned(server.uri("/loop")));
      assertInstanceOf(RedirectRefusedException.class, loop);

      final int loopRequests = server.getRequestCount("/loop");
      assertEquals(6, loopRequests, "the first request and five redirects");

      server.respond("/nowhere", HTTP_FOUND, new byte[0]);
      final HttpStatusException nowhere = assertThrows(HttpStatusException.class, () -> openAndClosePinned(server.uri("/nowhere")));
      final int nowhereStatus = nowhere.getStatusCode();
      assertEquals(HTTP_FOUND, nowhereStatus);

      server.respondRedirect("/choices", 300, FILE_ROUTE);
      final HttpStatusException choices = assertThrows(HttpStatusException.class, () -> openAndClosePinned(server.uri("/choices")));
      final int choicesStatus = choices.getStatusCode();
      assertEquals(300, choicesStatus);
    }
  }

  private void assertPinnedRedirectIsFollowed(final LocalHttpServer server, final int status) throws IOException {
    server.respondRedirect("/" + status, status, FILE_ROUTE);
    try (final InputStream body = HttpDownloader.openPinnedStream(server.uri("/" + status))) {
      final byte[] content = body.readAllBytes();
      assertArrayEquals(CONTENT, content, "after a " + status);
    }
  }

  @Test
  void shouldFollowARedirectWhoseBodyStalls() {
    try (final LocalHttpServer server = LocalHttpServer.start()) {
      server.respond(FILE_ROUTE, HTTP_OK, CONTENT);
      server.respondStallingRedirect(MOVED_ROUTE, HTTP_FOUND, FILE_ROUTE, CONTENT, CONTENT.length + 1);
      final URI moved = server.uri(MOVED_ROUTE);

      final byte[] content = assertTimeoutPreemptively(Duration.ofSeconds(WAIT_TIMEOUT_SECONDS), () -> readPinned(moved));
      assertArrayEquals(CONTENT, content);
    }
  }

  private static byte[] readPinned(final URI uri) throws IOException {
    try (final InputStream body = HttpDownloader.openPinnedStream(uri)) {
      return body.readAllBytes();
    }
  }

  @Test
  void shouldRefuseARedirectOfAPinnedDownloadToALocationThatIsNotAUri() {
    try (final LocalHttpServer server = LocalHttpServer.start()) {
      server.respondRedirect(BROKEN_ROUTE, HTTP_FOUND, "http://[not-a-host");

      final URI broken = server.uri(BROKEN_ROUTE);
      final DownloadException refused = assertThrows(DownloadException.class, () -> openAndClosePinned(broken));
      assertInstanceOf(RedirectRefusedException.class, refused);

      final Throwable cause = refused.getCause();
      assertInstanceOf(IllegalArgumentException.class, cause);
    }
  }

  private static void openAndClosePinned(final URI uri) throws IOException {
    HttpDownloader.openPinnedStream(uri).close();
  }

  @Test
  void shouldMakeAnOriginOfItsSchemeItsHostAndItsPort() {
    final URI page = URI.create("https://mirror.example/debian/pool/a.deb");
    final boolean defaultHttpsPort = HttpDownloader.isSameOrigin(page, URI.create("https://MIRROR.example:443/file/b.deb"));
    assertTrue(defaultHttpsPort);

    final boolean defaultHttpPort = HttpDownloader.isSameOrigin(
      URI.create("http://mirror.example/a"),
      URI.create("http://mirror.example:80/b")
    );
    assertTrue(defaultHttpPort);

    final boolean otherScheme = HttpDownloader.isSameOrigin(page, URI.create("http://mirror.example/a.deb"));
    assertFalse(otherScheme, "another scheme");

    final boolean otherHost = HttpDownloader.isSameOrigin(page, URI.create("https://other.example/a.deb"));
    assertFalse(otherHost, "another host");

    final boolean otherPort = HttpDownloader.isSameOrigin(page, URI.create("https://mirror.example:8443/a.deb"));
    assertFalse(otherPort, "another port");
  }

  @Test
  void shouldTakePortZeroForAPortOfItsOwn() {
    final URI portZero = URI.create("http://mirror.example:0/a");
    final URI defaultPort = URI.create("http://mirror.example/b");

    final boolean sameOrigin = HttpDownloader.isSameOrigin(portZero, defaultPort);
    assertFalse(sameOrigin);
  }

  @Test
  void shouldGiveUpAPinnedDownloadThatTricklesAtItsDeadlineWithoutAnotherAttempt() throws IOException {
    try (final LocalHttpServer server = LocalHttpServer.start()) {
      server.respondTrickling(SLOW_ROUTE, HTTP_OK, CONTENT, 50);
      final Path slow = this.directory.resolve(SLOW_FILE_NAME);
      final Duration deadline = Duration.ofMillis(200);
      final DownloadException failure = assertThrows(DownloadException.class, () ->
        HttpDownloader.download(server.uri(SLOW_ROUTE), slow, CONTENT_SHA256, NO_DELAY, Duration.ofSeconds(10), CONTENT.length, deadline)
      );
      assertInstanceOf(DownloadTooSlowException.class, failure);

      final int requests = server.getRequestCount(SLOW_ROUTE);
      assertEquals(1, requests);

      final boolean slowKept = Files.exists(slow);
      assertFalse(slowKept);
    }
  }

  @Test
  void shouldGiveAPinnedDownloadOfTheLargestSizeADeadlineFarAway() throws IOException {
    try (final LocalHttpServer server = LocalHttpServer.start()) {
      server.respond(FILE_ROUTE, HTTP_OK, CONTENT);
      final Path destination = this.directory.resolve(UNBOUNDED_FILE_NAME);
      HttpDownloader.download(server.uri(FILE_ROUTE), destination, CONTENT_SHA256, HttpDownloader.NO_SIZE_LIMIT);
      final byte[] content = Files.readAllBytes(destination);
      assertArrayEquals(CONTENT, content);
    }
  }

  @Test
  void shouldGiveAPinnedDownloadAMinuteAndASecondForEvery64KibOfItsSize() {
    final Duration smallestDeadline = HttpDownloader.pinnedDeadline(1);
    assertEquals(Duration.ofMinutes(1), smallestDeadline);

    final Duration twoBlockDeadline = HttpDownloader.pinnedDeadline(2 * 64 * 1024);
    assertEquals(Duration.ofSeconds(62), twoBlockDeadline);

    final Duration largeDeadline = HttpDownloader.pinnedDeadline(200_000_000L);
    assertEquals(Duration.ofSeconds(60 + 3051), largeDeadline);
  }

  @Test
  void shouldKeepDownloadingAtTheLastNanosecondOfTheDeadline() {
    final Duration deadline = Duration.ofMinutes(1);
    final long now = deadline.toNanos();
    final boolean past = HttpDownloader.isPastDeadline(0L, now, deadline);
    assertFalse(past, "the deadline itself is still in time");
  }

  @Test
  void shouldStopRetryingWhenInterrupted() throws IOException {
    final URI unreachable = unusedPort();
    final Path destination = this.directory.resolve(INTERRUPTED_FILE_NAME);
    final Thread current = Thread.currentThread();
    current.interrupt();
    final DownloadException exception = assertThrows(DownloadException.class, () ->
      HttpDownloader.download(unreachable, destination, null, NO_DELAY)
    );
    final boolean interrupted = Thread.interrupted();
    final String message = exception.getMessage();
    assertTrue(interrupted, "the interrupt must be restored");

    final boolean reportsTheInterrupt = message.startsWith("Interrupted");
    assertTrue(reportsTheInterrupt, message);
  }

  @Test
  void shouldRejectDestinationsWithoutAFileName() {
    final URI uri = URI.create("http://127.0.0.1/file");
    final Path root = this.directory.getRoot();
    final Path destination = this.directory.resolve(BARE_FILE_NAME);
    assertThrows(IllegalArgumentException.class, () -> HttpDownloader.download(uri, root));
    assertThrows(NullPointerException.class, () -> HttpDownloader.download(null, destination));
    assertThrows(NullPointerException.class, () -> HttpDownloader.download(uri, null));
  }

  @Test
  void shouldOpenStreamsThatCloseTheirClient() throws IOException, InterruptedException {
    try (final LocalHttpServer server = LocalHttpServer.start()) {
      server.respond(FILE_ROUTE, HTTP_OK, CONTENT);
      final URI uri = server.uri(FILE_ROUTE);
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
  void shouldUseAFreshTemporaryFileForEveryDownload() throws IOException {
    try (final LocalHttpServer server = LocalHttpServer.start()) {
      server.respond(FILE_ROUTE, HTTP_OK, CONTENT);
      final URI uri = server.uri(FILE_ROUTE);
      final Path destination = this.directory.resolve(FILE_NAME);
      final Path oldPartName = this.directory.resolve(FILE_NAME + PART_FILE_EXTENSION);
      Files.createDirectories(oldPartName);
      HttpDownloader.download(uri, destination, null, NO_DELAY);
      final byte[] content = Files.readAllBytes(destination);
      final int requests = server.getRequestCount(FILE_ROUTE);
      assertArrayEquals(CONTENT, content);
      assertEquals(1, requests);
    }
  }

  @Test
  void shouldDownloadTheSameFileConcurrentlyWithoutMixingTheDownloads() throws Exception {
    final CountDownLatch releaseResponses = new CountDownLatch(1);
    try (final LocalHttpServer server = LocalHttpServer.start()) {
      server.respondWhenOpened(FILE_ROUTE, HTTP_OK, CONTENT, releaseResponses);
      final URI uri = server.uri(FILE_ROUTE);
      final Path destination = this.directory.resolve(SHARED_FILE_NAME);
      try (final ExecutorService executor = Executors.newFixedThreadPool(2)) {
        final Future<?> first = executor.submit(() -> this.downloadSharedFile(uri, destination));
        final Future<?> second = executor.submit(() -> this.downloadSharedFile(uri, destination));
        final Duration requestWait = Duration.ofSeconds(WAIT_TIMEOUT_SECONDS);
        final boolean bothArrived = server.awaitRequests(FILE_ROUTE, 2, requestWait);
        assertTrue(bothArrived, "the server did not receive the requests in time");

        releaseResponses.countDown();
        first.get(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        second.get(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      }

      final byte[] content = Files.readAllBytes(destination);
      final List<Path> partFiles = partFiles(this.directory);
      assertArrayEquals(CONTENT, content);

      final boolean partFilesEmpty = partFiles.isEmpty();
      assertTrue(partFilesEmpty, partFiles::toString);
    }
  }

  private @Nullable Void downloadSharedFile(final URI uri, final Path destination) throws IOException {
    HttpDownloader.download(uri, destination, CONTENT_SHA256, NO_DELAY);

    return null;
  }

  @Test
  void shouldNotRetryAFileThatCannotBeMovedIntoPlace() throws IOException {
    try (final LocalHttpServer server = LocalHttpServer.start()) {
      server.respond(FILE_ROUTE, HTTP_OK, CONTENT);
      final URI uri = server.uri(FILE_ROUTE);
      final Path occupied = this.directory.resolve(OCCUPIED_DIRECTORY);
      Files.createDirectories(occupied);
      final Path keptPath = occupied.resolve(KEPT_FILE_NAME);
      final Path kept = Files.createFile(keptPath);
      assertThrows(FileOperationException.class, () -> HttpDownloader.download(uri, occupied, null, NO_DELAY));

      final int requests = server.getRequestCount(FILE_ROUTE);
      final boolean keptExists = Files.exists(kept);
      final List<Path> partFiles = partFiles(this.directory);
      assertEquals(1, requests, "a failed move is not a network failure and is not retried");
      assertTrue(keptExists);

      final boolean partFilesEmpty = partFiles.isEmpty();
      assertTrue(partFilesEmpty, partFiles::toString);
    }
  }

  @Test
  void shouldGiveUpOnABodyThatStopsArriving() {
    try (final LocalHttpServer server = LocalHttpServer.start()) {
      final long announcedLength = CONTENT.length * ANNOUNCED_LENGTH_FACTOR;
      server.respondStalling(STALLED_ROUTE, HTTP_OK, CONTENT, announcedLength);
      final URI uri = server.uri(STALLED_ROUTE);
      final Path destination = this.directory.resolve(STALLED_FILE_NAME);
      final Duration idleTimeout = Duration.ofMillis(300);
      final Duration limit = Duration.ofSeconds(30);
      final DownloadException exception = assertTimeoutPreemptively(limit, () ->
        this.assertStalledDownloadFails(uri, destination, idleTimeout)
      );
      final Throwable cause = exception.getCause();
      final int requests = server.getRequestCount(STALLED_ROUTE);
      final boolean destinationExists = Files.exists(destination);
      assertInstanceOf(HttpTimeoutException.class, cause);
      assertEquals(MAXIMUM_ATTEMPTS, requests, "a stalled body is a network failure and is retried");
      assertFalse(destinationExists);
    }
  }

  private DownloadException assertStalledDownloadFails(final URI uri, final Path destination, final Duration idleTimeout) {
    return assertThrows(DownloadException.class, () ->
      HttpDownloader.download(uri, destination, null, NO_DELAY, idleTimeout, HttpDownloader.NO_SIZE_LIMIT)
    );
  }

  @Test
  void shouldStopAtOnceWhenInterruptedWhileTheBodyArrives() throws Exception {
    try (final LocalHttpServer server = LocalHttpServer.start()) {
      final long announcedLength = CONTENT.length * ANNOUNCED_LENGTH_FACTOR;
      server.respondStalling(SLOW_ROUTE, HTTP_OK, CONTENT, announcedLength);
      final URI uri = server.uri(SLOW_ROUTE);
      final Path destination = this.directory.resolve(SLOW_FILE_NAME);
      final Duration longRetryDelay = Duration.ofMinutes(1);
      final Duration longIdleTimeout = Duration.ofMinutes(1);
      final AtomicReference<DownloadException> failure = new AtomicReference<>();
      final Runnable download = () -> this.downloadRecordingFailure(uri, destination, longRetryDelay, longIdleTimeout, failure);
      final Thread downloader = new Thread(download, "interrupted download");
      downloader.start();
      final Duration wait = Duration.ofSeconds(WAIT_TIMEOUT_SECONDS);
      final boolean requested = server.awaitRequests(SLOW_ROUTE, 1, wait);

      downloader.interrupt();

      final boolean ended = downloader.join(wait);
      final DownloadException exception = failure.get();
      final int requests = server.getRequestCount(SLOW_ROUTE);
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

  private void downloadRecordingFailure(
    final URI uri,
    final Path destination,
    final Duration retryDelay,
    final Duration idleTimeout,
    final AtomicReference<DownloadException> failure
  ) {
    try {
      HttpDownloader.download(uri, destination, null, retryDelay, idleTimeout, HttpDownloader.NO_SIZE_LIMIT);
    } catch (final DownloadException exception) {
      failure.set(exception);
    }
  }

  private static List<Path> partFiles(final Path folder) throws IOException {
    try (final Stream<Path> files = Files.list(folder)) {
      final Stream<Path> parts = files.filter(HttpDownloaderTest::isPartFile);
      return parts.toList();
    }
  }

  private static boolean isPartFile(final Path file) {
    final Path fileName = file.getFileName();
    final String name = fileName.toString();
    return name.endsWith(PART_FILE_EXTENSION) && Files.isRegularFile(file);
  }

  // Opens the body of a URL and closes it again right away, so a stream that opens unexpectedly never leaks.
  private static void openAndClose(final URI uri) throws IOException {
    try (final InputStream stream = HttpDownloader.openStream(uri)) {
      assertNotNull(stream, "opening either fails or yields a stream");
    }
  }

  @Test
  void shouldCloseTheBodyOfAResponseStreamEvenWhenTheClientOwnsNoExchange() throws IOException {
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
  void shouldReportFailuresOfOpenStream() throws IOException {
    final URI unreachable = unusedPort();
    try (final LocalHttpServer server = LocalHttpServer.start()) {
      final URI missing = server.uri(MISSING_ROUTE);
      assertThrows(HttpStatusException.class, () -> openAndClose(missing));
    }

    assertThrows(DownloadException.class, () -> openAndClose(unreachable));
    assertThrows(NullPointerException.class, () -> openAndClose(null));

    final Thread current = Thread.currentThread();
    current.interrupt();
    final DownloadException exception = assertThrows(DownloadException.class, () -> openAndClose(unreachable));
    final boolean interrupted = Thread.interrupted();
    final Throwable cause = exception.getCause();
    assertTrue(interrupted);
    assertInstanceOf(InterruptedException.class, cause);
  }

  @Test
  void shouldReportAPartFileThatCannotBeCreated() throws IOException {
    final Path blocker = Files.createFile(this.directory.resolve(BLOCKER_FILE_NAME));
    final Path destination = blocker.resolve(FILE_NAME);
    final URI unreachable = unusedPort();
    final FileOperationException thrown = assertThrows(FileOperationException.class, () ->
      HttpDownloader.download(unreachable, destination)
    );
    final Throwable cause = thrown.getCause();
    assertInstanceOf(FileAlreadyExistsException.class, cause);
  }

  @Test
  void shouldReportAFetchThatCannotConnect() throws IOException {
    final URI unreachable = unusedPort();
    final DownloadException thrown = assertThrows(DownloadException.class, () -> HttpDownloader.fetchText(unreachable));
    final Throwable cause = thrown.getCause();
    assertInstanceOf(IOException.class, cause);
  }

  @Test
  void shouldFetchTheBodyOrNothingForAnErrorStatus() throws IOException {
    try (final LocalHttpServer server = LocalHttpServer.start()) {
      server.respond("/text", HTTP_OK, CONTENT);
      final URI text = server.uri("/text");
      final URI missing = server.uri(MISSING_ROUTE);
      final Optional<String> fetched = HttpDownloader.fetchText(text);
      final Optional<String> notFound = HttpDownloader.fetchText(missing);
      final Optional<String> expectedText = Optional.of(CONTENT_TEXT);
      final boolean notFoundEmpty = notFound.isEmpty();
      assertEquals(expectedText, fetched);
      assertTrue(notFoundEmpty);
    }

    assertThrows(NullPointerException.class, () -> HttpDownloader.fetchText(null));
  }

  @Test
  void shouldKeepTheInterruptFlagWhenAFetchIsInterrupted() throws IOException {
    try (final LocalHttpServer server = LocalHttpServer.start()) {
      server.respond("/text", HTTP_OK, CONTENT);
      final URI text = server.uri("/text");
      final Thread current = Thread.currentThread();
      current.interrupt();
      assertThrows(DownloadException.class, () -> HttpDownloader.fetchText(text));

      final boolean interrupted = Thread.interrupted();
      assertTrue(interrupted);
    }
  }

  @Test
  void shouldCreateConfiguredClientsAndRequests() {
    final URI uri = URI.create("https://example.com/file");
    final HttpRequest request = HttpDownloader.createRequest(uri);
    final HttpHeaders headers = request.headers();
    final Optional<String> userAgent = headers.firstValue("User-Agent");
    final String method = request.method();
    final Optional<Duration> requestTimeout = request.timeout();
    try (final HttpClient client = HttpDownloader.createClient()) {
      final HttpClient.Redirect redirect = client.followRedirects();
      final Optional<Duration> connectTimeout = client.connectTimeout();
      assertEquals(HttpClient.Redirect.NORMAL, redirect);
      assertEquals(Optional.of(Duration.ofSeconds(20)), connectTimeout);
    }

    assertEquals("GET", method);
    assertEquals(Optional.of(Duration.ofMinutes(30)), requestTimeout);

    final String agent = userAgent.orElse("");
    final boolean namesMcav = agent.startsWith("mcav ");
    assertTrue(namesMcav, "the user agent names mcav, but was " + agent);
    assertThrows(NullPointerException.class, () -> HttpDownloader.createRequest(null));
  }

  @Test
  void shouldReportADigestAlgorithmThatDoesNotExist() {
    assertThrows(DownloadException.class, () -> HttpDownloader.createDigest("NO-SUCH-ALGORITHM"));
  }

  @Test
  void shouldNotBeInstantiated() throws ReflectiveOperationException {
    UtilityClassAssertions.assertNotInstantiable(HttpDownloader.class);
  }
}
