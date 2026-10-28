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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.Striped;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import me.brandonli.mcav.utils.FileOperationException;
import me.brandonli.mcav.utils.IoUtils;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Downloads files over HTTP with timeouts, redirects, retries, and optional hash verification.
 *
 * <p>Every download writes to a temporary {@code .part} file with a unique name next to the destination, so two
 * downloads of the same file never write into each other. The file is moved into place only after the download
 * completed and the hash was verified, atomically where the file system supports it, so a crashed or failed download
 * does not expose a partial download as the destination before that move. A non-atomic fallback move has the
 * guarantees of its file-system provider.
 *
 * <p>A download whose hash and size are both pinned follows a redirect only to the same host, port and scheme, so a
 * mirror cannot make the server send requests to hosts of its choosing, and it is given up, without another attempt,
 * once it takes longer than a minute plus a second for every 64 KiB of its size, so a mirror that trickles cannot hold
 * up whoever waits for it.
 *
 * <p>Network failures, server errors, and bodies that deliver no data for longer than the idle timeout of one minute
 * are retried a few times with a growing delay, because installer downloads are large and connection resets are
 * common. Client errors such as {@code 404 Not Found}, hash mismatches, and failures to move the finished file into
 * place are not retried, because another attempt would fail the same way.
 *
 * <p>Concurrent downloads of the same file within one JVM coordinate final replacement: each writes its own
 * temporary file, and the
 * finished files are moved into place one after another, so the destination always holds one complete, verified
 * download. A move that another process denies for a moment, such as a virus scanner inspecting the new file, is
 * retried briefly by {@link IoUtils#moveReplacing(Path, Path)}. A known limitation: another process that replaces the
 * same destination at the same moment is not coordinated with, and such a move can still fail on Windows.
 *
 * <p>This class is thread-safe.
 *
 * @since 1.0.0
 */
public final class HttpDownloader {

  private static final Logger LOGGER = LoggerFactory.getLogger(HttpDownloader.class);

  private static final String USER_AGENT = "mcav (+https://github.com/PulseBeat02/mcav)";
  private static final String USER_AGENT_HEADER = "User-Agent";
  private static final String ACCEPT_HEADER = "Accept";
  private static final String ANY_MEDIA_TYPE = "*/*";
  private static final String LOCATION_HEADER = "Location";

  private static final String HTTPS_SCHEME = "https";
  private static final int HTTPS_PORT = 443;
  private static final int HTTP_PORT = 80;

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(20);
  private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(30);
  private static final Duration IDLE_TIMEOUT = Duration.ofMinutes(1);
  private static final int MAXIMUM_ATTEMPTS = 3;
  private static final Duration RETRY_DELAY = Duration.ofSeconds(2);

  private static final int BUFFER_SIZE = 64 * 1024;
  private static final String PART_SUFFIX = ".part";
  private static final String PART_PREFIX_SEPARATOR = ".";
  private static final String DIGEST_ALGORITHM = "SHA-256";

  private static final int MAXIMUM_REDIRECTS = 5;
  private static final Duration PINNED_GRACE = Duration.ofMinutes(1);
  private static final long PINNED_MINIMUM_BYTES_PER_SECOND = 64L * 1024;
  /**
   * The limit of a download that names no limit of its own, which is none.
   *
   * @since 1.0.0
   */
  public static final long NO_SIZE_LIMIT = Long.MAX_VALUE;

  private static final int HTTP_REQUEST_TIMEOUT = 408;
  private static final int HTTP_TOO_MANY_REQUESTS = 429;
  private static final int HTTP_SERVER_ERROR = 500;
  private static final int HTTP_STATUS_CLASS_SIZE = 100;
  private static final int HTTP_SUCCESS_CLASS = 2;
  private static final Set<Integer> HTTP_REDIRECTS = Set.of(301, 302, 303, 307, 308);

  private static final int TARGET_LOCK_STRIPES = 64;
  private static final Striped<Lock> TARGET_LOCKS = Striped.lock(TARGET_LOCK_STRIPES);

  private static final String SIZE_LIMIT_MESSAGE = "Size limit %s must be positive!";

  private HttpDownloader() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated!");
  }

  /**
   * Creates an HTTP client with a 20-second connection timeout and {@link HttpClient.Redirect#NORMAL}.
   * The caller owns this client; individual requests define their own response timeout.
   *
   * @return a new client, which the caller must close
   * @since 1.0.0
   */
  public static HttpClient createClient() {
    return createClient(HttpClient.Redirect.NORMAL);
  }

  private static HttpClient createClient(final HttpClient.Redirect redirects) {
    final HttpClient.Builder builder = HttpClient.newBuilder();
    builder.connectTimeout(CONNECT_TIMEOUT);
    builder.followRedirects(redirects);
    return builder.build();
  }

  /**
   * Creates a GET request with a 30-minute request timeout and mcav's user-agent header.
   *
   * @param uri the URI to request
   * @return the request
   * @throws IllegalArgumentException if the URI is not an absolute HTTP or HTTPS URI with a valid host
   * @throws NullPointerException     if {@code uri} is null
   * @since 1.0.0
   */
  public static HttpRequest createRequest(final URI uri) {
    Preconditions.checkNotNull(uri, "URI must not be null!");

    final HttpRequest.Builder builder = HttpRequest.newBuilder(uri);
    builder.timeout(REQUEST_TIMEOUT);
    builder.header(USER_AGENT_HEADER, USER_AGENT);
    builder.header(ACCEPT_HEADER, ANY_MEDIA_TYPE);
    builder.GET();
    return builder.build();
  }

  /**
   * Opens the body of a URL as a stream. The stream must be closed by the caller, which also shuts down the HTTP
   * client behind it. This method makes one request and does not impose a timeout on subsequent body reads.
   * Interruption while waiting for the response is restored and wrapped in {@link DownloadException}.
   *
   * @param uri the URI to download
   * @return the body of the response
   * @throws HttpStatusException      if the server answers with an error status
   * @throws DownloadException        if the request fails or is interrupted
   * @throws IllegalArgumentException if the URI is not an absolute HTTP or HTTPS URI with a valid host
   * @throws NullPointerException     if {@code uri} is null
   * @since 1.0.0
   */
  public static InputStream openStream(final URI uri) {
    Preconditions.checkNotNull(uri, "URI must not be null!");

    try {
      return openUnpinnedStream(uri);
    } catch (final IOException exception) {
      final String message = "Request for %s failed!".formatted(uri);
      throw new DownloadException(message, exception);
    }
  }

  private static InputStream openUnpinnedStream(final URI uri) throws IOException {
    final HttpRequest request = createRequest(uri);
    final HttpClient client = createClient();
    final HttpResponse<InputStream> response = send(client, request, uri);
    return bodyOf(response, client, uri);
  }

  // Opens the body of a pinned download: a redirect is followed only to the host, port and scheme of the URI, and at
  // most MAXIMUM_REDIRECTS times, so a mirror cannot make the server request what it chooses.
  @VisibleForTesting
  static InputStream openPinnedStream(final URI uri) throws IOException {
    URI current = uri;
    for (int redirects = 0; ; redirects++) {
      final HttpRequest request = createRequest(current);
      final HttpClient client = createClient(HttpClient.Redirect.NEVER);
      final HttpResponse<InputStream> response = send(client, request, current);
      final int status = response.statusCode();
      final Optional<String> location = response.headers().firstValue(LOCATION_HEADER);
      final @Nullable String redirectLocation = location.orElse(null);
      if (!isRedirect(status) || redirectLocation == null) {
        return bodyOf(response, client, current);
      }
      response.body().close();
      client.close();
      final URI next = resolveRedirect(uri, current, redirectLocation);
      if (!isSameOrigin(uri, next)) {
        final String message = "Pinned download %s may not redirect to %s on another host!".formatted(uri, next);
        throw new RedirectRefusedException(message);
      }
      if (redirects == MAXIMUM_REDIRECTS) {
        final String message = "Pinned download %s was redirected more than %s times!".formatted(uri, MAXIMUM_REDIRECTS);
        throw new RedirectRefusedException(message);
      }
      current = next;
    }
  }

  private static URI resolveRedirect(final URI uri, final URI current, final String location) {
    try {
      return current.resolve(location);
    } catch (final IllegalArgumentException exception) {
      final String message = "Pinned download %s may not redirect to %s, which is not a URI!".formatted(uri, location);
      throw new RedirectRefusedException(message, exception);
    }
  }

  private static boolean isRedirect(final int status) {
    return HTTP_REDIRECTS.contains(status);
  }

  @VisibleForTesting
  static boolean isSameOrigin(final URI first, final URI second) {
    final String scheme = Objects.requireNonNull(first.getScheme());
    final String host = Objects.requireNonNull(first.getHost());
    return scheme.equalsIgnoreCase(second.getScheme()) && host.equalsIgnoreCase(second.getHost()) && portOf(first) == portOf(second);
  }

  private static int portOf(final URI uri) {
    final int port = uri.getPort();
    if (port >= 0) {
      return port;
    }

    return HTTPS_SCHEME.equalsIgnoreCase(uri.getScheme()) ? HTTPS_PORT : HTTP_PORT;
  }

  private static HttpResponse<InputStream> send(final HttpClient client, final HttpRequest request, final URI uri) throws IOException {
    final HttpResponse.BodyHandler<InputStream> bodyHandler = HttpResponse.BodyHandlers.ofInputStream();
    try {
      return client.send(request, bodyHandler);
    } catch (final InterruptedException exception) {
      final Thread currentThread = Thread.currentThread();
      currentThread.interrupt();
      client.close();
      final String message = "Download of %s was interrupted!".formatted(uri);
      throw new DownloadException(message, exception);
    } catch (final IOException exception) {
      client.close();
      throw exception;
    }
  }

  private static InputStream bodyOf(final HttpResponse<InputStream> response, final HttpClient client, final URI uri) throws IOException {
    final int status = response.statusCode();
    final InputStream body = response.body();
    final boolean successful = status / HTTP_STATUS_CLASS_SIZE == HTTP_SUCCESS_CLASS;
    if (!successful) {
      body.close();
      client.close();
      throw new HttpStatusException(status, uri);
    }

    return new ClientClosingStream(body, client);
  }

  /**
   * Downloads a URL to a file without hash verification.
   *
   * @param uri         the URI to download
   * @param destination the file to write, which is replaced if it exists
   * @throws HttpStatusException      if the server answers with an error status that is not retried
   * @throws DownloadException        if the download fails after all retries
   * @throws FileOperationException   if the part file cannot be created or deleted, or the finished download cannot be
   *                                  moved into place
   * @throws NullPointerException     if {@code uri}, {@code destination} is null
   * @throws IllegalArgumentException if the URI is not an absolute HTTP or HTTPS URI with a valid host,
   *                                  the destination has no file name
   * @since 1.0.0
   */
  public static void download(final URI uri, final Path destination) {
    download(uri, destination, null);
  }

  /**
   * Downloads a URL to a file and verifies its SHA-256 hash.
   *
   * @param uri            the URI to download
   * @param destination    the file to write, which is replaced if it exists
   * @param expectedSha256 the expected SHA-256 hash in hexadecimal, or null to skip verification
   * @throws ChecksumMismatchException if the hash does not match, which is not retried
   * @throws HttpStatusException       if the server answers with an error status that is not retried
   * @throws DownloadException         if the download fails after all retries
   * @throws FileOperationException    if the part file cannot be created or deleted, or the finished download cannot be
   *                                   moved into place
   * @throws NullPointerException      if {@code uri}, {@code destination} is null
   * @throws IllegalArgumentException  if the URI is not an absolute HTTP or HTTPS URI with a valid host,
   *                                   the destination has no file name
   * @since 1.0.0
   */
  public static void download(final URI uri, final Path destination, final @Nullable String expectedSha256) {
    download(uri, destination, expectedSha256, RETRY_DELAY, IDLE_TIMEOUT, NO_SIZE_LIMIT);
  }

  /**
   * Downloads a file, stopping as soon as it turns out to be larger than allowed. Use this for anything whose size a
   * remote server chooses, such as a picture behind a URL a player named: a server that never stops sending would
   * otherwise fill the disk of its client.
   *
   * @param uri          the URL to download
   * @param destination  the file to write, which is replaced only after a complete download
   * @param maximumBytes the largest number of bytes the download may have, at least 1
   * @throws DownloadTooLargeException if the download is larger than the limit
   * @throws HttpStatusException       if the server answers with an error status that is not retried
   * @throws DownloadException         if the download fails after all retries
   * @throws FileOperationException    if the part file cannot be created or deleted, or the finished download cannot be
   *                                   moved into place
   * @throws NullPointerException      if {@code uri}, {@code destination} is null
   * @throws IllegalArgumentException  if the URI is not an absolute HTTP or HTTPS URI with a valid host,
   *                                   the destination has no file name, or {@code maximumBytes} is nonpositive
   * @since 1.0.0
   */
  public static void download(final URI uri, final Path destination, final long maximumBytes) {
    Preconditions.checkArgument(maximumBytes > 0, SIZE_LIMIT_MESSAGE, maximumBytes);

    download(uri, destination, null, RETRY_DELAY, IDLE_TIMEOUT, maximumBytes);
  }

  /**
   * Downloads a file whose hash and size are both pinned: it is verified against its SHA-256 hash, and the download
   * stops as soon as it turns out to be larger than its size, so a mirror that sends something else cannot fill the
   * disk before the hash tells.
   *
   * @param uri            the URL to download
   * @param destination    the file to write, which is replaced only after a complete download
   * @param expectedSha256 the expected SHA-256 hash in hexadecimal
   * @param maximumBytes   the largest number of bytes the download may have, at least 1
   * @throws DownloadTooLargeException if the download is larger than the limit
   * @throws ChecksumMismatchException if the hash does not match
   * @throws HttpStatusException       if the server answers with an error status that is not retried
   * @throws DownloadException         if the download fails after all retries, or is redirected to another host
   * @throws FileOperationException    if the part file cannot be created or deleted, or the finished download cannot be
   *                                   moved into place
   * @throws NullPointerException      if {@code uri}, {@code destination}, {@code expectedSha256} is null
   * @throws IllegalArgumentException  if the URI is not an absolute HTTP or HTTPS URI with a valid host,
   *                                   the destination has no file name, or {@code maximumBytes} is nonpositive
   * @since 1.0.0
   */
  public static void download(final URI uri, final Path destination, final String expectedSha256, final long maximumBytes) {
    Preconditions.checkNotNull(expectedSha256, "Expected SHA-256 must not be null!");
    Preconditions.checkArgument(maximumBytes > 0, SIZE_LIMIT_MESSAGE, maximumBytes);

    download(uri, destination, expectedSha256, RETRY_DELAY, IDLE_TIMEOUT, maximumBytes, pinnedDeadline(maximumBytes));
  }

  @VisibleForTesting
  static void download(final URI uri, final Path destination, final @Nullable String expectedSha256, final Duration retryDelay) {
    download(uri, destination, expectedSha256, retryDelay, IDLE_TIMEOUT, NO_SIZE_LIMIT);
  }

  @VisibleForTesting
  static void download(
    final URI uri,
    final Path destination,
    final @Nullable String expectedSha256,
    final Duration retryDelay,
    final Duration idleTimeout,
    final long maximumBytes
  ) {
    download(uri, destination, expectedSha256, retryDelay, idleTimeout, maximumBytes, null);
  }

  @VisibleForTesting
  static void download(
    final URI uri,
    final Path destination,
    final @Nullable String expectedSha256,
    final Duration retryDelay,
    final Duration idleTimeout,
    final long maximumBytes,
    final @Nullable Duration pinnedDeadline
  ) {
    Preconditions.checkNotNull(uri, "URI must not be null!");
    Preconditions.checkNotNull(destination, "Destination must not be null!");

    final Path target = destination.toAbsolutePath();
    final Path fileName = target.getFileName();
    Preconditions.checkArgument(fileName != null, "Destination %s must name a file!", destination);

    final Path parentOrNull = target.getParent();
    final Path parent = Objects.requireNonNull(parentOrNull, "A file always has a parent directory!");
    final String partPrefix = fileName + PART_PREFIX_SEPARATOR;
    try {
      Files.createDirectories(parent);
      final Path partFile = Files.createTempFile(parent, partPrefix, PART_SUFFIX);
      try {
        downloadWithRetries(uri, partFile, expectedSha256, retryDelay, idleTimeout, maximumBytes, pinnedDeadline);
        moveIntoPlace(partFile, target);
      } finally {
        Files.deleteIfExists(partFile);
      }
    } catch (final IOException exception) {
      final String message = "Part file of the download of %s to %s could not be created or deleted!".formatted(uri, target);
      throw new FileOperationException(message, exception);
    }
  }

  @VisibleForTesting
  static Duration pinnedDeadline(final long maximumBytes) {
    return PINNED_GRACE.plusSeconds(maximumBytes / PINNED_MINIMUM_BYTES_PER_SECOND);
  }

  // Moves a finished and verified download into place. Downloads of the same file in this JVM take turns here, because
  // Windows refuses to replace a file while another thread is replacing it, so two concurrent downloads of one file
  // would otherwise fail at random. Equal paths always share a lock stripe; on Windows, paths are equal regardless of
  // the case of their letters.
  private static void moveIntoPlace(final Path partFile, final Path target) {
    final Path normalizedTarget = target.normalize();
    final Lock lock = TARGET_LOCKS.get(normalizedTarget);
    lock.lock();
    try {
      IoUtils.moveReplacing(partFile, target);
    } finally {
      lock.unlock();
    }
  }

  private static void downloadWithRetries(
    final URI uri,
    final Path partFile,
    final @Nullable String expectedSha256,
    final Duration retryDelay,
    final Duration idleTimeout,
    final long maximumBytes,
    final @Nullable Duration pinnedDeadline
  ) {
    Exception lastFailure = null;
    for (int attempt = 1; attempt <= MAXIMUM_ATTEMPTS; attempt++) {
      try {
        downloadOnce(uri, partFile, expectedSha256, idleTimeout, maximumBytes, pinnedDeadline);
        return;
      } catch (final IOException | DownloadException exception) {
        lastFailure = exception;
        logFailedAttempt(uri, exception, attempt);
        rethrowUnlessRetryable(exception);
        sleepUnlessLastAttempt(retryDelay, attempt);
      }
    }

    final String message = "Download of %s failed after %s attempts!".formatted(uri, MAXIMUM_ATTEMPTS);
    final Exception cause = Objects.requireNonNull(lastFailure, "Every attempt failed before the loop ended!");
    throw new DownloadException(message, cause);
  }

  private static void logFailedAttempt(final URI uri, final Exception failure, final int attempt) {
    final String reason = failure.getMessage();
    LOGGER.warn("Download of {} failed with \"{}\" on attempt {} of {}.", uri, reason, attempt, MAXIMUM_ATTEMPTS);
  }

  private static void rethrowUnlessRetryable(final Exception failure) {
    if (failure instanceof final DownloadException downloadFailure && !isRetryable(downloadFailure)) {
      throw downloadFailure;
    }
  }

  private static void sleepUnlessLastAttempt(final Duration retryDelay, final int attempt) {
    if (attempt < MAXIMUM_ATTEMPTS) {
      sleepBeforeRetry(retryDelay, attempt);
    }
  }

  // Decides whether a failed download is worth another attempt. Network failures, stalled bodies, and server errors may
  // be temporary, but a client error such as 404 Not Found or a hash mismatch will not go away on its own.
  @VisibleForTesting
  static boolean isRetryable(final Exception exception) {
    if (exception instanceof ChecksumMismatchException) {
      return false;
    }

    if (exception instanceof DownloadTooLargeException) {
      return false;
    }

    if (exception instanceof RedirectRefusedException || exception instanceof DownloadTooSlowException) {
      return false;
    }

    if (!(exception instanceof final HttpStatusException statusException)) {
      return true;
    }

    final int status = statusException.getStatusCode();
    return status >= HTTP_SERVER_ERROR || status == HTTP_REQUEST_TIMEOUT || status == HTTP_TOO_MANY_REQUESTS;
  }

  private static void downloadOnce(
    final URI uri,
    final Path partFile,
    final @Nullable String expectedSha256,
    final Duration idleTimeout,
    final long maximumBytes,
    final @Nullable Duration pinnedDeadline
  ) throws IOException {
    final MessageDigest digest = createDigest(DIGEST_ALGORITHM);
    final long started = System.nanoTime();
    try (
      final InputStream response = pinnedDeadline == null ? openUnpinnedStream(uri) : openPinnedStream(uri);
      final InputStream body = new IdleTimeoutInputStream(response, idleTimeout);
      final OutputStream partOutput = Files.newOutputStream(partFile)
    ) {
      copy(body, partOutput, digest, uri, maximumBytes, started, pinnedDeadline);
    }

    verifyChecksum(uri, digest, expectedSha256);
  }

  // Copies the body into the part file and feeds every chunk into the digest on the way, so the file is hashed without
  // reading it a second time. A body that passes the limit stops the download at once, before the bytes beyond it are
  // written, and so does one that takes longer than its deadline.
  private static void copy(
    final InputStream body,
    final OutputStream partOutput,
    final MessageDigest digest,
    final URI uri,
    final long maximumBytes,
    final long started,
    final @Nullable Duration deadline
  ) throws IOException {
    final byte[] chunk = new byte[BUFFER_SIZE];
    long written = 0;
    int read = body.read(chunk);
    while (read != -1) {
      written += read;
      if (written > maximumBytes) {
        final String message = "The download of %s is larger than the %s bytes it may have!".formatted(uri, maximumBytes);
        throw new DownloadTooLargeException(message);
      }
      final long now = System.nanoTime();
      if (deadline != null && isPastDeadline(started, now, deadline)) {
        final String message = "The download of %s took longer than the %s it may take!".formatted(uri, deadline);
        throw new DownloadTooSlowException(message);
      }
      partOutput.write(chunk, 0, read);
      digest.update(chunk, 0, read);
      read = body.read(chunk);
    }
  }

  // Takes the current time, so a test can reach the deadline to the nanosecond, which kills the mutant that stops there.
  @VisibleForTesting
  static boolean isPastDeadline(final long started, final long now, final Duration deadline) {
    // compared as durations: the deadline of the largest pinned size does not fit a long of nanoseconds
    final Duration elapsed = Duration.ofNanos(now - started);
    return elapsed.compareTo(deadline) > 0;
  }

  private static void verifyChecksum(final URI uri, final MessageDigest digest, final @Nullable String expectedSha256) {
    if (expectedSha256 == null || expectedSha256.isBlank()) {
      return;
    }

    final byte[] hash = digest.digest();
    final HexFormat hexFormat = HexFormat.of();
    final String actualSha256 = hexFormat.formatHex(hash);
    final boolean matches = actualSha256.equalsIgnoreCase(expectedSha256);
    if (!matches) {
      final String message = "Download %s has the SHA-256 hash %s, but %s was expected!".formatted(uri, actualSha256, expectedSha256);
      throw new ChecksumMismatchException(message);
    }
  }

  @VisibleForTesting
  static MessageDigest createDigest(final String algorithm) {
    try {
      return MessageDigest.getInstance(algorithm);
    } catch (final NoSuchAlgorithmException exception) {
      final String message = "Digest algorithm %s is not available!".formatted(algorithm);
      throw new DownloadException(message, exception);
    }
  }

  private static void sleepBeforeRetry(final Duration retryDelay, final int attempt) {
    try {
      final Duration delay = retryDelay.multipliedBy(attempt);
      Thread.sleep(delay);
    } catch (final InterruptedException exception) {
      final Thread currentThread = Thread.currentThread();
      currentThread.interrupt();
      throw new DownloadException("Interrupted while waiting to retry a download!", exception);
    }
  }

  /**
   * Fetches a URL as text, for small responses such as JSON APIs.
   *
   * @param uri the URI to fetch
   * @return the body decoded using the response charset, or UTF-8 if none is supplied, or empty for a
   *         non-2xx status; the response is accumulated in memory
   * @throws DownloadException        if the request cannot be sent or is interrupted
   * @throws IllegalArgumentException if the URI is not an absolute HTTP or HTTPS URI with a valid host
   * @throws NullPointerException     if {@code uri} is null
   * @since 1.0.0
   */
  public static Optional<String> fetchText(final URI uri) {
    Preconditions.checkNotNull(uri, "URI must not be null!");

    final HttpRequest request = createRequest(uri);
    final HttpResponse.BodyHandler<String> bodyHandler = HttpResponse.BodyHandlers.ofString();
    try (final HttpClient client = createClient()) {
      final HttpResponse<String> response = client.send(request, bodyHandler);
      return readSuccessfulBody(response, uri);
    } catch (final InterruptedException exception) {
      final Thread currentThread = Thread.currentThread();
      currentThread.interrupt();
      final String message = "Fetch of %s was interrupted!".formatted(uri);
      throw new DownloadException(message, exception);
    } catch (final IOException exception) {
      final String message = "Fetch of %s failed!".formatted(uri);
      throw new DownloadException(message, exception);
    }
  }

  private static Optional<String> readSuccessfulBody(final HttpResponse<String> response, final URI uri) {
    final int status = response.statusCode();
    final boolean successful = status / HTTP_STATUS_CLASS_SIZE == HTTP_SUCCESS_CLASS;
    if (!successful) {
      LOGGER.warn("Server answered with status {} for {}, which is not a success.", status, uri);
      return Optional.empty();
    }

    final String body = response.body();
    return Optional.of(body);
  }

  // A response stream that shuts down its HTTP client together with the stream. The client is shut down without waiting
  // for its exchanges, so closing a stream whose server stopped sending never blocks.
  @VisibleForTesting
  static final class ClientClosingStream extends InputStream {

    private final InputStream delegate;
    private final HttpClient client;

    ClientClosingStream(final InputStream delegate, final HttpClient client) {
      this.delegate = delegate;
      this.client = client;
    }

    @Override
    public int read() throws IOException {
      return this.delegate.read();
    }

    @Override
    public int read(final byte @NonNull [] buffer, final int offset, final int length) throws IOException {
      return this.delegate.read(buffer, offset, length);
    }

    @Override
    public void close() throws IOException {
      try {
        this.delegate.close();
      } finally {
        this.client.shutdownNow();
      }
    }

    HttpClient getClient() {
      return this.client;
    }
  }
}
