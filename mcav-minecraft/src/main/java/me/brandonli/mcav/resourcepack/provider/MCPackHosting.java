/*
 * This file is part of mcav, a media playback library for Minecraft
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
package me.brandonli.mcav.resourcepack.provider;

import com.google.gson.Gson;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import me.brandonli.mcav.json.GsonProvider;
import me.brandonli.mcav.utils.IOUtils;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The {@code MCPackHosting} class provides functionality for managing and hosting Minecraft resource
 * packs via the "mc-packs.net" platform. It handles uploading resource pack files, creating metadata,
 * and maintaining access URLs for hosted packs.
 * <p>
 * This class implements the {@link PackHosting} interface and provides methods to start and stop the hosting
 * process, retrieve the associated resource pack ZIP file path, and obtain the raw URL of the hosted pack.
 * <p>
 * Features include:
 * - Uploading resource packs to the hosting service.
 * - Generating and managing metadata for hosted packs, including load counts.
 * - Caching metadata locally for use during subsequent executions.
 */
public class MCPackHosting implements WebsiteHosting {

  private static final String WEBSITE_URL = "https://mc-packs.net";
  private static final String DOWNLOAD_WEBSITE_URL = "https://download.mc-packs.net";
  private static final String PACK_URL = "%s/pack/%s.zip";
  private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

  private final Path zip;
  private String url;
  private PackInfo info;

  /**
   * Creates a new instance of {@code MCPackHosting} using the specified path to the resource pack ZIP file.
   *
   * @param zip the path to the resource pack ZIP file to be managed and hosted
   */
  public MCPackHosting(final Path zip) {
    this.zip = zip;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getRawUrl() {
    return this.url;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void start() {
    final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    final PackInfo info = this.checkFileUrl(lock);
    this.info = Objects.requireNonNullElseGet(info, () -> this.createNewPackInfo(this.zip));
    this.url = this.updateAndRetrievePackJSON(lock, this.info);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void shutdown() {}

  /**
   * {@inheritDoc}
   */
  @Override
  public Path getZip() {
    return this.zip;
  }

  private PackInfo createNewPackInfo(final Path zip) {
    try {
      this.uploadPackPost(zip);
      final String hash = IOUtils.getSHA1Hash(zip);
      final String url = String.format(PACK_URL, DOWNLOAD_WEBSITE_URL, hash);
      return new PackInfo(url, 0);
    } catch (final IOException e) {
      throw new AssertionError(e);
    } catch (final InterruptedException e) {
      final Thread current = Thread.currentThread();
      current.interrupt();
      throw new AssertionError(e);
    }
  }

  private void uploadPackPost(final Path zip) throws IOException, InterruptedException {
    final byte[] fileBytes = Files.readAllBytes(zip);
    final HttpRequest.BodyPublisher bodyPublisher = HttpRequest.BodyPublishers.ofByteArray(fileBytes);
    final URI uri = URI.create(WEBSITE_URL);
    final HttpRequest request = HttpRequest.newBuilder()
      .uri(uri)
      .header("Content-Type", "application/json")
      .header("Accept", "*/*")
      .header("Accept-Encoding", "gzip, deflate, br, zstd")
      .header("Accept-Language", "en-US,en;q=0.9")
      .POST(bodyPublisher)
      .build();
    final HttpResponse.BodyHandler<String> bodyHandlers = HttpResponse.BodyHandlers.ofString();
    final HttpResponse<String> response = HTTP_CLIENT.send(request, bodyHandlers);
    final int status = response.statusCode();
    if (status != 200) {
      throw new IOException("Failed to upload file to MC-Packs.net! Status: " + status);
    }
  }

  private String updateAndRetrievePackJSON(final ReentrantReadWriteLock lock, final PackInfo info) {
    final Lock write = lock.writeLock();
    final Path path = this.getCachedFilePath();
    try (final Writer writer = Files.newBufferedWriter(path)) {
      final int loads = info.loads + 1;
      final PackInfo updated = new PackInfo(info.url, loads);
      final Gson gson = GsonProvider.getSimple();
      write.lock();
      gson.toJson(updated, writer);
      return updated.url;
    } catch (final IOException e) {
      throw new AssertionError(e);
    } finally {
      write.unlock();
    }
  }

  private @Nullable PackInfo checkFileUrl(final ReentrantReadWriteLock lock) {
    final Path path = this.getCachedFilePath();
    if (IOUtils.createFileIfNotExists(path)) {
      return null;
    }
    final Lock read = lock.readLock();
    read.lock();

    try (final Reader reader = Files.newBufferedReader(path)) {
      final Gson gson = GsonProvider.getSimple();
      final PackInfo info = gson.fromJson(reader, PackInfo.class);
      return info == null ? null : (info.loads > 10 ? null : info);
    } catch (final IOException e) {
      throw new AssertionError(e);
    } finally {
      read.unlock();
    }
  }

  private Path getCachedFilePath() {
    final Path data = IOUtils.getCachedFolder();
    return data.resolve("cached-packs.json");
  }

  /**
   * Represents metadata about a resource pack, encapsulating its download URL and the number
   * of times it has been loaded or accessed. This class is designed to be immutable and provides
   * functionality for equality comparison, hash code generation, and string representation.
   */
  public static final class PackInfo {

    private final String url;
    private final int loads;

    /**
     * Constructs an instance of {@code PackInfo} which represents the details
     * of a resource pack, including its download URL and the number of loads it has.
     *
     * @param url   the URL for downloading the resource pack
     * @param loads the number of loads or downloads associated with the resource pack
     */
    public PackInfo(final String url, final int loads) {
      this.url = url;
      this.loads = loads;
    }

    /**
     * Retrieves the URL associated with this instance.
     *
     * @return the URL as a String
     */
    public String getUrl() {
      return this.url;
    }

    /**
     * Retrieves the number of times the resource pack has been loaded.
     *
     * @return the load count as an integer
     */
    public int getLoads() {
      return this.loads;
    }

    /**
     * Compares this instance with the specified object to determine equality.
     * The comparison is based on the {@code url} and {@code loads} fields.
     *
     * @param o the object to be compared for equality with this instance
     * @return {@code true} if the specified object is equal to this instance,
     * {@code false} otherwise
     */
    @Override
    public boolean equals(final @Nullable Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || this.getClass() != o.getClass()) {
        return false;
      }
      final PackInfo packInfo = (PackInfo) o;
      return this.loads == packInfo.loads && this.url.equals(packInfo.url);
    }

    /**
     * Computes the hash code for this object based on its URL and load properties.
     * This method is overridden to ensure that objects with the same URL and load
     * values produce the same hash code, satisfying the general contract of
     * {@code hashCode}.
     *
     * @return the hash code of this object as an integer
     */
    @Override
    public int hashCode() {
      return Objects.hash(this.url, this.loads);
    }

    /**
     * Returns a string representation of the {@code PackInfo} object. The string includes
     * the URL and the number of loads associated with the pack, formatted for readability.
     *
     * @return a string representation of this {@code PackInfo} instance
     */
    @Override
    public String toString() {
      return "PackInfo{" + "url='" + this.url + '\'' + ", loads=" + this.loads + '}';
    }
  }
}
