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
import java.util.concurrent.locks.Lock;
import me.brandonli.mcav.utils.IOUtils;
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
 * retried briefly by {@link IOUtils#moveReplacing(Path, Path)}. A known limitation: another process that replaces the
 * same destination at the same moment is not coordinated with, and such a move can still fail on Windows.
 */
public final class HttpDownloader {

  private static final Logger LOGGER = LoggerFactory.getLogger(HttpDownloader.class);
  private static final String ATTEMPT_FAILED = "Download attempt {}/{} of {} failed: {}";
  private static final String UNEXPECTED_STATUS = "Server answered with status {} for {}";
  private static final String USER_AGENT = "mcav (+https://github.com/PulseBeat02/mcav)";
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(20);
  private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(30);
  private static final Duration IDLE_TIMEOUT = Duration.ofMinutes(1);
  private static final int MAX_ATTEMPTS = 3;
  private static final Duration RETRY_DELAY = Duration.ofSeconds(2);
  private static final int BUFFER_SIZE = 64 * 1024;
  private static final String PART_SUFFIX = ".part";
  private static final int MAX_REDIRECTS = 5;
  private static final Duration PINNED_GRACE = Duration.ofMinutes(1);
  private static final long PINNED_MIN_BYTES_PER_SECOND = 64L * 1024;
  /**
   * The limit of a download that names no limit of its own, which is none.
   */
  public static final long NO_SIZE_LIMIT = Long.MAX_VALUE;

  private static final int HTTP_REQUEST_TIMEOUT = 408;
  private static final int HTTP_TOO_MANY_REQUESTS = 429;
  private static final int HTTP_SERVER_ERROR = 500;
  private static final int TARGET_LOCK_STRIPES = 64;
  private static final Striped<Lock> TARGET_LOCKS = Striped.lock(TARGET_LOCK_STRIPES);

  private HttpDownloader() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Creates an HTTP client with a 20-second connection timeout and {@link HttpClient.Redirect#NORMAL}.
   * The caller owns this client; individual requests define their own response timeout.
   *
   * @return a new client, which the caller must close
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
   * @throws NullPointerException if {@code uri} is null
   */
  public static HttpRequest createRequest(final URI uri) {
    Preconditions.checkNotNull(uri, "URI must not be null");
    final HttpRequest.Builder builder = HttpRequest.newBuilder(uri);
    builder.timeout(REQUEST_TIMEOUT);
    builder.header("User-Agent", USER_AGENT);
    builder.header("Accept", "*/*");
    builder.GET();
    return builder.build();
  }

  /**
   * Opens the body of a URL as a stream. The stream must be closed by the caller, which also shuts down the HTTP
   * client behind it. This method makes one request and does not impose a timeout on subsequent body reads.
   * Interruption while waiting for the response is restored and wrapped in {@link IOException}.
   *
   * @param uri the URI to download
   * @return the body of the response
   * @throws IOException if the request fails or the server answers with an error status
   * @throws IllegalArgumentException if the URI is not an absolute HTTP or HTTPS URI with a valid host
   * @throws NullPointerException if {@code uri} is null
   */
  public static InputStream openStream(final URI uri) throws IOException {
    Preconditions.checkNotNull(uri, "URI must not be null");
    final HttpRequest request = createRequest(uri);
    final HttpClient client = createClient();
    final HttpResponse<InputStream> response = send(client, request, uri);
    return bodyOf(response, client, uri);
  }

  /**
   * Opens the body of a pinned download: a redirect is followed only to the host, port and scheme of the URI, and at
   * most {@value #MAX_REDIRECTS} times, so a mirror cannot make the server request what it chooses.
   *
   * @param uri the URI to download
   * @return the body of the response, which closes its client
   * @throws RedirectRefusedException if the server redirects elsewhere, or too often
   * @throws IOException if a request fails or the server answers with an error status
   */
  @VisibleForTesting
  static InputStream openPinnedStream(final URI uri) throws IOException {
    URI current = uri;
    for (int redirects = 0; ; redirects++) {
      final HttpRequest request = createRequest(current);
      final HttpClient client = createClient(HttpClient.Redirect.NEVER);
      final HttpResponse<InputStream> response = send(client, request, current);
      final int status = response.statusCode();
      final Optional<String> location = response.headers().firstValue("Location");
      if (!isRedirect(status) || location.isEmpty()) {
        return bodyOf(response, client, current);
      }
      response.body().close();
      client.close();
      final URI next = current.resolve(location.get());
      if (!isSameOrigin(uri, next)) {
        throw new RedirectRefusedException("The pinned download of " + uri + " was redirected to another host: " + next);
      }
      if (redirects == MAX_REDIRECTS) {
        throw new RedirectRefusedException("The pinned download of " + uri + " was redirected more than " + MAX_REDIRECTS + " times");
      }
      current = next;
    }
  }

  private static boolean isRedirect(final int status) {
    return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
  }

  /**
   * Checks whether two URIs name the same scheme, host and port, a port left out being the scheme's own.
   *
   * @param first  a URI
   * @param second another URI
   * @return true if both have the same origin
   */
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
    return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
  }

  private static HttpResponse<InputStream> send(final HttpClient client, final HttpRequest request, final URI uri) throws IOException {
    final HttpResponse.BodyHandler<InputStream> bodyHandler = HttpResponse.BodyHandlers.ofInputStream();
    try {
      return client.send(request, bodyHandler);
    } catch (final InterruptedException exception) {
      final Thread currentThread = Thread.currentThread();
      currentThread.interrupt();
      client.close();
      throw new IOException("Interrupted while downloading " + uri, exception);
    } catch (final IOException exception) {
      client.close();
      throw exception;
    }
  }

  private static InputStream bodyOf(final HttpResponse<InputStream> response, final HttpClient client, final URI uri) throws IOException {
    final int status = response.statusCode();
    final InputStream body = response.body();
    final boolean successful = status / 100 == 2;
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
   * @throws IOException if the download fails after all retries
   * @throws NullPointerException if {@code uri}, {@code destination} is null
   * @throws IllegalArgumentException if the URI is not an absolute HTTP or HTTPS URI with a valid host,
   *                                  the destination has no file name
   */
  public static void download(final URI uri, final Path destination) throws IOException {
    download(uri, destination, null);
  }

  /**
   * Downloads a URL to a file and verifies its SHA-256 hash.
   *
   * @param uri            the URI to download
   * @param destination    the file to write, which is replaced if it exists
   * @param expectedSha256 the expected SHA-256 hash in hexadecimal, or null to skip verification
   * @throws IOException                if the download fails after all retries or the file cannot be moved into place
   * @throws ChecksumMismatchException if the hash does not match, which is not retried
   * @throws NullPointerException if {@code uri}, {@code destination} is null
   * @throws IllegalArgumentException if the URI is not an absolute HTTP or HTTPS URI with a valid host,
   *                                  the destination has no file name
   */
  public static void download(final URI uri, final Path destination, final @Nullable String expectedSha256) throws IOException {
    download(uri, destination, expectedSha256, RETRY_DELAY, IDLE_TIMEOUT, NO_SIZE_LIMIT);
  }

  /**
   * Downloads a file, stopping as soon as it turns out to be larger than allowed. Use this for anything whose size a
   * remote server chooses, such as a picture behind a URL a player named: a server that never stops sending would
   * otherwise fill the disk of its client.
   *
   * @param uri         the URL to download
   * @param destination the file to write, which is replaced only after a complete download
   * @param maxBytes    the largest number of bytes the download may have, at least 1
   * @throws DownloadTooLargeException if the download is larger than the limit
   * @throws IOException               if the download fails
   * @throws NullPointerException if {@code uri}, {@code destination} is null
   * @throws IllegalArgumentException if the URI is not an absolute HTTP or HTTPS URI with a valid host,
   *                                  the destination has no file name, or {@code maxBytes} is nonpositive
   */
  public static void download(final URI uri, final Path destination, final long maxBytes) throws IOException {
    Preconditions.checkArgument(maxBytes > 0, "The size limit must be positive but was %s", maxBytes);
    download(uri, destination, null, RETRY_DELAY, IDLE_TIMEOUT, maxBytes);
  }

  /**
   * Downloads a file whose hash and size are both pinned: it is verified against its SHA-256 hash, and the download
   * stops as soon as it turns out to be larger than its size, so a mirror that sends something else cannot fill the
   * disk before the hash tells.
   *
   * @param uri            the URL to download
   * @param destination    the file to write, which is replaced only after a complete download
   * @param expectedSha256 the expected SHA-256 hash in hexadecimal
   * @param maxBytes       the largest number of bytes the download may have, at least 1
   * @throws DownloadTooLargeException if the download is larger than the limit
   * @throws ChecksumMismatchException if the hash does not match
   * @throws IOException               if the download fails
   * @throws NullPointerException if {@code uri}, {@code destination}, {@code expectedSha256} is null
   * @throws IllegalArgumentException if the URI is not an absolute HTTP or HTTPS URI with a valid host,
   *                                  the destination has no file name, or {@code maxBytes} is nonpositive
   */
  public static void download(final URI uri, final Path destination, final String expectedSha256, final long maxBytes) throws IOException {
    Preconditions.checkNotNull(expectedSha256, "Expected SHA-256 must not be null");
    Preconditions.checkArgument(maxBytes > 0, "The size limit must be positive but was %s", maxBytes);
    download(uri, destination, expectedSha256, RETRY_DELAY, IDLE_TIMEOUT, maxBytes, pinnedDeadline(maxBytes));
  }

  /**
   * Downloads a file with a custom delay between attempts and the default idle timeout.
   *
   * @param uri            the URI to download
   * @param destination    the file to write to; its parent directories are created if needed
   * @param expectedSha256 the expected SHA-256 hash in hexadecimal, or {@code null} to skip verification
   * @param retryDelay     the delay before the second attempt; later attempts wait correspondingly longer
   * @throws IOException if every attempt fails
   */
  @VisibleForTesting
  static void download(final URI uri, final Path destination, final @Nullable String expectedSha256, final Duration retryDelay)
    throws IOException {
    download(uri, destination, expectedSha256, retryDelay, IDLE_TIMEOUT, NO_SIZE_LIMIT);
  }

  /**
   * Downloads a file with a custom delay between attempts, a custom idle timeout and a custom size limit.
   *
   * @param uri            the URI to download
   * @param destination    the file to write to; its parent directories are created if needed
   * @param expectedSha256 the expected SHA-256 hash in hexadecimal, or {@code null} to skip verification
   * @param retryDelay     the delay before the second attempt; later attempts wait correspondingly longer
   * @param idleTimeout    how long a read may wait for data before the attempt fails
   * @param maxBytes       the largest number of bytes the download may have, or {@link #NO_SIZE_LIMIT} for no limit
   * @throws IOException if every attempt fails or the file cannot be moved into place
   */
  @VisibleForTesting
  static void download(
    final URI uri,
    final Path destination,
    final @Nullable String expectedSha256,
    final Duration retryDelay,
    final Duration idleTimeout,
    final long maxBytes
  ) throws IOException {
    download(uri, destination, expectedSha256, retryDelay, idleTimeout, maxBytes, null);
  }

  /**
   * Downloads a file as the method above does, pinned if a deadline is given.
   *
   * @param uri            the URI to download
   * @param destination    the file to write to; its parent directories are created if needed
   * @param expectedSha256 the expected SHA-256 hash in hexadecimal, or {@code null} to skip verification
   * @param retryDelay     the delay before the second attempt; later attempts wait correspondingly longer
   * @param idleTimeout    how long a read may wait for data before the attempt fails
   * @param maxBytes       the largest number of bytes the download may have, or {@link #NO_SIZE_LIMIT} for no limit
   * @param pinnedDeadline how long an attempt of a pinned download may take, which also follows a redirect only to
   *                       its own host, or null for a download that is not pinned
   * @throws IOException if every attempt fails or the file cannot be moved into place
   */
  @VisibleForTesting
  static void download(
    final URI uri,
    final Path destination,
    final @Nullable String expectedSha256,
    final Duration retryDelay,
    final Duration idleTimeout,
    final long maxBytes,
    final @Nullable Duration pinnedDeadline
  ) throws IOException {
    Preconditions.checkNotNull(uri, "URI must not be null");
    Preconditions.checkNotNull(destination, "Destination must not be null");
    final Path target = destination.toAbsolutePath();
    final Path fileName = target.getFileName();
    Preconditions.checkArgument(fileName != null, "Destination must name a file but was %s", destination);
    final Path parentOrNull = target.getParent();
    final Path parent = Objects.requireNonNull(parentOrNull, "A file always has a parent directory");
    Files.createDirectories(parent);
    final String partPrefix = fileName + ".";
    final Path partFile = Files.createTempFile(parent, partPrefix, PART_SUFFIX);
    try {
      downloadWithRetries(uri, partFile, expectedSha256, retryDelay, idleTimeout, maxBytes, pinnedDeadline);
      moveIntoPlace(partFile, target);
    } finally {
      Files.deleteIfExists(partFile);
    }
  }

  /**
   * Gets how long a pinned download may take: a minute, and a second for every 64 KiB of its size.
   *
   * @param maxBytes the pinned size
   * @return the time it may take
   */
  @VisibleForTesting
  static Duration pinnedDeadline(final long maxBytes) {
    return PINNED_GRACE.plusSeconds(maxBytes / PINNED_MIN_BYTES_PER_SECOND);
  }

  /**
   * Moves a finished and verified download into place. Downloads of the same file in this JVM take turns here,
   * because Windows refuses to replace a file while another thread is replacing it, so two concurrent downloads of one
   * file would otherwise fail at random. Equal paths always share a lock stripe; on Windows, paths are equal regardless
   * of the case of their letters.
   */
  private static void moveIntoPlace(final Path partFile, final Path target) throws IOException {
    final Path normalizedTarget = target.normalize();
    final Lock lock = TARGET_LOCKS.get(normalizedTarget);
    lock.lock();
    try {
      IOUtils.moveReplacing(partFile, target);
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
    final long maxBytes,
    final @Nullable Duration pinnedDeadline
  ) throws IOException {
    IOException lastFailure = null;
    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      try {
        downloadOnce(uri, partFile, expectedSha256, idleTimeout, maxBytes, pinnedDeadline);
        return;
      } catch (final IOException exception) {
        lastFailure = exception;
        final String reason = exception.getMessage();
        LOGGER.warn(ATTEMPT_FAILED, attempt, MAX_ATTEMPTS, uri, reason);
        final boolean retryable = isRetryable(exception);
        if (!retryable) {
          throw exception;
        }
        if (attempt < MAX_ATTEMPTS) {
          sleepBeforeRetry(retryDelay, attempt);
        }
      }
    }
    throw new IOException("Failed to download " + uri + " after " + MAX_ATTEMPTS + " attempts", lastFailure);
  }

  /**
   * Decides whether a failed download is worth another attempt. Network failures, stalled bodies, and server errors
   * may be temporary, but a client error such as {@code 404 Not Found} or a hash mismatch will not go away on its own.
   */
  @VisibleForTesting
  static boolean isRetryable(final IOException exception) {
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
    final long maxBytes,
    final @Nullable Duration pinnedDeadline
  ) throws IOException {
    final MessageDigest digest = createDigest("SHA-256");
    final long started = System.nanoTime();
    try (
      final InputStream response = pinnedDeadline == null ? openStream(uri) : openPinnedStream(uri);
      final InputStream body = new IdleTimeoutInputStream(response, idleTimeout);
      final OutputStream partOutput = Files.newOutputStream(partFile)
    ) {
      copy(body, partOutput, digest, uri, maxBytes, started, pinnedDeadline);
    }
    verifyChecksum(uri, digest, expectedSha256);
  }

  /**
   * Copies the body into the part file and feeds every chunk into the digest on the way, so the file is hashed
   * without reading it a second time. A body that passes the limit stops the download at once, before the bytes
   * beyond it are written, and so does one that takes longer than its deadline.
   */
  private static void copy(
    final InputStream body,
    final OutputStream partOutput,
    final MessageDigest digest,
    final URI uri,
    final long maxBytes,
    final long started,
    final @Nullable Duration deadline
  ) throws IOException {
    final byte[] chunk = new byte[BUFFER_SIZE];
    long written = 0;
    int read = body.read(chunk);
    while (read != -1) {
      written += read;
      if (written > maxBytes) {
        final String message = "The download of %s is larger than the %s bytes it may have".formatted(uri, maxBytes);
        throw new DownloadTooLargeException(message);
      }
      // compared as durations: the deadline of the largest pinned size does not fit a long of nanoseconds
      if (deadline != null && Duration.ofNanos(System.nanoTime() - started).compareTo(deadline) > 0) {
        final String message = "The download of %s took longer than the %s it may take".formatted(uri, deadline);
        throw new DownloadTooSlowException(message);
      }
      partOutput.write(chunk, 0, read);
      digest.update(chunk, 0, read);
      read = body.read(chunk);
    }
  }

  private static void verifyChecksum(final URI uri, final MessageDigest digest, final @Nullable String expectedSha256) throws IOException {
    if (expectedSha256 == null || expectedSha256.isBlank()) {
      return;
    }
    final byte[] hash = digest.digest();
    final HexFormat hexFormat = HexFormat.of();
    final String actualSha256 = hexFormat.formatHex(hash);
    final boolean matches = actualSha256.equalsIgnoreCase(expectedSha256);
    if (!matches) {
      final String message = "SHA-256 mismatch for %s: expected %s but got %s".formatted(uri, expectedSha256, actualSha256);
      throw new ChecksumMismatchException(message);
    }
  }

  @VisibleForTesting
  static MessageDigest createDigest(final String algorithm) throws IOException {
    try {
      return MessageDigest.getInstance(algorithm);
    } catch (final NoSuchAlgorithmException exception) {
      throw new IOException(algorithm + " is not available", exception);
    }
  }

  private static void sleepBeforeRetry(final Duration retryDelay, final int attempt) throws IOException {
    try {
      final Duration delay = retryDelay.multipliedBy(attempt);
      Thread.sleep(delay);
    } catch (final InterruptedException exception) {
      final Thread currentThread = Thread.currentThread();
      currentThread.interrupt();
      throw new IOException("Interrupted while waiting to retry a download", exception);
    }
  }

  /**
   * Fetches a URL as text, for small responses such as JSON APIs.
   *
   * @param uri the URI to fetch
   * @return the body decoded using the response charset, or UTF-8 if none is supplied, or empty for a
   *         non-2xx status; the response is accumulated in memory
   * @throws IOException if the request cannot be sent
   * @throws IllegalArgumentException if the URI is not an absolute HTTP or HTTPS URI with a valid host
   * @throws NullPointerException if {@code uri} is null
   */
  public static Optional<String> fetchText(final URI uri) throws IOException {
    Preconditions.checkNotNull(uri, "URI must not be null");
    final HttpRequest request = createRequest(uri);
    final HttpResponse.BodyHandler<String> bodyHandler = HttpResponse.BodyHandlers.ofString();
    try (final HttpClient client = createClient()) {
      final HttpResponse<String> response = client.send(request, bodyHandler);
      return readSuccessfulBody(response, uri);
    } catch (final InterruptedException exception) {
      final Thread currentThread = Thread.currentThread();
      currentThread.interrupt();
      throw new IOException("Interrupted while fetching " + uri, exception);
    }
  }

  private static Optional<String> readSuccessfulBody(final HttpResponse<String> response, final URI uri) {
    final int status = response.statusCode();
    final boolean successful = status / 100 == 2;
    if (!successful) {
      LOGGER.warn(UNEXPECTED_STATUS, status, uri);
      return Optional.empty();
    }
    final String body = response.body();
    return Optional.of(body);
  }

  /**
   * A response stream that shuts down its HTTP client together with the stream. The client is shut down without
   * waiting for its exchanges, so closing a stream whose server stopped sending never blocks.
   */
  @VisibleForTesting
  static final class ClientClosingStream extends InputStream {

    private final InputStream delegate;
    private final HttpClient client;

    ClientClosingStream(final InputStream delegate, final HttpClient client) {
      this.delegate = delegate;
      this.client = client;
    }

    /**
     * Gets the client this stream shuts down when it is closed.
     *
     * @return the client
     */
    HttpClient getClient() {
      return this.client;
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
  }
}
