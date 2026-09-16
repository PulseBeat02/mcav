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
package me.brandonli.mcav.bukkit.resourcepack.provider;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import me.brandonli.mcav.bukkit.testing.LogCapture;
import me.brandonli.mcav.utils.IOUtils;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;

/**
 * Tests {@link MCPackHosting} and {@link MCPacksException} against an upload and download service on the loopback
 * interface. The tests change {@code user.home}, where the upload cache lives, so they run in isolation.
 */
@Isolated
final class MCPackHostingTest {

  private static final byte[] PACK = "PK resource pack".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] OTHER_PACK = "PK other resource pack".getBytes(StandardCharsets.US_ASCII);
  private static final String UNREACHABLE_DOWNLOAD_FORMAT = "http://download.test/pack/%s.zip";
  private static final Pattern BOUNDARY = Pattern.compile("multipart/form-data; boundary=(----mcav-[0-9a-f-]{36})");
  private static final List<String> ONLY_HEAD = List.of("HEAD");
  private static final Duration LOCK_TIMEOUT = Duration.ofSeconds(10);

  @TempDir
  private Path directory;

  private String previousHome;
  private PackService service;
  private Path zip;

  @BeforeEach
  void startService() throws IOException {
    this.previousHome = System.getProperty("user.home");
    final Path home = this.directory.resolve("home");
    Files.createDirectory(home);
    final String homePath = home.toString();
    System.setProperty("user.home", homePath);

    this.service = PackService.start();
    this.zip = this.directory.resolve("pack.zip");
    Files.write(this.zip, PACK);
  }

  @AfterEach
  void stopService() {
    this.service.close();
    System.setProperty("user.home", this.previousHome);

    // clears the flag as well, so a failed test cannot leave later tests interrupted
    final boolean leftInterrupted = Thread.interrupted();
    assertFalse(leftInterrupted, "no test leaves the interrupt flag set");
  }

  private MCPackHosting createHosting(final Path pack) {
    final URI uploadUri = this.service.uploadUri();
    final String downloadFormat = this.service.downloadFormat();
    return new MCPackHosting(pack, uploadUri, downloadFormat);
  }

  private String downloadUrl(final byte[] pack) throws NoSuchAlgorithmException {
    final String hash = sha1(pack);
    final String downloadFormat = this.service.downloadFormat();
    return downloadFormat.formatted(hash);
  }

  private int uploadCount() {
    final List<Upload> uploads = this.service.getUploads();
    return uploads.size();
  }

  private static String sha1(final byte[] bytes) throws NoSuchAlgorithmException {
    final MessageDigest digest = MessageDigest.getInstance("SHA-1");
    final byte[] hash = digest.digest(bytes);
    return IOUtils.bytesToHex(hash);
  }

  private static Path getCacheFile() {
    final Path cache = IOUtils.getCachedFolder();
    return cache.resolve("mc-packs-cache.json");
  }

  private static JsonObject readCache() throws IOException {
    final Path cacheFile = getCacheFile();
    final String text = Files.readString(cacheFile);
    final JsonElement element = JsonParser.parseString(text);
    return element.getAsJsonObject();
  }

  private static String cachedUrl(final JsonObject cache, final String hash) {
    final JsonElement element = cache.get(hash);
    return element.getAsString();
  }

  private static byte[] expectedMultipartBody(final String boundary) {
    final String head =
      "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"pack.zip\"\r\nContent-Type: application/zip\r\n\r\n";
    final String tail = "\r\n--" + boundary + "--\r\n";
    final byte[] headBytes = head.getBytes(StandardCharsets.UTF_8);
    final byte[] tailBytes = tail.getBytes(StandardCharsets.UTF_8);

    final ByteArrayOutputStream output = new ByteArrayOutputStream();
    output.writeBytes(headBytes);
    output.writeBytes(PACK);
    output.writeBytes(tailBytes);
    return output.toByteArray();
  }

  private static void assertIsMultipartUpload(final Upload upload) {
    final Matcher boundaryMatcher = BOUNDARY.matcher(upload.contentType);
    final boolean multipart = boundaryMatcher.matches();
    assertTrue(multipart, upload.contentType);

    final String boundary = boundaryMatcher.group(1);
    final byte[] expectedBody = expectedMultipartBody(boundary);
    assertEquals("POST", upload.method);
    assertEquals("mcav", upload.userAgent);
    assertEquals("*/*", upload.accept);
    assertArrayEquals(expectedBody, upload.body);
  }

  @Test
  void uploadsThePackAsAMultipartFormAndCachesTheDownloadUrl() throws Exception {
    final MCPackHosting hosting = this.createHosting(this.zip);
    assertThrows(IllegalStateException.class, hosting::getRawUrl);

    hosting.start();
    hosting.start();

    final String url = hosting.getRawUrl();
    final String hash = sha1(PACK);
    final String expectedUrl = this.downloadUrl(PACK);
    final List<Upload> uploads = this.service.getUploads();
    final int uploadCount = uploads.size();
    final Upload upload = uploads.getFirst();
    final JsonObject cache = readCache();
    final String cached = cachedUrl(cache, hash);
    assertEquals(expectedUrl, url);
    assertEquals(1, uploadCount, "starting again does not upload again");
    assertIsMultipartUpload(upload);
    assertEquals(url, cached);
  }

  @Test
  void reusesTheUploadOfAnIdenticalPackWhileItIsStillAvailable() throws IOException {
    final MCPackHosting first = this.createHosting(this.zip);
    final Path copy = this.directory.resolve("copy.zip");
    final MCPackHosting second = this.createHosting(copy);
    Files.write(copy, PACK);

    first.start();
    second.start();

    final String firstUrl = first.getRawUrl();
    final String secondUrl = second.getRawUrl();
    final int uploadCount = this.uploadCount();
    final List<String> downloadMethods = this.service.getDownloadMethods();
    assertEquals(firstUrl, secondUrl);
    assertEquals(1, uploadCount);
    assertEquals(ONLY_HEAD, downloadMethods, "the cached download is checked before it is reused");
  }

  @Test
  void uploadsThePackAgainWhenTheCachedDownloadIsGone() throws Exception {
    final MCPackHosting first = this.createHosting(this.zip);
    final MCPackHosting second = this.createHosting(this.zip);

    first.start();
    this.service.removeDownloads();
    second.start();

    final String secondUrl = second.getRawUrl();
    final String expectedUrl = this.downloadUrl(PACK);
    final int uploadCount = this.uploadCount();
    final List<String> downloadMethods = this.service.getDownloadMethods();
    assertEquals(expectedUrl, secondUrl);
    assertEquals(2, uploadCount, "a pack deleted by the service is uploaded again instead of handing out a dead URL");
    assertEquals(ONLY_HEAD, downloadMethods);
  }

  @Test
  void uploadsThePackAgainWhenTheCachedUrlIsInvalid() throws Exception {
    final String hash = sha1(PACK);
    final Path cacheFile = getCacheFile();
    Files.writeString(cacheFile, "{\"" + hash + "\": \"not a url\"}");
    final MCPackHosting hosting = this.createHosting(this.zip);

    hosting.start();

    final String url = hosting.getRawUrl();
    final String expectedUrl = this.downloadUrl(PACK);
    final int uploadCount = this.uploadCount();
    final JsonObject cache = readCache();
    final String cached = cachedUrl(cache, hash);
    assertEquals(expectedUrl, url);
    assertEquals(1, uploadCount);
    assertEquals(expectedUrl, cached, "the broken entry is replaced");
  }

  @Test
  void uploadsChangedPacksAgain() throws Exception {
    final MCPackHosting first = this.createHosting(this.zip);
    final MCPackHosting second = this.createHosting(this.zip);

    first.start();
    Files.write(this.zip, OTHER_PACK);
    second.start();

    final String secondUrl = second.getRawUrl();
    final String expectedUrl = this.downloadUrl(OTHER_PACK);
    final int uploadCount = this.uploadCount();
    final JsonObject cache = readCache();
    final int cacheSize = cache.size();
    assertEquals(expectedUrl, secondUrl);
    assertEquals(2, uploadCount);
    assertEquals(2, cacheSize);
  }

  @Test
  void reportsRejectedUploads() {
    this.service.rejectUploads();
    final MCPackHosting hosting = this.createHosting(this.zip);

    final MCPacksException exception = assertThrows(MCPacksException.class, hosting::start);

    final String message = exception.getMessage();
    final Path cacheFile = getCacheFile();
    final boolean cached = Files.exists(cacheFile);
    assertEquals("mc-packs.net rejected the upload with status 500", message);
    assertThrows(IllegalStateException.class, hosting::getRawUrl);
    assertFalse(cached, "failed uploads are not cached");
  }

  @Test
  void reportsUnreachableServices() throws IOException {
    final int port;
    try (final ServerSocket socket = new ServerSocket(0)) {
      port = socket.getLocalPort();
    }
    final URI closed = URI.create("http://127.0.0.1:" + port + "/upload");
    final MCPackHosting hosting = new MCPackHosting(this.zip, closed, UNREACHABLE_DOWNLOAD_FORMAT);

    final MCPacksException exception = assertThrows(MCPacksException.class, hosting::start);

    final Throwable cause = exception.getCause();
    assertInstanceOf(ConnectException.class, cause);
  }

  @Test
  void keepsTheInterruptFlagWhenInterruptedDuringTheUpload() {
    final Thread testThread = Thread.currentThread();
    final CountDownLatch release = new CountDownLatch(1);
    this.service.setUploadHook(() -> {
        testThread.interrupt();
        awaitRelease(release);
      });
    final MCPackHosting hosting = this.createHosting(this.zip);

    try {
      final MCPacksException exception = assertThrows(MCPacksException.class, hosting::start);
      final boolean interrupted = Thread.interrupted();
      final String message = exception.getMessage();
      final Throwable cause = exception.getCause();
      assertTrue(interrupted);
      assertEquals("Interrupted while uploading the resource pack", message);
      assertInstanceOf(InterruptedException.class, cause);
    } finally {
      release.countDown();
    }
  }

  // keeps the upload open until the test has checked the interrupted client
  private static void awaitRelease(final CountDownLatch release) {
    try {
      final boolean released = release.await(10, TimeUnit.SECONDS);
      assertTrue(released, "the test releases the upload");
    } catch (final InterruptedException exception) {
      final Thread handlerThread = Thread.currentThread();
      handlerThread.interrupt();
    }
  }

  @Test
  void reportsPacksThatCannotBeRead() {
    final Path missing = this.directory.resolve("missing.zip");
    final MCPackHosting hosting = this.createHosting(missing);

    final MCPacksException exception = assertThrows(MCPacksException.class, hosting::start);

    final String message = exception.getMessage();
    final Throwable cause = exception.getCause();
    final List<Upload> uploads = this.service.getUploads();
    final boolean nothingUploaded = uploads.isEmpty();
    assertEquals("Resource pack cannot be read: " + missing, message);
    assertInstanceOf(NoSuchFileException.class, cause);
    assertTrue(nothingUploaded);
  }

  @Test
  void treatsCorruptCachesAsEmpty() throws Exception {
    final Path cacheFile = getCacheFile();
    final MCPackHosting corrupt = this.createHosting(this.zip);
    final MCPackHosting empty = this.createHosting(this.zip);

    Files.writeString(cacheFile, "{not json");
    corrupt.start();
    Files.writeString(cacheFile, "null");
    empty.start();

    final int uploadCount = this.uploadCount();
    final JsonObject cache = readCache();
    final String hash = sha1(PACK);
    final boolean cached = cache.has(hash);
    assertEquals(2, uploadCount);
    assertTrue(cached, "the cache is written again");
  }

  @Test
  void usesTheUploadAndLogsAWarningWhenTheCacheCannotBeWritten() throws Exception {
    final Path cacheFile = getCacheFile();
    Files.createDirectory(cacheFile);
    final MCPackHosting hosting = this.createHosting(this.zip);
    final List<LogCapture.RecordedEvent> events;
    try (final LogCapture logs = LogCapture.capture(MCPackHosting.class)) {
      hosting.start();
      events = logs.getEvents();
    }

    final String url = hosting.getRawUrl();
    final String expectedUrl = this.downloadUrl(PACK);
    final int uploadCount = this.uploadCount();
    final LogCapture.RecordedEvent event = events.getFirst();
    final Level level = event.getLevel();
    final String message = event.getMessage();
    final Throwable thrown = event.getThrown();
    assertEquals(expectedUrl, url, "the successful upload is used even though it cannot be cached");
    assertEquals(1, uploadCount);
    assertEquals(Level.WARN, level);
    assertEquals("Could not write the upload cache " + cacheFile + ", the pack will be uploaded again next time", message);
    assertInstanceOf(IOException.class, thrown);
  }

  /**
   * Uploads a pack on another thread and waits for it to finish. The upload cache lock is shared by every hosting, so
   * a lock the calling thread still holds shows up as a thread that never finishes.
   *
   * @param hosting the hosting to start
   * @throws InterruptedException if the test is interrupted while waiting for the thread
   */
  private static void startInAnotherThread(final MCPackHosting hosting) throws InterruptedException {
    final AtomicReference<Throwable> failure = new AtomicReference<>();
    final Thread starter = new Thread(hosting::start, "pack-upload-lock-probe");
    starter.setDaemon(true);
    starter.setUncaughtExceptionHandler((_, throwable) -> failure.set(throwable));
    starter.start();
    starter.join();
    final Throwable thrown = failure.get();
    if (thrown != null) {
      throw new AssertionError("uploading on another thread failed", thrown);
    }
  }

  @Test
  void leavesTheUploadCacheLockFreeForTheNextPack() throws Exception {
    final MCPackHosting first = this.createHosting(this.zip);
    final Path other = this.directory.resolve("other.zip");
    Files.write(other, OTHER_PACK);
    final MCPackHosting second = this.createHosting(other);

    first.start();
    assertTimeoutPreemptively(LOCK_TIMEOUT, () -> startInAnotherThread(second), "an upload that finished leaves the lock free");

    final String firstUrl = first.getRawUrl();
    final String secondUrl = second.getRawUrl();
    final String expectedFirstUrl = this.downloadUrl(PACK);
    final String expectedSecondUrl = this.downloadUrl(OTHER_PACK);
    final int uploads = this.uploadCount();
    assertEquals(expectedFirstUrl, firstUrl);
    assertEquals(expectedSecondUrl, secondUrl);
    assertEquals(2, uploads, "both packs were uploaded");
  }

  @Test
  void hashesBytesAsLowercaseHexadecimal() {
    final byte[] abc = "abc".getBytes(StandardCharsets.US_ASCII);

    final String hash = MCPackHosting.hash(abc, "SHA-1");
    final IllegalStateException exception = assertThrows(IllegalStateException.class, () -> MCPackHosting.hash(abc, "NO-SUCH-HASH"));

    final Throwable cause = exception.getCause();
    assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", hash);
    assertInstanceOf(NoSuchAlgorithmException.class, cause);
  }

  @Test
  void keepsThePackAvailableAfterShutdown() {
    final MCPackHosting hosting = this.createHosting(this.zip);

    hosting.start();
    final String url = hosting.getRawUrl();
    hosting.shutdown();

    final String afterShutdown = hosting.getRawUrl();
    final Path hostedZip = hosting.getZip();
    assertEquals(url, afterShutdown);
    assertSame(this.zip, hostedZip);
  }

  @Test
  void isCreatedByTheWebsiteFactory() {
    final WebsiteHosting hosting = PackHosting.website(this.zip);

    final Path hostedZip = hosting.getZip();
    final URI uploadUri = this.service.uploadUri();

    assertInstanceOf(MCPackHosting.class, hosting);
    assertSame(this.zip, hostedZip);
    assertThrows(NullPointerException.class, () -> PackHosting.website(null));
    assertThrows(NullPointerException.class, () -> new MCPackHosting(null));
    assertThrows(NullPointerException.class, () -> new MCPackHosting(null, uploadUri, UNREACHABLE_DOWNLOAD_FORMAT));
    assertThrows(NullPointerException.class, () -> new MCPackHosting(this.zip, null, UNREACHABLE_DOWNLOAD_FORMAT));
    assertThrows(NullPointerException.class, () -> new MCPackHosting(this.zip, uploadUri, null));
  }

  @Test
  void exceptionsKeepTheMessage() {
    final MCPacksException exception = new MCPacksException("message");

    final String message = exception.getMessage();
    final Throwable cause = exception.getCause();

    assertEquals("message", message);
    assertNull(cause);
  }

  @Test
  void exceptionsKeepTheMessageAndTheCause() {
    final IOException failure = new IOException("cause");
    final MCPacksException exception = new MCPacksException("message", failure);

    final String message = exception.getMessage();
    final Throwable cause = exception.getCause();

    assertEquals("message", message);
    assertSame(failure, cause);
  }

  @Test
  void exceptionsAreRuntimeExceptions() {
    final MCPacksException exception = new MCPacksException("message");
    assertInstanceOf(RuntimeException.class, exception, "a failed upload is recoverable, so it is no Error");
  }

  /**
   * One upload received by the service.
   */
  private static final class Upload {

    private final String method;
    private final String contentType;
    private final String userAgent;
    private final String accept;
    private final byte[] body;

    Upload(final String method, final String contentType, final String userAgent, final String accept, final byte[] body) {
      this.method = method;
      this.contentType = contentType;
      this.userAgent = userAgent;
      this.accept = accept;
      this.body = body;
    }
  }

  /**
   * An upload and download service on the loopback interface, like mc-packs.net, that records every request.
   * Uploads are posted to {@code /upload}, and downloads are answered below {@code /pack/} while they are
   * available.
   */
  private static final class PackService implements AutoCloseable {

    private static final int REJECTED_STATUS = 500;

    private final HttpServer server;
    private final List<Upload> uploads;
    private final List<String> downloadMethods;

    private volatile int uploadStatus;
    private volatile boolean downloadsAvailable;
    private volatile Runnable uploadHook;

    private PackService(final HttpServer server) {
      this.server = server;
      this.uploads = new CopyOnWriteArrayList<>();
      this.downloadMethods = new CopyOnWriteArrayList<>();
      this.uploadStatus = 200;
      this.downloadsAvailable = true;
      this.uploadHook = () -> {};
    }

    static PackService start() throws IOException {
      final InetAddress loopback = InetAddress.getLoopbackAddress();
      final InetSocketAddress address = new InetSocketAddress(loopback, 0);
      final HttpServer httpServer = HttpServer.create(address, 0);
      final PackService service = new PackService(httpServer);
      httpServer.createContext("/upload", service::handleUpload);
      httpServer.createContext("/pack/", service::handleDownload);
      httpServer.start();
      return service;
    }

    private void handleUpload(final HttpExchange exchange) throws IOException {
      final byte[] body;
      try (final InputStream input = exchange.getRequestBody()) {
        body = input.readAllBytes();
      }

      final Headers headers = exchange.getRequestHeaders();
      final String method = exchange.getRequestMethod();
      final String contentType = headers.getFirst("Content-Type");
      final String userAgent = headers.getFirst("User-Agent");
      final String accept = headers.getFirst("Accept");
      final Upload upload = new Upload(method, contentType, userAgent, accept, body);
      this.uploads.add(upload);
      this.uploadHook.run();

      final byte[] response = "ok".getBytes(StandardCharsets.US_ASCII);
      exchange.sendResponseHeaders(this.uploadStatus, response.length);
      try (final OutputStream output = exchange.getResponseBody()) {
        output.write(response);
      }
    }

    private void handleDownload(final HttpExchange exchange) throws IOException {
      final String method = exchange.getRequestMethod();
      this.downloadMethods.add(method);
      final int status = this.downloadsAvailable ? 200 : 404;
      exchange.sendResponseHeaders(status, -1);
      exchange.close();
    }

    private int port() {
      final InetSocketAddress address = this.server.getAddress();
      return address.getPort();
    }

    URI uploadUri() {
      final int port = this.port();
      return URI.create("http://127.0.0.1:" + port + "/upload");
    }

    String downloadFormat() {
      final int port = this.port();
      return "http://127.0.0.1:" + port + "/pack/%s.zip";
    }

    List<Upload> getUploads() {
      return List.copyOf(this.uploads);
    }

    List<String> getDownloadMethods() {
      return List.copyOf(this.downloadMethods);
    }

    void rejectUploads() {
      this.uploadStatus = REJECTED_STATUS;
    }

    void removeDownloads() {
      this.downloadsAvailable = false;
    }

    void setUploadHook(final Runnable hook) {
      this.uploadHook = hook;
    }

    @Override
    public void close() {
      this.server.stop(0);
    }
  }
}
