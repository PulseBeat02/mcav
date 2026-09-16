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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.gson.Gson;
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
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import me.brandonli.mcav.json.GsonProvider;
import me.brandonli.mcav.utils.IOUtils;
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
 */
public class MCPackHosting implements WebsiteHosting {

  private static final Logger LOGGER = LoggerFactory.getLogger(MCPackHosting.class);
  private static final URI UPLOAD_URI = URI.create("https://mc-packs.net");
  private static final String DOWNLOAD_URL_FORMAT = "https://download.mc-packs.net/pack/%s.zip";
  private static final String HASH_ALGORITHM = "SHA-1";
  private static final String CACHE_FILE = "mc-packs-cache.json";
  private static final Type CACHE_TYPE = createCacheType();
  private static final Duration TIMEOUT = Duration.ofMinutes(5);
  private static final int HTTP_OK = 200;
  private static final Lock CACHE_LOCK = new ReentrantLock();

  private final Path zip;
  private final URI uploadUri;
  private final String downloadUrlFormat;

  private volatile @Nullable String url;

  /**
   * Constructs a new {@code MCPackHosting}. Nothing is uploaded until {@link #start()} is called.
   *
   * @param zip the path to the resource pack zip to upload
   */
  public MCPackHosting(final Path zip) {
    this(zip, UPLOAD_URI, DOWNLOAD_URL_FORMAT);
  }

  /**
   * Constructs a new {@code MCPackHosting} that talks to another upload service.
   *
   * @param zip               the path to the resource pack zip to upload
   * @param uploadUri         the URI the pack is posted to as a multipart form
   * @param downloadUrlFormat the format of the download URL, with one placeholder for the SHA-1 hash of the pack
   */
  @VisibleForTesting
  MCPackHosting(final Path zip, final URI uploadUri, final String downloadUrlFormat) {
    Preconditions.checkNotNull(zip, "Resource pack path must not be null");
    Preconditions.checkNotNull(uploadUri, "Upload URI must not be null");
    Preconditions.checkNotNull(downloadUrlFormat, "Download URL format must not be null");
    this.zip = zip;
    this.uploadUri = uploadUri;
    this.downloadUrlFormat = downloadUrlFormat;
  }

  private static Type createCacheType() {
    final TypeToken<Map<String, String>> cacheToken = new TypeToken<>() {};
    return cacheToken.getType();
  }

  /**
   * Gets the download URL of the uploaded pack.
   *
   * @return the URL of the resource pack
   * @throws IllegalStateException if the pack has not been uploaded yet
   */
  @Override
  public String getRawUrl() {
    final String currentUrl = this.url;
    if (currentUrl == null) {
      throw new IllegalStateException("The resource pack has not been uploaded yet, call start() first");
    }
    return currentUrl;
  }

  /**
   * Uploads the resource pack unless an identical pack was uploaded before and is still available. Calling this
   * method again after a successful upload has no effect. If the upload cache cannot be written, a warning is
   * logged and the uploaded pack is used anyway.
   *
   * @throws MCPacksException if the pack cannot be read or the upload fails
   */
  @Override
  public void start() {
    if (this.url != null) {
      return;
    }

    // the pack is read once, so the uploaded bytes always match the hash the download URL is derived from
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
   * Hashes bytes and formats the hash as lowercase hexadecimal digits.
   *
   * @param bytes     the bytes to hash
   * @param algorithm the name of the hash algorithm, such as {@code SHA-1}
   * @return the hash in hexadecimal
   * @throws IllegalStateException if the algorithm is not available
   */
  @VisibleForTesting
  static String hash(final byte[] bytes, final String algorithm) {
    try {
      final MessageDigest digest = MessageDigest.getInstance(algorithm);
      final byte[] hash = digest.digest(bytes);
      return IOUtils.bytesToHex(hash);
    } catch (final NoSuchAlgorithmException exception) {
      final String message = "Hash algorithm %s is not available".formatted(algorithm);
      throw new IllegalStateException(message, exception);
    }
  }

  /**
   * Looks the pack up in the upload cache, uploading it only if it was never uploaded before or its download is
   * no longer available.
   *
   * @param hash the SHA-1 hash of the pack
   * @param pack the bytes of the pack
   * @return the download URL of the pack
   */
  private String findOrUpload(final String hash, final byte[] pack) {
    final Map<String, String> cache = readCache();
    final String cachedUrl = cache.get(hash);
    if (cachedUrl != null) {
      final boolean available = isAvailable(cachedUrl);
      if (available) {
        return cachedUrl;
      }
    }

    this.upload(pack);
    final String uploadedUrl = this.downloadUrlFormat.formatted(hash);
    cache.put(hash, uploadedUrl);
    writeCache(cache);
    return uploadedUrl;
  }

  // a cache entry that is not even a valid URI is as useless as a download that is gone
  private static boolean isAvailable(final String url) {
    final URI uri;
    try {
      uri = URI.create(url);
    } catch (final IllegalArgumentException exception) {
      return false;
    }
    return NetworkUtils.isReachable(uri);
  }

  /**
   * Does nothing, because the pack stays available on mc-packs.net after it was uploaded.
   */
  @Override
  public void shutdown() {
    // the upload cannot be undone and stays available for other servers
  }

  /**
   * Gets the resource pack zip that is uploaded to mc-packs.net.
   *
   * @return the path to the resource pack zip
   */
  @Override
  public Path getZip() {
    return this.zip;
  }

  private void upload(final byte[] pack) {
    final UUID boundaryId = UUID.randomUUID();
    final String boundary = "----mcav-" + boundaryId;
    final HttpRequest request = this.createUploadRequest(boundary, pack);
    final HttpClient.Builder clientBuilder = HttpClient.newBuilder();
    clientBuilder.connectTimeout(TIMEOUT);
    clientBuilder.followRedirects(HttpClient.Redirect.NORMAL);
    try (final HttpClient client = clientBuilder.build()) {
      send(client, request);
    }
  }

  private HttpRequest createUploadRequest(final String boundary, final byte[] pack) {
    final byte[] body = this.createMultipartBody(boundary, pack);
    final HttpRequest.BodyPublisher publisher = HttpRequest.BodyPublishers.ofByteArray(body);
    final HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(this.uploadUri);
    requestBuilder.timeout(TIMEOUT);
    requestBuilder.header("Content-Type", "multipart/form-data; boundary=" + boundary);
    requestBuilder.header("Accept", "*/*");
    requestBuilder.header("User-Agent", "mcav");
    requestBuilder.POST(publisher);
    return requestBuilder.build();
  }

  private static void send(final HttpClient client, final HttpRequest request) {
    final HttpResponse.BodyHandler<String> bodyHandler = HttpResponse.BodyHandlers.ofString();
    try {
      final HttpResponse<String> response = client.send(request, bodyHandler);
      final int status = response.statusCode();
      if (status != HTTP_OK) {
        final String message = "mc-packs.net rejected the upload with status %d".formatted(status);
        throw new MCPacksException(message);
      }
    } catch (final IOException exception) {
      final String message = exception.getMessage();
      throw new MCPacksException(message, exception);
    } catch (final InterruptedException exception) {
      final Thread currentThread = Thread.currentThread();
      currentThread.interrupt();
      throw new MCPacksException("Interrupted while uploading the resource pack", exception);
    }
  }

  private byte[] createMultipartBody(final String boundary, final byte[] pack) {
    final String headText = this.createMultipartHead(boundary);
    final String tailText = "\r\n--" + boundary + "--\r\n";
    final byte[] headBytes = headText.getBytes(StandardCharsets.UTF_8);
    final byte[] tailBytes = tailText.getBytes(StandardCharsets.UTF_8);
    return concatenate(headBytes, pack, tailBytes);
  }

  private byte[] readZip() {
    try {
      return Files.readAllBytes(this.zip);
    } catch (final IOException exception) {
      final String message = "Resource pack cannot be read: %s".formatted(this.zip);
      throw new MCPacksException(message, exception);
    }
  }

  private String createMultipartHead(final String boundary) {
    // the pack was read as a regular file, and the path of a regular file always ends with a file name
    final Path zipFileName = this.zip.getFileName();
    final Path fileNamePath = Objects.requireNonNull(zipFileName, "The resource pack has no file name");
    final String fileName = fileNamePath.toString();
    final String safeFileName = fileName.replace("\"", "");
    return (
      "--" +
      boundary +
      "\r\n" +
      "Content-Disposition: form-data; name=\"file\"; filename=\"" +
      safeFileName +
      "\"\r\n" +
      "Content-Type: application/zip\r\n\r\n"
    );
  }

  private static byte[] concatenate(final byte[]... parts) {
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

  private static Path getCacheFile() {
    final Path cacheFolder = IOUtils.getCachedFolder();
    return cacheFolder.resolve(CACHE_FILE);
  }

  private static Map<String, String> readCache() {
    final Path cacheFile = getCacheFile();
    final boolean exists = Files.isRegularFile(cacheFile);
    if (!exists) {
      return new HashMap<>();
    }
    final Gson gson = GsonProvider.getSimple();
    try (final Reader reader = Files.newBufferedReader(cacheFile)) {
      final Map<String, String> cache = gson.fromJson(reader, CACHE_TYPE);
      return copyCache(cache);
    } catch (final IOException | JsonSyntaxException exception) {
      // a corrupt cache only costs one extra upload
      return new HashMap<>();
    }
  }

  // an empty cache file parses as null
  private static Map<String, String> copyCache(final @Nullable Map<String, String> cache) {
    if (cache == null) {
      return new HashMap<>();
    }
    return new HashMap<>(cache);
  }

  private static void writeCache(final Map<String, String> cache) {
    final Path cacheFile = getCacheFile();
    final Gson gson = GsonProvider.getSimple();
    try (final Writer writer = Files.newBufferedWriter(cacheFile)) {
      gson.toJson(cache, CACHE_TYPE, writer);
    } catch (final IOException exception) {
      // the upload itself succeeded, a missing cache entry only costs one extra upload on the next start
      LOGGER.warn("Could not write the upload cache {}, the pack will be uploaded again next time", cacheFile, exception);
    }
  }
}
