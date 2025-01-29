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
 * The concrete implementation of {@link WebsiteHosting} for hosting resource packs on MCPacks.net. Periodically
 * caches the pack information to avoid excessive loads on the server.
 */
public class MCPackHosting implements WebsiteHosting {

  private static final String WEBSITE_URL = "https://mc-packs.net";
  private static final String DOWNLOAD_WEBSITE_URL = "https://download.mc-packs.net";
  private static final String PACK_URL = "%s/pack/%s.zip";
  private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

  private final Path zip;
  private String url;

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
    final PackInfo info1 = Objects.requireNonNullElseGet(info, () -> this.createNewPackInfo(this.zip));
    this.url = this.updateAndRetrievePackJSON(lock, info1);
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
      throw new MCPacksException(e.getMessage(), e);
    } catch (final InterruptedException e) {
      final Thread current = Thread.currentThread();
      current.interrupt();
      throw new MCPacksException(e.getMessage(), e);
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
      throw new MCPacksException("Failed to upload file to MC-Packs.net!");
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
      throw new MCPacksException(e.getMessage(), e);
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
      throw new MCPacksException(e.getMessage(), e);
    } finally {
      read.unlock();
    }
  }

  private Path getCachedFilePath() {
    final Path data = IOUtils.getCachedFolder();
    return data.resolve("cached-packs.json");
  }

  static final class PackInfo {

    private final String url;
    private final int loads;

    PackInfo(final String url, final int loads) {
      this.url = url;
      this.loads = loads;
    }

    String getUrl() {
      return this.url;
    }

    int getLoads() {
      return this.loads;
    }

    /**
     * {@inheritDoc}
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
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
      return Objects.hash(this.url, this.loads);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
      return "PackInfo{" + "url='" + this.url + '\'' + ", loads=" + this.loads + '}';
    }
  }
}
