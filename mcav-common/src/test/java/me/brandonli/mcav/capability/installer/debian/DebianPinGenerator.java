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

import com.google.common.base.CharMatcher;
import com.google.common.base.Splitter;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import me.brandonli.mcav.utils.IoUtils;
import me.brandonli.mcav.utils.http.HttpDownloader;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.tukaani.xz.XZInputStream;

// Generates the pinned Debian package lists that mcav fetches libraries from: it resolves the dependency closure of a
// few root packages in the bookworm and bookworm-security indexes of one snapshot of each Debian archive, the newest
// version of a package winning, so every URL stays valid after later point releases, and writes each package with the
// SHA-256 its index lists, dependencies before their dependents. Packages every glibc system has (libc6 and the GCC
// runtime) and architecture-independent packages, which hold no libraries, are left out. Run it with
// ./gradlew :mcav-common:generateDebianPins after a Debian point release or security update.
final class DebianPinGenerator {

  private static final DateTimeFormatter SNAPSHOT_DAY = DateTimeFormatter.ofPattern("yyyyMMdd'T000000Z/'", Locale.ROOT).withZone(
    ZoneOffset.UTC
  );
  private static final String LOCATION_HEADER = "location";
  private static final int FOUND_STATUS = 302;

  private static final String INDEX_FILE_NAME = "Packages.xz";
  private static final String PATH_SEPARATOR = "/";
  private static final String TEMPORARY_DIRECTORY_PREFIX = "mcav-debian-pins";
  private static final long MAXIMUM_INDEX_SIZE = 64L * 1024L * 1024L;

  // a mebibyte is 1024 kibibytes; the index is compressed with xz -6, whose dictionary needs 9 MiB to decompress
  private static final int XZ_MEMORY_LIMIT_KIBIBYTES = 64 * 1024;

  private static final Set<String> BASE_PACKAGES = Set.of("libc6", "libgcc-s1", "libstdc++6");
  private static final String INDEPENDENT_ARCHITECTURE = "all";

  private static final String PACKAGE_FIELD = "Package";
  private static final String VERSION_FIELD = "Version";
  private static final String ARCHITECTURE_FIELD = "Architecture";
  private static final String FILENAME_FIELD = "Filename";
  private static final String SHA256_FIELD = "SHA256";
  private static final List<String> DEPENDENCY_FIELDS = List.of("Pre-Depends", "Depends");
  private static final String PROVIDES_FIELD = "Provides";

  private static final Splitter STANZAS = Splitter.on("\n\n").omitEmptyStrings();
  private static final Splitter LINES = Splitter.on('\n').omitEmptyStrings();
  private static final Splitter RELATIONS = Splitter.on(',').trimResults().omitEmptyStrings();
  private static final Splitter ALTERNATIVES = Splitter.on('|').trimResults();
  private static final CharMatcher NAME_END = CharMatcher.anyOf(" :(");
  private static final CharMatcher CONTINUATION = CharMatcher.whitespace();
  private static final String FIELD_SEPARATOR = ":";
  private static final String LINE_END = "\n";

  private static final Comparator<String> VERSIONS = new DebianVersionComparator();

  private static final List<Bundle> BUNDLES = List.of(
    new Bundle("debian-vlc.json", List.of("libvlc5", "libvlccore9", "vlc-plugin-base")),
    new Bundle("debian-gtk2.json", List.of("libgtk2.0-0")),
    new Bundle("debian-ffmpeg.json", List.of("libasound2", "libpulse0", "libxcb1", "libxcb-shm0", "libudev1"))
  );

  static void main(final String[] arguments) throws IOException, InterruptedException {
    final Path outputDirectory = Path.of(arguments[0]);
    final DebianPinGenerator generator = new DebianPinGenerator();
    generator.generate(outputDirectory);
  }

  private void generate(final Path outputDirectory) throws IOException, InterruptedException {
    final Map<Archive, URI> snapshots = new EnumMap<>(Archive.class);
    for (final Archive archive : Archive.values()) {
      final URI snapshot = this.findSnapshot(archive);
      snapshots.put(archive, snapshot);
    }

    final Path temporary = Files.createTempDirectory(TEMPORARY_DIRECTORY_PREFIX);
    try {
      final Map<Architecture, List<Index>> indexes = new EnumMap<>(Architecture.class);
      for (final Architecture architecture : Architecture.values()) {
        final List<Index> architectureIndexes = this.downloadIndexes(snapshots, architecture, temporary);
        indexes.put(architecture, architectureIndexes);
      }

      for (final Bundle bundle : BUNDLES) {
        this.writeBundle(outputDirectory, bundle, indexes);
      }
    } finally {
      IoUtils.deleteRecursively(temporary);
    }
  }

  // Asks a snapshot archive for its last snapshot before today, which it answers with a redirect to that snapshot.
  private URI findSnapshot(final Archive archive) throws IOException, InterruptedException {
    final Instant now = Instant.now();
    final String day = SNAPSHOT_DAY.format(now);
    final URI request = URI.create(archive.getUrl() + day);
    final HttpRequest httpRequest = HttpRequest.newBuilder(request).build();
    final HttpResponse.BodyHandler<Void> discarding = HttpResponse.BodyHandlers.discarding();
    try (final HttpClient client = HttpClient.newHttpClient()) {
      final HttpResponse<Void> response = client.send(httpRequest, discarding);
      final int status = response.statusCode();
      final HttpHeaders headers = response.headers();
      final Optional<String> location = headers.firstValue(LOCATION_HEADER);
      final @Nullable String target = location.orElse(null);
      if (status != FOUND_STATUS || target == null) {
        final String message = "Snapshot archive answered %s without a redirect!".formatted(status);
        throw new IOException(message);
      }

      return request.resolve(target);
    }
  }

  private List<Index> downloadIndexes(final Map<Archive, URI> snapshots, final Architecture architecture, final Path temporary)
    throws IOException {
    final List<Index> indexes = new ArrayList<>();
    for (final Archive archive : Archive.values()) {
      final URI snapshot = snapshots.get(archive);
      final String text = this.downloadIndex(snapshot, archive, architecture, temporary);
      final Index index = new Index(snapshot, text);
      indexes.add(index);
    }

    return indexes;
  }

  private String downloadIndex(final URI snapshot, final Archive archive, final Architecture architecture, final Path temporary)
    throws IOException {
    final String debianName = architecture.getDebianName();
    final URI index = snapshot.resolve(archive.getIndexDirectoryPrefix() + debianName + PATH_SEPARATOR + INDEX_FILE_NAME);
    final Path directory = Files.createDirectories(temporary.resolve(archive.name()).resolve(debianName));
    final Path file = directory.resolve(INDEX_FILE_NAME);
    HttpDownloader.download(index, file, MAXIMUM_INDEX_SIZE);
    try (
      final InputStream compressed = Files.newInputStream(file);
      final InputStream xz = new XZInputStream(compressed, XZ_MEMORY_LIMIT_KIBIBYTES)
    ) {
      final byte[] text = xz.readAllBytes();
      return new String(text, StandardCharsets.UTF_8);
    }
  }

  private void writeBundle(final Path outputDirectory, final Bundle bundle, final Map<Architecture, List<Index>> indexes)
    throws IOException {
    final JsonArray downloads = new JsonArray();
    for (final Architecture architecture : Architecture.values()) {
      final List<Index> architectureIndexes = indexes.get(architecture);
      final List<IndexEntry> closure = this.resolveClosure(architectureIndexes, bundle.roots());
      for (final IndexEntry entry : closure) {
        final JsonObject download = this.toDownload(architecture, entry);
        downloads.add(download);
      }
    }

    final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    final String json = gson.toJson(downloads) + LINE_END;
    final Path file = outputDirectory.resolve(bundle.fileName());
    Files.writeString(file, json, StandardCharsets.UTF_8);
  }

  private JsonObject toDownload(final Architecture architecture, final IndexEntry entry) {
    final JsonObject platform = new JsonObject();
    platform.addProperty("os", "LINUX");
    platform.addProperty("arch", architecture.getArch());
    platform.addProperty("bits", "BITS_64");
    final URI snapshot = entry.snapshot();
    final URI url = snapshot.resolve(entry.filename());
    final JsonObject download = new JsonObject();
    download.add("platform", platform);
    download.addProperty("url", url.toString());
    download.addProperty("hash", entry.sha256());
    return download;
  }

  // Resolves the packages that the roots need, each after the packages it depends on. Only the first alternative of
  // a dependency is followed, and a virtual package resolves to its first provider in the indexes.
  List<IndexEntry> resolveClosure(final List<Index> indexes, final List<String> roots) {
    final Map<String, IndexEntry> entries = this.parseIndexes(indexes);
    final Map<String, String> providers = this.findProviders(entries);
    final Map<String, IndexEntry> resolved = new LinkedHashMap<>();
    for (final String root : roots) {
      this.visit(root, entries, providers, resolved);
    }

    final Collection<IndexEntry> closure = resolved.values();
    return List.copyOf(closure);
  }

  private void visit(
    final String name,
    final Map<String, IndexEntry> entries,
    final Map<String, String> providers,
    final Map<String, IndexEntry> resolved
  ) {
    if (BASE_PACKAGES.contains(name)) {
      return;
    }

    final String real = entries.containsKey(name) ? name : providers.getOrDefault(name, name);
    final @Nullable IndexEntry entry = entries.get(real);
    if (entry == null) {
      final String message = "Package %s is missing from the indexes!".formatted(name);
      throw new IllegalStateException(message);
    }

    final boolean independent = entry.architecture().equals(INDEPENDENT_ARCHITECTURE);
    if (resolved.containsKey(real) || independent) {
      return;
    }

    // marked before its dependencies, so a dependency cycle ends here, then moved behind the dependencies
    resolved.put(real, entry);
    for (final String dependency : entry.dependencies()) {
      this.visit(dependency, entries, providers, resolved);
    }

    resolved.remove(real);
    resolved.put(real, entry);
  }

  private Map<String, String> findProviders(final Map<String, IndexEntry> entries) {
    final Map<String, String> providers = new HashMap<>();
    for (final IndexEntry entry : entries.values()) {
      for (final String provided : entry.provides()) {
        providers.putIfAbsent(provided, entry.name());
      }
    }

    return providers;
  }

  // an equal version keeps the entry of the earlier index
  private Map<String, IndexEntry> parseIndexes(final List<Index> indexes) {
    final Map<String, IndexEntry> entries = new LinkedHashMap<>();
    for (final Index index : indexes) {
      final List<IndexEntry> parsed = this.parseIndex(index);
      for (final IndexEntry entry : parsed) {
        entries.merge(entry.name(), entry, this::selectNewer);
      }
    }

    return entries;
  }

  private List<IndexEntry> parseIndex(final Index index) {
    final List<IndexEntry> entries = new ArrayList<>();
    for (final String stanza : STANZAS.split(index.text())) {
      final Map<String, String> fields = this.parseFields(stanza);
      final IndexEntry entry = this.toEntry(fields, index.snapshot());
      entries.add(entry);
    }

    return entries;
  }

  private IndexEntry selectNewer(final IndexEntry earlier, final IndexEntry later) {
    final int order = VERSIONS.compare(earlier.version(), later.version());
    return order >= 0 ? earlier : later;
  }

  private Map<String, String> parseFields(final String stanza) {
    final Map<String, String> fields = new HashMap<>();
    for (final String line : LINES.split(stanza)) {
      final boolean continuation = CONTINUATION.matches(line.charAt(0));
      final int separator = line.indexOf(FIELD_SEPARATOR);
      if (continuation || separator < 0) {
        continue;
      }

      final String key = line.substring(0, separator);
      final String value = line.substring(separator + 1).strip();
      fields.put(key, value);
    }

    return fields;
  }

  private IndexEntry toEntry(final Map<String, String> fields, final URI snapshot) {
    final String name = fields.getOrDefault(PACKAGE_FIELD, "");
    final String version = fields.getOrDefault(VERSION_FIELD, "");
    final String architecture = fields.getOrDefault(ARCHITECTURE_FIELD, "");
    final String filename = fields.getOrDefault(FILENAME_FIELD, "");
    final String sha256 = fields.getOrDefault(SHA256_FIELD, "");
    final List<String> dependencies = new ArrayList<>();
    for (final String field : DEPENDENCY_FIELDS) {
      final String relations = fields.getOrDefault(field, "");
      final List<String> names = this.readFirstAlternatives(relations);
      dependencies.addAll(names);
    }

    final String provided = fields.getOrDefault(PROVIDES_FIELD, "");
    final List<String> provides = this.readFirstAlternatives(provided);
    return new IndexEntry(name, version, architecture, filename, sha256, dependencies, provides, snapshot);
  }

  private List<String> readFirstAlternatives(final String relations) {
    final List<String> names = new ArrayList<>();
    for (final String relation : RELATIONS.split(relations)) {
      final Iterable<String> alternatives = ALTERNATIVES.split(relation);
      final String first = alternatives.iterator().next();
      final int end = NAME_END.indexIn(first);
      final String name = end < 0 ? first : first.substring(0, end);
      names.add(name);
    }

    return names;
  }

  private enum Archive {
    MAIN("https://snapshot.debian.org/archive/debian/", "dists/bookworm/main/binary-"),
    SECURITY("https://snapshot.debian.org/archive/debian-security/", "dists/bookworm-security/main/binary-");

    private final String url;
    private final String indexDirectoryPrefix;

    Archive(final String url, final String indexDirectoryPrefix) {
      this.url = url;
      this.indexDirectoryPrefix = indexDirectoryPrefix;
    }

    String getUrl() {
      return this.url;
    }

    String getIndexDirectoryPrefix() {
      return this.indexDirectoryPrefix;
    }
  }

  private enum Architecture {
    AMD64("amd64", "X86"),
    ARM64("arm64", "ARM");

    private final String debianName;
    private final String arch;

    Architecture(final String debianName, final String arch) {
      this.debianName = debianName;
      this.arch = arch;
    }

    String getDebianName() {
      return this.debianName;
    }

    String getArch() {
      return this.arch;
    }
  }

  private record Bundle(String fileName, List<String> roots) {}

  record Index(URI snapshot, String text) {}

  record IndexEntry(
    String name,
    String version,
    String architecture,
    String filename,
    String sha256,
    List<String> dependencies,
    List<String> provides,
    URI snapshot
  ) {}
}
