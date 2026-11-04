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
package me.brandonli.mcav.capability.installer.debian;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import me.brandonli.mcav.MCAVException;
import me.brandonli.mcav.utils.FileOperationException;
import me.brandonli.mcav.utils.http.DownloadException;
import me.brandonli.mcav.utils.http.HttpDownloader;
import org.checkerframework.checker.nullness.qual.Nullable;

// Downloads each pinned package from the live Debian pool first and from its snapshot URL second; the pinned hash
// decides whether a file is the package. A source that failed is tried last for the packages that follow.
final class DebianSources {

  private static final String SNAPSHOT_ARCHIVE = "https://snapshot.debian.org/archive/";
  private static final String LIVE_ARCHIVE = "https://deb.debian.org/";

  private static final String POOL_FOLDER = "pool/";
  private static final char PATH_SEPARATOR = '/';

  private final String snapshotArchive;
  private final String liveArchive;
  private final List<Source> order;

  // tests point both archives at a local server
  DebianSources(final String snapshotArchive, final String liveArchive) {
    this.snapshotArchive = snapshotArchive;
    this.liveArchive = liveArchive;
    this.order = new ArrayList<>(List.of(Source.LIVE, Source.SNAPSHOT));
  }

  static DebianSources ofDebian() {
    return new DebianSources(SNAPSHOT_ARCHIVE, LIVE_ARCHIVE);
  }

  void download(final URI pinnedUri, final Path archive, final String sha256, final long maximumBytes) {
    final Map<Source, URI> uris = this.findUris(pinnedUri);
    final List<Source> sources = List.copyOf(this.order);
    final List<MCAVException> failures = new ArrayList<>();
    for (final Source source : sources) {
      final @Nullable URI uri = uris.get(source);
      if (uri == null) {
        continue;
      }
      try {
        HttpDownloader.download(uri, archive, sha256, maximumBytes);
        return;
      } catch (final DownloadException | FileOperationException exception) {
        failures.add(exception);
        this.moveLast(source);
      }
    }

    throw this.combineFailures(failures);
  }

  private Map<Source, URI> findUris(final URI pinnedUri) {
    final String pinnedUrl = pinnedUri.toString();
    final Optional<String> liveUrl = this.findLiveUrl(pinnedUrl);
    final Optional<URI> liveUri = liveUrl.map(URI::create);
    final Map<Source, URI> uris = new EnumMap<>(Source.class);
    uris.put(Source.SNAPSHOT, pinnedUri);
    liveUri.ifPresent(uri -> uris.put(Source.LIVE, uri));
    return uris;
  }

  Optional<String> findLiveUrl(final String pinnedUrl) {
    if (!pinnedUrl.startsWith(this.snapshotArchive)) {
      return Optional.empty();
    }

    final int archiveStart = this.snapshotArchive.length();
    final int archiveEnd = pinnedUrl.indexOf(PATH_SEPARATOR, archiveStart);
    final int runEnd = pinnedUrl.indexOf(PATH_SEPARATOR, archiveEnd + 1);
    final int poolStart = runEnd + 1;
    final boolean segmentsFound = archiveEnd > archiveStart && runEnd > archiveEnd + 1;
    if (!segmentsFound || !pinnedUrl.startsWith(POOL_FOLDER, poolStart)) {
      return Optional.empty();
    }

    final String archive = pinnedUrl.substring(archiveStart, archiveEnd + 1);
    final String pool = pinnedUrl.substring(poolStart);
    final String liveUrl = this.liveArchive + archive + pool;
    return Optional.of(liveUrl);
  }

  private void moveLast(final Source source) {
    this.order.remove(source);
    this.order.add(source);
  }

  private MCAVException combineFailures(final List<MCAVException> failures) {
    final MCAVException first = failures.getFirst();
    final List<MCAVException> others = failures.subList(1, failures.size());
    others.forEach(first::addSuppressed);
    return first;
  }

  private enum Source {
    LIVE,
    SNAPSHOT,
  }
}
