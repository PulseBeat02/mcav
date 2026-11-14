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

import static java.util.Objects.requireNonNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.gson.Gson;
import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import me.brandonli.mcav.json.GsonProvider;
import me.brandonli.mcav.utils.IoUtils;
import me.brandonli.mcav.utils.http.NetworkUtils;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hosts a resource pack by uploading it to <a href="https://mc-packs.net">mc-packs.net</a>.
 *
 * <p>This strategy needs no open port at all, which makes it the easiest option for servers behind a proxy or a
 * strict firewall. Uploads are cached on disk by the SHA-1 hash of the pack, so an unchanged pack is uploaded
 * only once even across server restarts. Before a cached download URL is reused, it is checked with a
 * {@code HEAD} request, and the pack is uploaded again if the download is gone, for example because mc-packs.net
 * deleted it. The check and the upload block for as long as the transfer takes, so call {@link #start()} off the
 * main thread.
 *
 * <p>The first successful start fixes this instance's URL. Rebuilding the file afterward does not upload it again
 * through this instance; create another hosting instance for changed content. Shutdown does not clear that URL or the
 * cache. This class is thread-safe: the cache lock serializes upload/cache decisions across instances in this class
 * loader.
 *
 * @since 1.0.0
 */
public final class McPackHosting implements WebsiteHosting {

  private static final Logger LOGGER = LoggerFactory.getLogger(McPackHosting.class);

  private static final URI UPLOAD_URI = URI.create("https://mc-packs.net");
  private static final String DOWNLOAD_URL_FORMAT = "https://download.mc-packs.net/pack/%s.zip";

  private static final String HASH_ALGORITHM = "SHA-1";

  private static final String CACHE_FILE = "mc-packs-cache.json";
  private static final Type CACHE_TYPE = createCacheType();
  private static final Lock CACHE_LOCK = new ReentrantLock();

  private static final Duration TIMEOUT = Duration.ofMinutes(5);

  private static final int HTTP_OK = 200;

  private static final String USER_AGENT = "mcav";

  private static final String CONTENT_TYPE_HEADER = "Content-Type";
  private static final String ACCEPT_HEADER = "Accept";
  private static final String USER_AGENT_HEADER = "User-Agent";

  private static final String MULTIPART_CONTENT_TYPE_PREFIX = "multipart/form-data; boundary=";
  private static final String ANY_MEDIA_TYPE = "*/*";

  private static final String BOUNDARY_PREFIX = "----mcav-";

  private static final Pattern UNSAFE_FILE_NAME_CHARACTERS = Pattern.compile("[\\p{Cntrl}\"\\\\]");

  private final Path zip;
  private final URI uploadUri;
  private final String downloadUrlFormat;

  private volatile @Nullable String url;

  /**
   * Creates a hosting that uploads the pack at {@code zip} to mc-packs.net. Nothing is uploaded until
   * {@link #start()} is called.
   *
   * @param zip the path to the resource pack zip to upload
   * @throws NullPointerException if the zip path is null
   * @since 1.0.0
   */
  public McPackHosting(final Path zip) {
    this(zip, UPLOAD_URI, DOWNLOAD_URL_FORMAT);
  }

  @VisibleForTesting
  McPackHosting(final Path zip, final URI uploadUri, final String downloadUrlFormat) {
    Preconditions.checkNotNull(zip, "Resource pack path must not be null!");
    Preconditions.checkNotNull(uploadUri, "Upload URI must not be null!");
    Preconditions.checkNotNull(downloadUrlFormat, "Download URL format must not be null!");

    this.zip = zip;
    this.uploadUri = uploadUri;
    this.downloadUrlFormat = downloadUrlFormat;
  }

  /**
   * Uploads the resource pack unless an identical pack was uploaded before and is still available. Calling this
   * method again after a successful upload has no effect. If the upload cache cannot be read or written, a warning is
   * logged and the uploaded pack is used anyway.
   *
   * @throws McPacksException if the pack cannot be read or the upload fails
   * @since 1.0.0
   */
  @Override
  public void start() {
    if (this.url != null) {
      return;
    }

    final byte[] pack = this.readZip();
    final String hash = hash(pack, HASH_ALGORITHM);

    CACHE_LOCK.lock();
    try {
      this.url = this.findOrUpload(hash, pack);
    } finally {
      CACHE_LOCK.unlock();
    }
  }

  /**
   * Does nothing, because the pack stays available on mc-packs.net after it was uploaded.
   *
   * @since 1.0.0
   */
  @Override
  public void shutdown() {
    // the upload cannot be undone and stays available for other servers
  }

  private static Type createCacheType() {
    final TypeToken<Map<String, String>> cacheToken = new TypeToken<>() {};
    return cacheToken.getType();
  }

  @VisibleForTesting
  static String hash(final byte[] bytes, final String algorithm) {
    try {
      final MessageDigest digest = MessageDigest.getInstance(algorithm);
      final byte[] hash = digest.digest(bytes);
      return IoUtils.bytesToHexadecimal(hash);
    } catch (final NoSuchAlgorithmException exception) {
      throw new AssertionError(exception);
    }
  }

  private String findOrUpload(final String hash, final byte[] pack) {
    final Map<String, String> cache = this.readCache();
    final String cachedUrl = cache.get(hash);
    if (cachedUrl != null) {
      final boolean available = this.isAvailable(cachedUrl);
      if (available) {
        return cachedUrl;
      }
    }

    this.upload(pack);

    final String uploadedUrl = this.downloadUrlFormat.formatted(hash);
    cache.put(hash, uploadedUrl);
    this.writeCache(cache);

    return uploadedUrl;
  }

  private boolean isAvailable(final String url) {
    final URI uri;
    try {
      uri = URI.create(url);
    } catch (final IllegalArgumentException exception) {
      return false;
    }

    return NetworkUtils.isReachable(uri);
  }

  private void upload(final byte[] pack) {
    final UUID boundaryId = UUID.randomUUID();
    final String boundary = BOUNDARY_PREFIX + boundaryId;
    final HttpRequest request = this.createUploadRequest(boundary, pack);
    final HttpClient.Builder clientBuilder = HttpClient.newBuilder();
    clientBuilder.connectTimeout(TIMEOUT);
    clientBuilder.followRedirects(HttpClient.Redirect.NORMAL);
    try (final HttpClient client = clientBuilder.build()) {
      this.send(client, request);
    }
  }

  private HttpRequest createUploadRequest(final String boundary, final byte[] pack) {
    final byte[] body = this.createMultipartBody(boundary, pack);
    final HttpRequest.BodyPublisher publisher = HttpRequest.BodyPublishers.ofByteArray(body);
    final HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(this.uploadUri);
    requestBuilder.timeout(TIMEOUT);
    final String contentType = MULTIPART_CONTENT_TYPE_PREFIX + boundary;
    requestBuilder.header(CONTENT_TYPE_HEADER, contentType);
    requestBuilder.header(ACCEPT_HEADER, ANY_MEDIA_TYPE);
    requestBuilder.header(USER_AGENT_HEADER, USER_AGENT);
    requestBuilder.POST(publisher);
    return requestBuilder.build();
  }

  private void send(final HttpClient client, final HttpRequest request) {
    final HttpResponse.BodyHandler<String> bodyHandler = HttpResponse.BodyHandlers.ofString();
    try {
      final HttpResponse<String> response = client.send(request, bodyHandler);

      final int status = response.statusCode();
      if (status != HTTP_OK) {
        final String message = "mc-packs.net answered status %s and rejected the upload!".formatted(status);
        throw new McPacksException(message);
      }
    } catch (final IOException exception) {
      final String message = "The resource pack upload to mc-packs.net failed!";
      throw new McPacksException(message, exception);
    } catch (final InterruptedException exception) {
      final Thread current = Thread.currentThread();
      current.interrupt();

      final String message = "Interrupted while uploading the resource pack!";
      throw new McPacksException(message, exception);
    }
  }

  private byte[] createMultipartBody(final String boundary, final byte[] pack) {
    final String headText = this.createMultipartHead(boundary);
    final String tailText = "\r\n--%s--\r\n".formatted(boundary);
    final byte[] headBytes = headText.getBytes(StandardCharsets.UTF_8);
    final byte[] tailBytes = tailText.getBytes(StandardCharsets.UTF_8);
    return this.concatenate(headBytes, pack, tailBytes);
  }

  private byte[] readZip() {
    try {
      return Files.readAllBytes(this.zip);
    } catch (final IOException exception) {
      final String message = "Resource pack %s cannot be read!".formatted(this.zip);
      throw new McPacksException(message, exception);
    }
  }

  @VisibleForTesting
  String createMultipartHead(final String boundary) {
    final Path zipFileName = this.zip.getFileName();
    final Path fileNamePath = requireNonNull(zipFileName, "The resource pack has no file name!");
    final String fileName = fileNamePath.toString();
    final String safeFileName = UNSAFE_FILE_NAME_CHARACTERS.matcher(fileName).replaceAll("");
    return "--%s\r\nContent-Disposition: form-data; name=\"file\"; filename=\"%s\"\r\nContent-Type: application/zip\r\n\r\n".formatted(
      boundary,
      safeFileName
    );
  }

  private byte[] concatenate(final byte[]... parts) {
    int totalLength = 0;
    for (final byte[] part : parts) {
      totalLength += part.length;
    }

    final byte[] joined = new byte[totalLength];
    int offset = 0;
    for (final byte[] part : parts) {
      System.arraycopy(part, 0, joined, offset, part.length);
      offset += part.length;
    }

    return joined;
  }

  private Path getCacheFile() {
    final Path cacheFolder = IoUtils.getCachedFolder();
    return cacheFolder.resolve(CACHE_FILE);
  }

  private Map<String, String> readCache() {
    final Path cacheFile = this.getCacheFile();
    final boolean regularFile = Files.isRegularFile(cacheFile);
    if (!regularFile) {
      return new HashMap<>();
    }

    final Gson gson = GsonProvider.getSimple();
    try (final Reader reader = Files.newBufferedReader(cacheFile)) {
      final Map<String, String> cache = gson.fromJson(reader, CACHE_TYPE);
      return this.copyCache(cache);
    } catch (final IOException | JsonIOException | JsonSyntaxException exception) {
      LOGGER.warn("Could not read the upload cache {}, the pack will be uploaded again.", cacheFile, exception);
      return new HashMap<>();
    }
  }

  private Map<String, String> copyCache(final @Nullable Map<String, String> cache) {
    if (cache == null) {
      return new HashMap<>();
    }

    return new HashMap<>(cache);
  }

  private void writeCache(final Map<String, String> cache) {
    final Path cacheFile = this.getCacheFile();
    final Gson gson = GsonProvider.getSimple();
    try (final Writer writer = Files.newBufferedWriter(cacheFile)) {
      gson.toJson(cache, CACHE_TYPE, writer);
    } catch (final IOException | JsonIOException exception) {
      LOGGER.warn("Could not write the upload cache {}, the pack will be uploaded again next time.", cacheFile, exception);
    }
  }

  /**
   * Returns the download URL of the uploaded pack.
   *
   * @return the URL of the resource pack
   * @throws IllegalStateException if the pack has not been uploaded yet
   * @since 1.0.0
   */
  @Override
  public String getRawUrl() {
    final String currentUrl = this.url;
    if (currentUrl == null) {
      final String message = "The resource pack has not been uploaded yet, call start() first!";
      throw new IllegalStateException(message);
    }

    return currentUrl;
  }

  /**
   * Returns the resource pack zip that is uploaded to mc-packs.net.
   *
   * @return the path to the resource pack zip
   * @since 1.0.0
   */
  @Override
  public Path getZip() {
    return this.zip;
  }
}
