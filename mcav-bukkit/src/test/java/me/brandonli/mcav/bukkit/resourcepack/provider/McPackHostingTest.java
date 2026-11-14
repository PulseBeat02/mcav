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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import me.brandonli.mcav.bukkit.BukkitException;
import me.brandonli.mcav.bukkit.testing.LogCapture;
import me.brandonli.mcav.utils.IoUtils;
import org.apache.logging.log4j.Level;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;

// changes user.home, where the upload cache lives
@Isolated
final class McPackHostingTest {

  private static final String LOCAL_SERVER_PREFIX = "http://127.0.0.1:";

  private static final byte[] PACK = "PK resource pack".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] OTHER_PACK = "PK other resource pack".getBytes(StandardCharsets.US_ASCII);
  private static final String UNREACHABLE_DOWNLOAD_FORMAT = "http://download.test/pack/%s.zip";
  private static final Pattern BOUNDARY = Pattern.compile("multipart/form-data; boundary=(----mcav-[0-9a-f-]{36})");
  private static final List<String> ONLY_HEAD = List.of("HEAD");
  private static final Duration LOCK_TIMEOUT = Duration.ofSeconds(10);

  private static final int EPHEMERAL_PORT = 0;

  private static final String PACK_FILE_NAME = "pack.zip";
  private static final String OTHER_FILE_NAME = "other.zip";
  private static final String CACHE_FILE_NAME = "mc-packs-cache.json";
  private static final String COPY_PACK_FILE_NAME = "copy.zip";
  private static final String MISSING_PACK_FILE_NAME = "missing.zip";

  private static final String HOME_DIRECTORY = "home";

  private static final String USER_HOME_PROPERTY = "user.home";
  private static final String HASH_ALGORITHM = "SHA-1";

  private static final String POST_METHOD = "POST";
  private static final String UPLOAD_PATH = "/upload";
  private static final String PACK_PATH = "/pack/";

  private static final String MESSAGE = "Message!";

  @TempDir
  private Path directory;

  private String previousHome;
  private PackService service;
  private Path zip;

  @BeforeEach
  void startService() throws IOException {
    this.previousHome = System.getProperty(USER_HOME_PROPERTY);
    final Path home = this.directory.resolve(HOME_DIRECTORY);
    Files.createDirectory(home);
    final String homePath = home.toString();
    System.setProperty(USER_HOME_PROPERTY, homePath);

    this.service = PackService.start();
    this.zip = this.directory.resolve(PACK_FILE_NAME);
    Files.write(this.zip, PACK);
  }

  @AfterEach
  void stopService() throws InterruptedException {
    try {
      this.service.close();
    } finally {
      System.setProperty(USER_HOME_PROPERTY, this.previousHome);
      final boolean leftInterrupted = Thread.interrupted();
      assertFalse(leftInterrupted, "no test leaves the interrupt flag set");
    }
  }

  private McPackHosting createHosting(final Path pack) {
    final URI uploadUri = this.service.uploadUri();
    final String downloadFormat = this.service.downloadFormat();
    return new McPackHosting(pack, uploadUri, downloadFormat);
  }

  private String downloadUrl(final byte[] pack) throws NoSuchAlgorithmException {
    final String hash = this.sha1(pack);
    final String downloadFormat = this.service.downloadFormat();
    return downloadFormat.formatted(hash);
  }

  private int uploadCount() {
    final List<Upload> uploads = this.service.getUploads();
    return uploads.size();
  }

  private String sha1(final byte[] bytes) throws NoSuchAlgorithmException {
    final MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
    final byte[] hash = digest.digest(bytes);
    return IoUtils.bytesToHexadecimal(hash);
  }

  private Path getCacheFile() {
    final Path cache = IoUtils.getCachedFolder();
    return cache.resolve(CACHE_FILE_NAME);
  }

  private JsonObject readCache() throws IOException {
    final Path cacheFile = this.getCacheFile();
    final String text = Files.readString(cacheFile);
    final JsonElement element = JsonParser.parseString(text);
    return element.getAsJsonObject();
  }

  private String cachedUrl(final JsonObject cache, final String hash) {
    final JsonElement element = cache.get(hash);
    return element.getAsString();
  }

  private byte[] expectedMultipartBody(final String boundary) {
    final String head =
      "--%s\r\nContent-Disposition: form-data; name=\"file\"; filename=\"pack.zip\"\r\nContent-Type: application/zip\r\n\r\n".formatted(
        boundary
      );
    final String tail = "\r\n--%s--\r\n".formatted(boundary);
    final byte[] headBytes = head.getBytes(StandardCharsets.UTF_8);
    final byte[] tailBytes = tail.getBytes(StandardCharsets.UTF_8);

    final ByteArrayOutputStream output = new ByteArrayOutputStream();
    output.writeBytes(headBytes);
    output.writeBytes(PACK);
    output.writeBytes(tailBytes);
    return output.toByteArray();
  }

  private void assertIsMultipartUpload(final Upload upload) {
    final Matcher boundaryMatcher = BOUNDARY.matcher(upload.contentType);
    final boolean multipart = boundaryMatcher.matches();
    assertTrue(multipart, upload.contentType);

    final String boundary = boundaryMatcher.group(1);
    final byte[] expectedBody = this.expectedMultipartBody(boundary);
    final String method = upload.method;
    assertEquals(POST_METHOD, method);

    final String userAgent = upload.userAgent;
    assertEquals("mcav", userAgent);

    final String accept = upload.accept;
    assertEquals("*/*", accept);

    final byte[] body = upload.body;
    assertArrayEquals(expectedBody, body);
  }

  @Test
  void shouldKeepUntrustedFileNamesInsideOneMultipartHeader() {
    final Path path = mock(Path.class);
    final Path name = mock(Path.class);
    when(path.getFileName()).thenReturn(name);
    when(name.toString()).thenReturn("pa\r\nc\t\"\\k.zip");
    final McPackHosting hosting = this.createHosting(path);
    final String head = hosting.createMultipartHead("boundary");
    final String expected =
      "--boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"pack.zip\"\r\nContent-Type: application/zip\r\n\r\n";
    assertEquals(expected, head);
  }

  @Test
  void shouldUploadThePackAsAMultipartFormAndCacheTheDownloadUrl() throws Exception {
    final McPackHosting hosting = this.createHosting(this.zip);
    assertThrows(IllegalStateException.class, hosting::getRawUrl);

    hosting.start();
    hosting.start();

    final String url = hosting.getRawUrl();
    final String hash = this.sha1(PACK);
    final String expectedUrl = this.downloadUrl(PACK);
    final List<Upload> uploads = this.service.getUploads();
    final int uploadCount = uploads.size();
    final Upload upload = uploads.getFirst();
    final JsonObject cache = this.readCache();
    final String cached = this.cachedUrl(cache, hash);
    assertEquals(expectedUrl, url);
    assertEquals(1, uploadCount, "starting again does not upload again");
    this.assertIsMultipartUpload(upload);
    assertEquals(url, cached);
  }

  @Test
  void shouldReuseTheUploadOfAnIdenticalPackWhileItIsStillAvailable() throws IOException {
    final McPackHosting first = this.createHosting(this.zip);
    final Path copy = this.directory.resolve(COPY_PACK_FILE_NAME);
    final McPackHosting second = this.createHosting(copy);
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
  void shouldUploadThePackAgainWhenTheCachedDownloadIsGone() throws Exception {
    final McPackHosting first = this.createHosting(this.zip);
    final McPackHosting second = this.createHosting(this.zip);

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
  void shouldUploadThePackAgainWhenTheCachedUrlIsInvalid() throws Exception {
    final String hash = this.sha1(PACK);
    final Path cacheFile = this.getCacheFile();
    Files.writeString(cacheFile, "{\"%s\": \"not a url\"}".formatted(hash));
    final McPackHosting hosting = this.createHosting(this.zip);

    hosting.start();

    final String url = hosting.getRawUrl();
    final String expectedUrl = this.downloadUrl(PACK);
    final int uploadCount = this.uploadCount();
    final JsonObject cache = this.readCache();
    final String cached = this.cachedUrl(cache, hash);
    assertEquals(expectedUrl, url);
    assertEquals(1, uploadCount);
    assertEquals(expectedUrl, cached, "the broken entry is replaced");
  }

  @Test
  void shouldUploadChangedPacksAgain() throws Exception {
    final McPackHosting first = this.createHosting(this.zip);
    final McPackHosting second = this.createHosting(this.zip);

    first.start();
    Files.write(this.zip, OTHER_PACK);
    second.start();

    final String secondUrl = second.getRawUrl();
    final String expectedUrl = this.downloadUrl(OTHER_PACK);
    final int uploadCount = this.uploadCount();
    final JsonObject cache = this.readCache();
    final int cacheSize = cache.size();
    assertEquals(expectedUrl, secondUrl);
    assertEquals(2, uploadCount);
    assertEquals(2, cacheSize);
  }

  @Test
  void shouldReportRejectedUploads() {
    this.service.rejectUploads();
    final McPackHosting hosting = this.createHosting(this.zip);

    final McPacksException exception = assertThrows(McPacksException.class, hosting::start);

    final String message = exception.getMessage();
    final Path cacheFile = this.getCacheFile();
    final boolean cached = Files.exists(cacheFile);
    assertEquals("mc-packs.net answered status 500 and rejected the upload!", message);
    assertThrows(IllegalStateException.class, hosting::getRawUrl);
    assertFalse(cached, "failed uploads are not cached");
  }

  @Test
  void shouldReportUnreachableServices() throws IOException {
    final int port;
    try (final ServerSocket socket = new ServerSocket(EPHEMERAL_PORT)) {
      port = socket.getLocalPort();
    }

    final URI closed = URI.create(LOCAL_SERVER_PREFIX + port + UPLOAD_PATH);
    final McPackHosting hosting = new McPackHosting(this.zip, closed, UNREACHABLE_DOWNLOAD_FORMAT);

    final McPacksException exception = assertThrows(McPacksException.class, hosting::start);

    final String message = exception.getMessage();
    final Throwable cause = exception.getCause();
    assertEquals("The resource pack upload to mc-packs.net failed!", message);
    assertInstanceOf(ConnectException.class, cause);
  }

  @Test
  void shouldKeepTheInterruptFlagWhenInterruptedDuringTheUpload() {
    final Thread testThread = Thread.currentThread();
    final CountDownLatch release = new CountDownLatch(1);
    this.service.setUploadHook(() -> this.interruptUploadAndAwaitRelease(testThread, release));
    final McPackHosting hosting = this.createHosting(this.zip);

    try {
      final McPacksException exception = assertThrows(McPacksException.class, hosting::start);
      final boolean interrupted = Thread.interrupted();
      final String message = exception.getMessage();
      final Throwable cause = exception.getCause();
      assertTrue(interrupted);
      assertEquals("Interrupted while uploading the resource pack!", message);
      assertInstanceOf(InterruptedException.class, cause);
    } finally {
      release.countDown();
    }
  }

  private void interruptUploadAndAwaitRelease(final Thread testThread, final CountDownLatch release) {
    testThread.interrupt();
    this.awaitRelease(release);
  }

  private void awaitRelease(final CountDownLatch release) {
    try {
      final boolean released = release.await(10, TimeUnit.SECONDS);
      assertTrue(released, "the test releases the upload");
    } catch (final InterruptedException exception) {
      final Thread current = Thread.currentThread();
      current.interrupt();
    }
  }

  @Test
  void shouldReportPacksThatCannotBeRead() {
    final Path missing = this.directory.resolve(MISSING_PACK_FILE_NAME);
    final McPackHosting hosting = this.createHosting(missing);

    final McPacksException exception = assertThrows(McPacksException.class, hosting::start);

    final String message = exception.getMessage();
    final Throwable cause = exception.getCause();
    final List<Upload> uploads = this.service.getUploads();
    final boolean nothingUploaded = uploads.isEmpty();
    final String expectedMessage = "Resource pack %s cannot be read!".formatted(missing);
    assertEquals(expectedMessage, message);
    assertInstanceOf(NoSuchFileException.class, cause);
    assertTrue(nothingUploaded);
  }

  @Test
  void shouldTreatCorruptCachesAsEmpty() throws Exception {
    final Path cacheFile = this.getCacheFile();
    final McPackHosting corrupt = this.createHosting(this.zip);
    final McPackHosting empty = this.createHosting(this.zip);

    Files.writeString(cacheFile, "{not json");
    corrupt.start();
    Files.writeString(cacheFile, "null");
    empty.start();

    final int uploadCount = this.uploadCount();
    final JsonObject cache = this.readCache();
    final String hash = this.sha1(PACK);
    final boolean cached = cache.has(hash);
    assertEquals(2, uploadCount);
    assertTrue(cached, "the cache is written again");
  }

  @Test
  void shouldTreatCachesThatAreNotUtf8AsEmpty() throws Exception {
    final Path cacheFile = this.getCacheFile();
    final McPackHosting hosting = this.createHosting(this.zip);
    // past the reader's first 8 KB, so the JSON parses before the bad byte fails
    final String json = "{\"a\":\"b\"}" + " ".repeat(9000);
    final byte[] text = json.getBytes(StandardCharsets.UTF_8);
    final ByteArrayOutputStream contents = new ByteArrayOutputStream();
    contents.writeBytes(text);
    contents.write(0xFF);
    final byte[] undecodable = contents.toByteArray();

    Files.write(cacheFile, undecodable);
    hosting.start();

    final int uploadCount = this.uploadCount();
    final JsonObject cache = this.readCache();
    final String hash = this.sha1(PACK);
    final boolean cached = cache.has(hash);
    assertEquals(1, uploadCount);
    assertTrue(cached, "the cache is written again");
  }

  @Test
  void shouldUseTheUploadAndLogAWarningWhenTheCacheCannotBeWritten() throws Exception {
    final Path cacheFile = this.getCacheFile();
    Files.createDirectory(cacheFile);
    final McPackHosting hosting = this.createHosting(this.zip);
    final List<LogCapture.RecordedEvent> events;
    try (final LogCapture logs = LogCapture.capture(McPackHosting.class)) {
      hosting.start();
      events = logs.getEvents();
    }

    final String url = hosting.getRawUrl();
    final String expectedUrl = this.downloadUrl(PACK);
    final int uploadCount = this.uploadCount();
    final LogCapture.RecordedEvent event = events.getFirst();
    final Level level = event.getLevel();
    final String message = event.getMessage();
    final Throwable thrown = event.getThrown().orElseThrow();
    assertEquals(expectedUrl, url, "the successful upload is used even though it cannot be cached");
    assertEquals(1, uploadCount);
    assertEquals(Level.WARN, level);

    final String expectedMessage = "Could not write the upload cache %s, the pack will be uploaded again next time.".formatted(cacheFile);
    assertEquals(expectedMessage, message);
    assertInstanceOf(IOException.class, thrown);
  }

  // every hosting shares the upload cache lock, so a lock this thread holds shows as a thread that never finishes
  private void startInAnotherThread(final McPackHosting hosting) throws InterruptedException {
    final AtomicReference<Throwable> recordedFailure = new AtomicReference<>();
    final Thread starter = new Thread(hosting::start, "pack-upload-lock-probe");
    starter.setDaemon(true);
    starter.setUncaughtExceptionHandler((_, failure) -> recordedFailure.set(failure));
    starter.start();
    starter.join();
    final Throwable thrown = recordedFailure.get();
    if (thrown != null) {
      throw new AssertionError("Uploading on another thread failed!", thrown);
    }
  }

  @Test
  void shouldLeaveTheUploadCacheLockFreeForTheNextPack() throws Exception {
    final McPackHosting first = this.createHosting(this.zip);
    final Path other = this.directory.resolve(OTHER_FILE_NAME);
    Files.write(other, OTHER_PACK);
    final McPackHosting second = this.createHosting(other);

    first.start();
    assertTimeoutPreemptively(LOCK_TIMEOUT, () -> this.startInAnotherThread(second), "an upload that finished leaves the lock free");

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
  void shouldHashBytesAsLowercaseHexadecimal() {
    final byte[] abc = "abc".getBytes(StandardCharsets.US_ASCII);

    final String hash = McPackHosting.hash(abc, HASH_ALGORITHM);
    final AssertionError exception = assertThrows(AssertionError.class, () -> McPackHosting.hash(abc, "NO-SUCH-HASH"));

    final Throwable cause = exception.getCause();
    assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", hash);
    assertInstanceOf(NoSuchAlgorithmException.class, cause);
  }

  @Test
  void shouldKeepThePackAvailableAfterShutdown() {
    final McPackHosting hosting = this.createHosting(this.zip);

    hosting.start();
    final String url = hosting.getRawUrl();
    hosting.shutdown();

    final String afterShutdown = hosting.getRawUrl();
    final Path hostedZip = hosting.getZip();
    assertEquals(url, afterShutdown);
    assertSame(this.zip, hostedZip);
  }

  @Test
  void shouldBeCreatedByTheWebsiteFactory() {
    final WebsiteHosting hosting = PackHosting.website(this.zip);

    final Path hostedZip = hosting.getZip();
    final URI uploadUri = this.service.uploadUri();

    assertInstanceOf(McPackHosting.class, hosting);
    assertSame(this.zip, hostedZip);
    assertThrows(NullPointerException.class, () -> PackHosting.website(null));
    assertThrows(NullPointerException.class, () -> new McPackHosting(null));
    assertThrows(NullPointerException.class, () -> new McPackHosting(null, uploadUri, UNREACHABLE_DOWNLOAD_FORMAT));
    assertThrows(NullPointerException.class, () -> new McPackHosting(this.zip, null, UNREACHABLE_DOWNLOAD_FORMAT));
    assertThrows(NullPointerException.class, () -> new McPackHosting(this.zip, uploadUri, null));
  }

  @Test
  void shouldKeepTheMessageInExceptions() {
    final McPacksException exception = new McPacksException(MESSAGE);

    final String message = exception.getMessage();
    final Throwable cause = exception.getCause();

    assertEquals(MESSAGE, message);
    assertNull(cause);
  }

  @Test
  void shouldKeepTheMessageAndTheCauseInExceptions() {
    final IOException failure = new IOException("Cause!");
    final McPacksException exception = new McPacksException(MESSAGE, failure);

    final String message = exception.getMessage();
    final Throwable cause = exception.getCause();

    assertEquals(MESSAGE, message);
    assertSame(failure, cause);
  }

  @Test
  void shouldMakeExceptionsBukkitExceptions() {
    final McPacksException exception = new McPacksException(MESSAGE);
    assertInstanceOf(BukkitException.class, exception);
  }

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

  private static final class PackService implements AutoCloseable {

    private static final String DOWNLOAD_PATH_FORMAT = "/pack/%s.zip";

    private static final int SUCCESS_STATUS = 200;
    private static final int REJECTED_STATUS = 500;

    private final HttpServer server;
    private final ExecutorService handlers = Executors.newSingleThreadExecutor();
    private final AtomicReference<AssertionError> handlerFailure = new AtomicReference<>();
    private final List<Upload> uploads;
    private final List<String> downloadMethods;

    private volatile int uploadStatus;
    private volatile boolean downloadsAvailable;
    private volatile Runnable uploadHook;

    private PackService(final HttpServer server) {
      this.server = server;
      this.uploads = new CopyOnWriteArrayList<>();
      this.downloadMethods = new CopyOnWriteArrayList<>();
      this.uploadStatus = SUCCESS_STATUS;
      this.downloadsAvailable = true;
      this.uploadHook = () -> {};
    }

    private static PackService start() throws IOException {
      final InetAddress loopback = InetAddress.getLoopbackAddress();
      final InetSocketAddress address = new InetSocketAddress(loopback, EPHEMERAL_PORT);
      final HttpServer httpServer = HttpServer.create(address, 0);
      final PackService service = new PackService(httpServer);
      httpServer.createContext(UPLOAD_PATH, service::recordAndCloseUpload);
      httpServer.createContext(PACK_PATH, service::answerDownload);
      httpServer.setExecutor(service.handlers);
      httpServer.start();
      return service;
    }

    private void recordAndCloseUpload(final HttpExchange exchange) throws IOException {
      try {
        this.recordUpload(exchange);
      } catch (final AssertionError exception) {
        this.handlerFailure.compareAndSet(null, exception);
      } finally {
        exchange.close();
      }
    }

    private void recordUpload(final HttpExchange exchange) throws IOException {
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
      assertEquals(POST_METHOD, method, "a pack is uploaded by POST");

      final byte[] response = "ok".getBytes(StandardCharsets.US_ASCII);
      exchange.sendResponseHeaders(this.uploadStatus, response.length);
      try (final OutputStream output = exchange.getResponseBody()) {
        output.write(response);
      }
    }

    private void answerDownload(final HttpExchange exchange) throws IOException {
      final String method = exchange.getRequestMethod();
      this.downloadMethods.add(method);
      final int status = this.downloadsAvailable ? SUCCESS_STATUS : 404;
      exchange.sendResponseHeaders(status, -1);
      exchange.close();
    }

    private int port() {
      final InetSocketAddress address = this.server.getAddress();
      return address.getPort();
    }

    private URI uploadUri() {
      final int port = this.port();
      return URI.create(LOCAL_SERVER_PREFIX + port + UPLOAD_PATH);
    }

    private String downloadFormat() {
      final int port = this.port();
      return LOCAL_SERVER_PREFIX + port + DOWNLOAD_PATH_FORMAT;
    }

    private void rejectUploads() {
      this.uploadStatus = REJECTED_STATUS;
    }

    private void removeDownloads() {
      this.downloadsAvailable = false;
    }

    @Override
    public void close() {
      this.server.stop(0);
      this.handlers.shutdown();
      try {
        final boolean terminated = this.handlers.awaitTermination(10, TimeUnit.SECONDS);
        assertTrue(terminated, "the upload callbacks finish");
      } catch (final InterruptedException exception) {
        final Thread current = Thread.currentThread();
        current.interrupt();

        throw new AssertionError("Interrupted while joining upload callbacks!", exception);
      }

      final @Nullable AssertionError recordedFailure = this.handlerFailure.get();
      assertEquals(null, recordedFailure, "upload assertions reach JUnit");
    }

    private List<Upload> getUploads() {
      return List.copyOf(this.uploads);
    }

    private List<String> getDownloadMethods() {
      return List.copyOf(this.downloadMethods);
    }

    private void setUploadHook(final Runnable hook) {
      this.uploadHook = hook;
    }
  }
}
