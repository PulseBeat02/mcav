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
package me.brandonli.mcav.browser;

import static java.util.Objects.requireNonNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import me.brandonli.mcav.capability.installer.InstallationException;
import me.brandonli.mcav.capability.installer.debian.DebianPackageExtractor;
import me.brandonli.mcav.utils.FileOperationException;
import me.brandonli.mcav.utils.IoUtils;
import me.brandonli.mcav.utils.http.DownloadException;
import me.brandonli.mcav.utils.http.HttpDownloader;
import org.checkerframework.checker.initialization.qual.UnderInitialization;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// libcef.so needs X11, GLib, NSS, ATK and more at load time although CEF draws off-screen, and the image of a Minecraft
// server often lacks them. MCAV fetches them from Debian 11's frozen archive, pinned by SHA-256, and links only the
// missing ones into the session, so a newer library of the server is never paired with an older bundled one.
final class LinuxLibraries {

  private static final String RESOURCE = "mcav/browser/linux-libraries.txt";

  static final String INSTALL_MARKER = "install.lock";

  static final String SESSION_FOLDER = "lib";

  static final String JAWT = "libjawt.so";

  // tried in order; the hash decides whether a package is the pinned one
  static final List<String> MIRRORS = List.of("https://archive.debian.org/debian/", "https://deb.debian.org/debian/");

  // for the pins in pool/updates/; the snapshot archive keeps every file under a permanent address
  static final List<String> SECURITY_MIRRORS = List.of(
    "https://archive.debian.org/debian-security/",
    "https://snapshot.debian.org/archive/debian-security/20260901T000000Z/"
  );

  private static final Logger LOGGER = LoggerFactory.getLogger(LinuxLibraries.class);

  // interned, so every shaded copy of MCAV in the JVM shares it
  @VisibleForTesting
  static final Object INSTALL_LOCK = "the installation of the Linux libraries of mcav";

  private static final Pattern PLATFORM = Pattern.compile("[a-z0-9]+-[a-z0-9]+");
  private static final Pattern SONAME = Pattern.compile("lib[A-Za-z0-9_+.-]+\\.so(\\.[0-9]+)*");
  private static final Pattern PACKAGE_PATH = Pattern.compile("(usr/)?lib/[a-z0-9_-]+/(nss/)?[A-Za-z0-9_+.-]+");
  private static final Pattern POOL_PATH = Pattern.compile("pool/(updates/)?main/[a-z0-9+.-]+/[a-z0-9+.-]+/[A-Za-z0-9_+.:~-]+\\.deb");
  private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");

  private static final String SECURITY_POOL = "pool/updates/";

  private static final byte[] ELF_64_LITTLE_ENDIAN = { 0x7F, 'E', 'L', 'F', 2, 1 };
  private static final int ELF_MACHINE_OFFSET = 18;
  private static final int MACHINE_X86_64 = 62;
  private static final int MACHINE_AARCH64 = 183;

  private static final String CACHE_FOLDER = "jcef-libraries";
  private static final String INSTALLATION_PREFIX = "debian-11-";
  private static final String NAME_SEPARATOR = "-";
  private static final String LOCK_SUFFIX = ".lock";
  private static final String STAGING_SUFFIX = ".staging";
  private static final String DOWNLOAD_SUFFIX = ".deb";
  private static final String UNPACKED_SUFFIX = ".unpacked";
  private static final int FINGERPRINT_DIGITS = 12;

  private static final long BYTES_PER_MEGABYTE = 1_000_000L;

  private static final String LIST_SEPARATOR = ", ";

  private static final String PARENT_FOLDER = "..";
  private static final String RUNTIME_LIBRARY_FOLDER = "lib";

  private static final int UNSIGNED_BYTE_MASK = 0xFF;

  private static final String ARM64_SUFFIX = "arm64";
  private static final String AARCH64_TRIPLET = "aarch64-linux-gnu";
  private static final String X86_64_TRIPLET = "x86_64-linux-gnu";

  private static final List<String> MULTIARCH_FOLDERS = List.of("lib/", "usr/lib/");
  private static final List<String> DEFAULT_FOLDERS = List.of("lib64", "usr/lib64", "lib", "usr/lib");

  private static final String LOADER_CONFIGURATION = "etc/ld.so.conf";
  private static final String COMMENT_PREFIX = "#";
  private static final String INCLUDE_PREFIX = "include ";
  private static final int MAXIMUM_INCLUDE_DEPTH = 8;
  private static final String FOLDER_SEPARATOR = "/";
  private static final String GLOB_SYNTAX = "glob:";

  private static final Pattern FIELD_SEPARATOR = Pattern.compile(" +");
  private static final String HOST_KEYWORD = "host";
  private static final int HOST_PLATFORM_FIELD = 1;
  private static final int HOST_FIRST_SONAME_FIELD = 2;

  private static final int PIN_PLATFORM_FIELD = 0;
  private static final int PIN_NAME_FIELD = 1;
  private static final int PIN_VERSION_FIELD = 2;
  private static final int PIN_HASH_FIELD = 3;
  private static final int PIN_SIZE_FIELD = 4;
  private static final int PIN_POOL_FIELD = 5;
  private static final int PIN_FIRST_FILE_FIELD = 6;
  private static final int MINIMUM_PIN_FIELDS = 7;
  private static final char SONAME_SEPARATOR = '=';

  private final Path folder;
  private final JcefNatives.Downloader downloader;
  private final List<String> mirrors;
  private final List<String> securityMirrors;
  private final List<Pin> pins;
  private final Map<String, List<String>> hostLibraries;

  LinuxLibraries() {
    final Path folder = defaultFolder();
    final ClassLoader loader = requireNonNull(LinuxLibraries.class.getClassLoader(), "MCAV must not be loaded by the boot loader!");
    final List<String> pins = readResource(loader);
    this(folder, HttpDownloader::download, MIRRORS, SECURITY_MIRRORS, pins);
  }

  @VisibleForTesting
  LinuxLibraries(final Path folder, final JcefNatives.Downloader downloader, final List<String> mirrors, final List<String> pins) {
    this(folder, downloader, mirrors, mirrors, pins);
  }

  @VisibleForTesting
  LinuxLibraries(
    final Path folder,
    final JcefNatives.Downloader downloader,
    final List<String> mirrors,
    final List<String> securityMirrors,
    final List<String> pins
  ) {
    this.folder = folder;
    this.downloader = downloader;
    this.mirrors = List.copyOf(mirrors);
    this.securityMirrors = List.copyOf(securityMirrors);

    final List<Pin> parsed = new ArrayList<>();
    final Map<String, List<String>> hostLibraries = new LinkedHashMap<>();
    for (final String line : pins) {
      final String trimmed = line.trim();
      if (trimmed.isEmpty() || trimmed.startsWith(COMMENT_PREFIX)) {
        continue;
      }
      final List<String> fields = List.of(FIELD_SEPARATOR.split(trimmed));
      final String firstField = fields.getFirst();
      if (firstField.equals(HOST_KEYWORD)) {
        this.putHostLibraries(hostLibraries, fields, line);
      } else {
        parsed.add(Pin.parse(fields, line));
      }
    }

    this.pins = List.copyOf(parsed);
    this.hostLibraries = Map.copyOf(hostLibraries);
  }

  private void putHostLibraries(
    @UnderInitialization LinuxLibraries this,
    final Map<String, List<String>> hostLibraries,
    final List<String> fields,
    final String line
  ) {
    if (fields.size() < HOST_FIRST_SONAME_FIELD) {
      final String message = "The pin %s names no platform!".formatted(line);
      throw new IllegalArgumentException(message);
    }

    final String platform = requireMatch(PLATFORM, fields.get(HOST_PLATFORM_FIELD), line);
    final List<String> sonameFields = fields.subList(HOST_FIRST_SONAME_FIELD, fields.size());
    final List<String> sonames = this.requireSonames(sonameFields, line);
    hostLibraries.put(platform, sonames);
  }

  private static Path defaultFolder() {
    final Path cache = IoUtils.getCachedFolder();
    return cache.resolve(CACHE_FOLDER);
  }

  @VisibleForTesting
  static List<String> readResource(final ClassLoader loader) {
    try (final InputStream stream = loader.getResourceAsStream(RESOURCE)) {
      if (stream == null) {
        final String message = "The resource %s is missing!".formatted(RESOURCE);
        throw new IllegalStateException(message);
      }

      final BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
      return reader.lines().toList();
    } catch (final IOException | UncheckedIOException exception) {
      final String message = "The resource %s cannot be read!".formatted(RESOURCE);
      throw new IllegalStateException(message, exception);
    }
  }

  private static String requireMatch(final Pattern pattern, final String value, final String line) {
    if (!pattern.matcher(value).matches()) {
      final String message = "The value %s of the pin %s is invalid!".formatted(value, line);
      throw new IllegalArgumentException(message);
    }

    return value;
  }

  private List<String> requireSonames(@UnderInitialization LinuxLibraries this, final List<String> sonames, final String line) {
    for (final String soname : sonames) {
      requireMatch(SONAME, soname, line);
    }

    return List.copyOf(sonames);
  }

  List<Pin> getPins(final String platform) {
    return this.pins
      .stream()
      .filter(pin -> pin.platform().equals(platform))
      .toList();
  }

  Path install(final String platform) throws IOException {
    final List<Pin> wanted = this.getPins(platform);
    if (wanted.isEmpty()) {
      final String message = "Platform %s has no libraries in MCAV!".formatted(platform);
      throw new IOException(message);
    }

    final String fingerprint = fingerprint(wanted);
    final String name = INSTALLATION_PREFIX + platform + NAME_SEPARATOR + fingerprint;
    final Path installation = this.folder.resolve(name);
    final Path marker = installation.resolve(INSTALL_MARKER);
    if (Files.isRegularFile(marker)) {
      // an older installation may still carry the permissions of its umask
      ArchiveExtractor.tighten(this.folder);
      return installation;
    }

    ArchiveExtractor.createFolders(this.folder);

    final Path lockFile = this.folder.resolve(name + LOCK_SUFFIX);
    synchronized (INSTALL_LOCK) {
      try (
        final FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        final FileLock lock = JcefNatives.lock(channel)
      ) {
        this.installUnlessMarked(marker, wanted, installation);
        LOGGER.debug("The installation of {} is done, so its lock {} is released next.", name, lock);
      }
    }

    return installation;
  }

  private void installUnlessMarked(final Path marker, final List<Pin> wanted, final Path installation) throws IOException {
    if (!Files.isRegularFile(marker)) {
      this.installLocked(wanted, installation);
    }
  }

  // a changed bundle installs anew beside the old one
  @VisibleForTesting
  static String fingerprint(final List<Pin> pins) {
    final Hasher hasher = Hashing.sha256().newHasher();
    for (final Pin pin : pins) {
      hasher.putString(pin.sha256(), StandardCharsets.US_ASCII);
    }

    final HashCode hash = hasher.hash();
    final String hexadecimal = hash.toString();
    return hexadecimal.substring(0, FINGERPRINT_DIGITS);
  }

  private void installLocked(final List<Pin> wanted, final Path installation) throws IOException {
    final long bytes = wanted.stream().mapToLong(Pin::size).sum();

    LOGGER.info("Downloading {} Linux libraries for the browser ({} MB, once).", wanted.size(), bytes / BYTES_PER_MEGABYTE);

    final String suffix = UUID.randomUUID().toString();
    final String temporaryName = installation.getFileName() + NAME_SEPARATOR + suffix;
    final Path staging = this.folder.resolve(temporaryName + STAGING_SUFFIX);
    final Path download = this.folder.resolve(temporaryName + DOWNLOAD_SUFFIX);
    try {
      ArchiveExtractor.createFolders(staging);
      for (final Pin pin : wanted) {
        this.download(pin, download);
        extract(download, pin.files(), staging);
        Files.delete(download);
      }

      Files.createFile(staging.resolve(INSTALL_MARKER));
      IoUtils.deleteRecursively(installation);
      Files.move(staging, installation, StandardCopyOption.ATOMIC_MOVE);
    } finally {
      Files.deleteIfExists(download);
      IoUtils.deleteRecursively(staging);
    }

    LOGGER.info("Folder {} now holds the Linux libraries of the browser.", installation);
  }

  private void download(final Pin pin, final Path destination) throws IOException {
    Exception failure = null;
    final String pool = pin.pool();
    final List<String> bases = pool.startsWith(SECURITY_POOL) ? this.securityMirrors : this.mirrors;
    for (final String mirror : bases) {
      final String address = "%s%s".formatted(mirror, pool);
      final URI uri = URI.create(address);
      try {
        this.downloader.download(uri, destination, pin.sha256(), pin.size());
        return;
      } catch (final IOException | DownloadException | FileOperationException exception) {
        Files.deleteIfExists(destination);
        failure = this.combineFailures(failure, exception);
      }
    }

    final String message = "The package %s could not be downloaded from any mirror!".formatted(pin.name());
    throw new IOException(message, failure);
  }

  private Exception combineFailures(final @Nullable Exception failure, final Exception exception) {
    if (failure == null) {
      return exception;
    }

    failure.addSuppressed(exception);

    return failure;
  }

  static void extract(final Path debianPackage, final Map<String, String> files, final Path target) throws IOException {
    final Path unpacked = debianPackage.resolveSibling(debianPackage.getFileName() + UNPACKED_SUFFIX);
    try {
      DebianPackageExtractor.extract(debianPackage, unpacked);
      copyLibraries(debianPackage, files, unpacked, target);
    } catch (final InstallationException exception) {
      final @Nullable String message = exception.getMessage();
      throw new IOException(message, exception);
    } finally {
      IoUtils.deleteRecursively(unpacked);
    }
  }

  private static void copyLibraries(final Path debianPackage, final Map<String, String> files, final Path unpacked, final Path target)
    throws IOException {
    final Set<String> missing = new LinkedHashSet<>();
    for (final Map.Entry<String, String> file : files.entrySet()) {
      final String path = file.getKey();
      final Path library = unpacked.resolve(path);
      // a link is no library
      if (!Files.isRegularFile(library, LinkOption.NOFOLLOW_LINKS)) {
        missing.add(path);
        continue;
      }
      final String soname = file.getValue();
      writeLibrary(library, target.resolve(soname));
    }

    if (!missing.isEmpty()) {
      final @Nullable Path packageName = debianPackage.getFileName();
      final String message = "The package %s lacks the files %s that its pin lists!".formatted(packageName, missing);
      throw new IOException(message);
    }
  }

  private static void writeLibrary(final Path library, final Path file) throws IOException {
    try (final OutputStream output = Files.newOutputStream(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
      Files.copy(library, output);
    }

    IoUtils.markExecutable(file);
  }

  void requireHostLibraries(final String platform, final List<Path> hostFolders) {
    final List<String> hostSonames = this.hostLibraries.getOrDefault(platform, List.of());
    final List<String> missingHost = hostSonames
      .stream()
      .filter(soname -> !isPresent(soname, hostFolders, platform))
      .toList();

    if (!missingHost.isEmpty()) {
      final String libraries = String.join(LIST_SEPARATOR, missingHost);
      final String message = "The browser needs %s, which this server lacks and MCAV does not bring!".formatted(libraries);
      throw new BrowserUnavailableException(message);
    }
  }

  // a package is missing whole or not at all, as its first library decides: NSS loads its modules beside its own library
  List<Pin> findMissing(final String platform, final List<Path> hostFolders) {
    return this.getPins(platform)
      .stream()
      .filter(pin -> this.isMissing(pin, hostFolders, platform))
      .toList();
  }

  private boolean isMissing(final Pin pin, final List<Path> hostFolders, final String platform) {
    final List<String> sonames = pin.sonames();
    final String soname = sonames.getFirst();
    return !isPresent(soname, hostFolders, platform);
  }

  static void requireJawt(final Path java) {
    final Path runtime = java.toAbsolutePath().resolveSibling(PARENT_FOLDER).normalize();
    final Path library = runtime.resolve(RUNTIME_LIBRARY_FOLDER).resolve(JAWT);
    if (!Files.exists(library)) {
      final String message =
        "The browser needs the Java runtime's %s, which %s lacks: the headless Java packages of Debian and Ubuntu (openjdk-*-jre-headless) leave it out; install the full package of the same version (openjdk-*-jre), or run the server on a full Java runtime!".formatted(
          JAWT,
          runtime
        );
      throw new BrowserUnavailableException(message);
    }
  }

  static Path link(final Path installation, final Path session, final List<Pin> missing) throws IOException {
    final Path libraries = session.resolve(SESSION_FOLDER);
    Files.createDirectory(libraries);

    for (final Pin pin : missing) {
      final List<String> sonames = pin.sonames();
      for (final String soname : sonames) {
        Files.createSymbolicLink(libraries.resolve(soname), installation.resolve(soname));
      }
      LOGGER.debug("The browser gets {} from {}, as the server lacks them.", sonames, installation);
    }

    return libraries;
  }

  static boolean isPresent(final String soname, final List<Path> hostFolders, final String platform) {
    for (final Path folder : hostFolders) {
      final Path library = folder.resolve(soname);
      if (Files.exists(library) && isBuiltFor(library, platform)) {
        return true;
      }
    }

    return false;
  }

  // the loader skips a library of another architecture, such as a 32-bit one of a multiarch system
  @VisibleForTesting
  static boolean isBuiltFor(final Path library, final String platform) {
    final byte[] header = new byte[ELF_MACHINE_OFFSET + Short.BYTES];
    final int read;
    try (final InputStream input = Files.newInputStream(library)) {
      read = input.readNBytes(header, 0, header.length);
    } catch (final IOException exception) {
      return false;
    }

    if (read < header.length) {
      return false;
    }

    final byte[] start = Arrays.copyOf(header, ELF_64_LITTLE_ENDIAN.length);
    final int lowByte = header[ELF_MACHINE_OFFSET] & UNSIGNED_BYTE_MASK;
    final int highByte = header[ELF_MACHINE_OFFSET + 1] & UNSIGNED_BYTE_MASK;
    final int shiftedHighByte = highByte << Byte.SIZE;
    final int machine = lowByte | shiftedHighByte;
    final int wanted = platform.endsWith(ARM64_SUFFIX) ? MACHINE_AARCH64 : MACHINE_X86_64;
    return Arrays.equals(start, ELF_64_LITTLE_ENDIAN) && machine == wanted;
  }

  static List<Path> hostFolders(final Path root, final String platform) {
    final Set<Path> folders = new LinkedHashSet<>();
    readConfiguration(root, root.resolve(LOADER_CONFIGURATION), folders, 0);

    final String triplet = platform.endsWith(ARM64_SUFFIX) ? AARCH64_TRIPLET : X86_64_TRIPLET;
    for (final String folder : MULTIARCH_FOLDERS) {
      folders.add(root.resolve(folder).resolve(triplet));
    }

    for (final String folder : DEFAULT_FOLDERS) {
      folders.add(root.resolve(folder));
    }

    return List.copyOf(folders);
  }

  private static void readConfiguration(final Path root, final Path file, final Set<Path> folders, final int depth) {
    if (depth > MAXIMUM_INCLUDE_DEPTH) {
      return;
    }

    final List<String> lines;
    try {
      lines = Files.readAllLines(file, StandardCharsets.UTF_8);
    } catch (final IOException exception) {
      // a missing or unreadable file adds no folder, as in glibc
      return;
    }

    for (final String raw : lines) {
      final int commentStart = raw.indexOf(COMMENT_PREFIX);
      final String content = commentStart >= 0 ? raw.substring(0, commentStart) : raw;
      final String line = content.trim();
      if (line.startsWith(INCLUDE_PREFIX)) {
        final String pattern = line.substring(INCLUDE_PREFIX.length()).trim();
        // as in glibc, a relative include lies beside the file that includes it
        final Path base = includeBase(root, file, pattern);
        final List<Path> included = glob(base, pattern);
        readConfigurations(root, included, folders, depth + 1);
      } else if (line.startsWith(FOLDER_SEPARATOR)) {
        folders.add(root.resolve(line.substring(1)));
      }
    }
  }

  private static Path includeBase(final Path root, final Path file, final String pattern) {
    if (pattern.startsWith(FOLDER_SEPARATOR)) {
      return root;
    }

    final Path parent = requireNonNull(file.getParent(), "A file lies in a folder!");
    return parent;
  }

  private static void readConfigurations(final Path root, final List<Path> files, final Set<Path> folders, final int depth) {
    for (final Path file : files) {
      readConfiguration(root, file, folders, depth);
    }
  }

  @VisibleForTesting
  static List<Path> glob(final Path root, final String pattern) {
    final String relative = pattern.startsWith(FOLDER_SEPARATOR) ? pattern.substring(1) : pattern;
    final int slash = relative.lastIndexOf(FOLDER_SEPARATOR);
    final String name = relative.substring(slash + 1);
    if (name.isEmpty()) {
      return List.of();
    }

    final List<Path> matches = new ArrayList<>();
    // only Linux allows wildcards in a path, so only the folder of the pattern is resolved; the empty text is the root
    final Path parent = root.resolve(relative.substring(0, Math.max(slash, 0)));
    final FileSystem fileSystem = root.getFileSystem();
    final PathMatcher matcher = fileSystem.getPathMatcher(GLOB_SYNTAX + name);
    try (final DirectoryStream<Path> entries = Files.newDirectoryStream(parent)) {
      addMatchingEntries(entries, matcher, matches);
    } catch (final IOException exception) {
      // an include of a folder that is not there includes nothing
    }
    Collections.sort(matches);

    return matches;
  }

  private static void addMatchingEntries(final DirectoryStream<Path> entries, final PathMatcher matcher, final List<Path> matches) {
    for (final Path entry : entries) {
      final Path entryName = requireNonNull(entry.getFileName(), "An entry of a folder has a name!");
      if (matcher.matches(entryName)) {
        matches.add(entry);
      }
    }
  }

  @VisibleForTesting
  Path getFolder() {
    return this.folder;
  }

  record Pin(String platform, String name, String version, String sha256, long size, String pool, Map<String, String> files) {
    private static Pin parse(final List<String> fields, final String line) {
      if (fields.size() < MINIMUM_PIN_FIELDS) {
        final String message = "The pin %s has %s fields instead of at least %s fields!".formatted(line, fields.size(), MINIMUM_PIN_FIELDS);
        throw new IllegalArgumentException(message);
      }

      final Map<String, String> files = new LinkedHashMap<>();
      for (final String entry : fields.subList(PIN_FIRST_FILE_FIELD, fields.size())) {
        final int separatorIndex = entry.indexOf(SONAME_SEPARATOR);
        final String sonameText = separatorIndex > 0 ? entry.substring(0, separatorIndex) : entry;
        final String soname = requireMatch(SONAME, sonameText, line);
        final String path = requireMatch(PACKAGE_PATH, entry.substring(separatorIndex + 1), line);
        files.put(path, soname);
      }

      final String platform = requireMatch(PLATFORM, fields.get(PIN_PLATFORM_FIELD), line);
      final String name = fields.get(PIN_NAME_FIELD);
      final String version = fields.get(PIN_VERSION_FIELD);
      final String sha256 = requireMatch(HASH, fields.get(PIN_HASH_FIELD), line);
      final long size = Long.parseLong(fields.get(PIN_SIZE_FIELD));
      final String pool = requireMatch(POOL_PATH, fields.get(PIN_POOL_FIELD), line);
      final Map<String, String> unmodifiableFiles = Collections.unmodifiableMap(files);
      return new Pin(platform, name, version, sha256, size, pool, unmodifiableFiles);
    }

    List<String> sonames() {
      return List.copyOf(new LinkedHashSet<>(this.files.values()));
    }
  }
}
