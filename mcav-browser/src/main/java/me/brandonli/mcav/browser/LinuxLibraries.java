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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import java.io.BufferedInputStream;
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
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import me.brandonli.mcav.utils.IOUtils;
import me.brandonli.mcav.utils.http.HttpDownloader;
import org.apache.commons.compress.archivers.ar.ArArchiveEntry;
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The shared libraries the browser helper needs on Linux that a server may lack.
 *
 * <p>{@code libcef.so} needs the X11 client libraries, GLib, NSS, ATK and more at load time, even though CEF draws
 * off-screen and never talks to an X server; the Docker images Minecraft servers run in have few of them, and the
 * operator of a shared host can install nothing. So mcav downloads them once from the main archive of Debian 11, which
 * is frozen, checks every package against its pinned SHA-256 hash, and keeps only the pinned libraries, each as a file
 * named by its soname. For every session, the libraries the server lacks are linked into a folder of the session,
 * which is the helper's {@code LD_LIBRARY_PATH}: the libraries the server has are always its own, so no newer library
 * of the server is ever paired with an older one of the bundle. The pins are in {@value #RESOURCE}.
 */
final class LinuxLibraries {

  /**
   * The resource that pins the packages.
   */
  static final String RESOURCE = "linux-libraries.txt";

  /**
   * The marker file of a complete installation.
   */
  static final String INSTALL_MARKER = "install.lock";

  /**
   * The folder of a session the libraries are linked into.
   */
  static final String SESSION_FOLDER = "lib";

  /**
   * The mirrors of the Debian archive the packages are tried from, in order; the hash decides whether a package is
   * the pinned one, wherever it came from.
   */
  static final List<String> MIRRORS = List.of("https://archive.debian.org/debian/", "https://deb.debian.org/debian/");

  private static final Logger LOGGER = LoggerFactory.getLogger(LinuxLibraries.class);

  // every copy of mcav in this JVM shares this monitor, like the one of the CEF natives
  @VisibleForTesting
  static final Object INSTALL_LOCK = "the installation of the Linux libraries of mcav";

  private static final String DATA_MEMBER = "data.tar.xz";
  private static final Pattern PLATFORM = Pattern.compile("[a-z0-9]+-[a-z0-9]+");
  private static final Pattern SONAME = Pattern.compile("lib[A-Za-z0-9_+.-]+\\.so(\\.[0-9]+)*");
  private static final Pattern PACKAGE_PATH = Pattern.compile("(usr/)?lib/[a-z0-9_-]+/(nss/)?[A-Za-z0-9_+.-]+");
  private static final Pattern POOL_PATH = Pattern.compile("pool/main/[a-z0-9+.-]+/[a-z0-9+.-]+/[A-Za-z0-9_+.:~-]+\\.deb");
  private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
  private static final long MAX_FILE_BYTES = 64L * 1024 * 1024;
  private static final int MAX_ENTRIES = 20_000;
  private static final int MAX_INCLUDE_DEPTH = 8;

  private final Path folder;
  private final JcefNatives.Downloader downloader;
  private final List<String> mirrors;
  private final List<Pin> pins;
  private final Map<String, List<String>> hostLibraries;

  /**
   * Constructs an installer that uses mcav's cache folder, the Debian mirrors and the pinned packages.
   */
  LinuxLibraries() {
    this(
      defaultFolder(),
      HttpDownloader::download,
      MIRRORS,
      readResource(Objects.requireNonNull(LinuxLibraries.class.getClassLoader(), "mcav is not loaded by the boot loader"))
    );
  }

  /**
   * Constructs an installer with another folder, download, mirrors and pins, so tests can install from a local copy.
   *
   * @param folder     the folder the libraries are installed in
   * @param downloader downloads a file and verifies its hash
   * @param mirrors    the base addresses of the archive, each ending with a slash
   * @param pins       the lines of the pins
   */
  @VisibleForTesting
  LinuxLibraries(final Path folder, final JcefNatives.Downloader downloader, final List<String> mirrors, final List<String> pins) {
    this.folder = folder;
    this.downloader = downloader;
    this.mirrors = List.copyOf(mirrors);
    final List<Pin> parsed = new ArrayList<>();
    final Map<String, List<String>> host = new LinkedHashMap<>();
    for (final String line : pins) {
      final String trimmed = line.trim();
      if (trimmed.isEmpty() || trimmed.startsWith("#")) {
        continue;
      }
      final List<String> fields = List.of(trimmed.split(" +"));
      if (fields.getFirst().equals("host")) {
        if (fields.size() < 2) {
          throw new IllegalArgumentException("The pin " + line + " names no platform");
        }
        host.put(requireMatch(PLATFORM, fields.get(1), line), requireSonames(fields.subList(2, fields.size()), line));
      } else {
        parsed.add(Pin.parse(fields, line));
      }
    }
    this.pins = List.copyOf(parsed);
    this.hostLibraries = Map.copyOf(host);
  }

  private static Path defaultFolder() {
    final Path cache = IOUtils.getCachedFolder();
    return cache.resolve("jcef-libraries");
  }

  /**
   * Reads the pins of the resource next to this class.
   *
   * @param loader the class loader of this class
   * @return the lines
   * @throws IllegalStateException if the resource is missing or cannot be read, which only a broken build does
   */
  @VisibleForTesting
  static List<String> readResource(final ClassLoader loader) {
    final String name = "me/brandonli/mcav/browser/" + RESOURCE;
    try (final InputStream stream = loader.getResourceAsStream(name)) {
      if (stream == null) {
        throw new IllegalStateException("The resource " + name + " is missing");
      }
      final BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
      return reader.lines().toList();
    } catch (final IOException | UncheckedIOException exception) {
      throw new IllegalStateException("The resource " + name + " cannot be read", exception);
    }
  }

  private static String requireMatch(final Pattern pattern, final String value, final String line) {
    if (!pattern.matcher(value).matches()) {
      throw new IllegalArgumentException("The pin " + line + " has the invalid value " + value);
    }
    return value;
  }

  private static List<String> requireSonames(final List<String> sonames, final String line) {
    for (final String soname : sonames) {
      requireMatch(SONAME, soname, line);
    }
    return List.copyOf(sonames);
  }

  /**
   * Gets the pins of a platform.
   *
   * @param platform the jcefmaven identifier of the platform, such as {@code linux-amd64}
   * @return the pins, in the order of the resource
   */
  List<Pin> getPins(final String platform) {
    final List<Pin> matching = new ArrayList<>();
    for (final Pin pin : this.pins) {
      if (pin.platform().equals(platform)) {
        matching.add(pin);
      }
    }
    return matching;
  }

  /**
   * Installs the libraries of a platform unless they are installed already.
   *
   * @param platform the jcefmaven identifier of the platform, such as {@code linux-amd64}
   * @return the folder of the installation, which holds every library as a file named by its soname
   * @throws IOException if mcav has no libraries for the platform, or a download, a verification or an extraction fails
   */
  Path install(final String platform) throws IOException {
    final List<Pin> wanted = this.getPins(platform);
    if (wanted.isEmpty()) {
      throw new IOException("mcav has no libraries for " + platform);
    }
    final String name = "debian-11-" + platform + "-" + fingerprint(wanted);
    final Path installation = this.folder.resolve(name);
    final Path marker = installation.resolve(INSTALL_MARKER);
    if (Files.isRegularFile(marker)) {
      return installation;
    }
    Files.createDirectories(this.folder);
    final Path lockFile = this.folder.resolve(name + ".lock");
    synchronized (INSTALL_LOCK) {
      try (
        final FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        final FileLock lock = JcefNatives.lock(channel)
      ) {
        // another process may have installed the libraries while this one waited for the lock
        if (!Files.isRegularFile(marker)) {
          this.installLocked(wanted, installation);
        }
        LOGGER.debug("Released the installation lock {} of {}", lock, name);
      }
    }
    return installation;
  }

  /**
   * Names an installation after its pins, so a changed bundle is installed anew beside the old one.
   *
   * @param pins the pins
   * @return the first twelve hexadecimal digits of the SHA-256 hash of the pinned hashes
   */
  @VisibleForTesting
  static String fingerprint(final List<Pin> pins) {
    final Hasher hasher = Hashing.sha256().newHasher();
    for (final Pin pin : pins) {
      hasher.putString(pin.sha256(), StandardCharsets.US_ASCII);
    }
    return hasher.hash().toString().substring(0, 12);
  }

  private void installLocked(final List<Pin> wanted, final Path installation) throws IOException {
    long bytes = 0L;
    for (final Pin pin : wanted) {
      bytes += pin.size();
    }
    LOGGER.info("Downloading {} Linux libraries for the browser ({} MB, once)", wanted.size(), bytes / 1_000_000L);
    final String suffix = UUID.randomUUID().toString();
    final Path staging = this.folder.resolve(installation.getFileName() + "-" + suffix + ".staging");
    final Path download = this.folder.resolve(installation.getFileName() + "-" + suffix + ".deb");
    try {
      Files.createDirectories(staging);
      for (final Pin pin : wanted) {
        this.download(pin, download);
        extract(download, pin.files(), staging);
        Files.delete(download);
      }
      Files.createFile(staging.resolve(INSTALL_MARKER));
      IOUtils.deleteRecursively(installation);
      Files.move(staging, installation, StandardCopyOption.ATOMIC_MOVE);
    } finally {
      Files.deleteIfExists(download);
      IOUtils.deleteRecursively(staging);
    }
    LOGGER.info("Installed the Linux libraries of the browser in {}", installation);
  }

  private void download(final Pin pin, final Path destination) throws IOException {
    IOException failure = null;
    for (final String mirror : this.mirrors) {
      final URI uri = URI.create(mirror + pin.pool());
      try {
        this.downloader.download(uri, destination, pin.sha256());
        return;
      } catch (final IOException exception) {
        Files.deleteIfExists(destination);
        if (failure == null) {
          failure = exception;
        } else {
          failure.addSuppressed(exception);
        }
      }
    }
    final String message = "The package " + pin.name() + " could not be downloaded from any mirror";
    throw new IOException(message, failure);
  }

  /**
   * Extracts the pinned libraries of a Debian package: the regular files of its {@value #DATA_MEMBER} at the pinned
   * paths, each written as a file named by its soname. Links, other files and other members are skipped.
   *
   * @param deb    the package
   * @param files  the paths in the package, mapped to the sonames to write them as
   * @param target the folder to write into
   * @throws IOException if the package is not one, or a pinned library is missing or too large
   */
  static void extract(final Path deb, final Map<String, String> files, final Path target) throws IOException {
    extract(deb, files, target, MAX_FILE_BYTES, MAX_ENTRIES);
  }

  /**
   * Extracts the pinned libraries of a Debian package with other limits, so tests can reach them.
   *
   * @param deb          the package
   * @param files        the paths in the package, mapped to the sonames to write them as
   * @param target       the folder to write into
   * @param maxFileBytes the largest library
   * @param maxEntries   the most entries the data of the package may have
   * @throws IOException if the package is not one, or a pinned library is missing or too large
   */
  @VisibleForTesting
  static void extract(final Path deb, final Map<String, String> files, final Path target, final long maxFileBytes, final int maxEntries)
    throws IOException {
    final boolean extracted;
    try (
      final InputStream raw = Files.newInputStream(deb);
      final ArArchiveInputStream ar = new ArArchiveInputStream(new BufferedInputStream(raw))
    ) {
      extracted = extractMembers(ar, files, target, deb, maxFileBytes, maxEntries);
    }
    if (!extracted) {
      throw new IOException("The package " + deb.getFileName() + " holds no " + DATA_MEMBER);
    }
  }

  private static boolean extractMembers(
    final ArArchiveInputStream ar,
    final Map<String, String> files,
    final Path target,
    final Path deb,
    final long maxFileBytes,
    final int maxEntries
  ) throws IOException {
    ArArchiveEntry member = ar.getNextEntry();
    while (member != null) {
      if (member.getName().equals(DATA_MEMBER)) {
        // the streams are not closed here: the package closes them all
        final XZCompressorInputStream xz = new XZCompressorInputStream(ar);
        final TarArchiveInputStream tar = new TarArchiveInputStream(xz);
        extractData(tar, files, target, deb, maxFileBytes, maxEntries);
        return true;
      }
      member = ar.getNextEntry();
    }
    return false;
  }

  private static void extractData(
    final TarArchiveInputStream tar,
    final Map<String, String> files,
    final Path target,
    final Path deb,
    final long maxFileBytes,
    final int maxEntries
  ) throws IOException {
    final Set<String> found = new HashSet<>();
    int entries = 0;
    TarArchiveEntry entry = tar.getNextEntry();
    while (entry != null) {
      entries++;
      if (entries > maxEntries) {
        throw new IOException("The package " + deb.getFileName() + " has more than " + maxEntries + " entries");
      }
      final String path = entry.getName().startsWith("./") ? entry.getName().substring(2) : entry.getName();
      final String soname = files.get(path);
      // isFile holds for a link as well, and a link is no library
      final boolean regular = entry.isFile() && !entry.isSymbolicLink() && !entry.isLink();
      if (soname != null && regular) {
        if (entry.getSize() > maxFileBytes) {
          throw new IOException("The library " + path + " is larger than " + maxFileBytes + " bytes");
        }
        writeLibrary(tar, target.resolve(soname));
        found.add(path);
      }
      entry = tar.getNextEntry();
    }
    final Set<String> missing = new LinkedHashSet<>(files.keySet());
    missing.removeAll(found);
    if (!missing.isEmpty()) {
      throw new IOException("The package " + deb.getFileName() + " holds no " + missing);
    }
  }

  private static void writeLibrary(final InputStream content, final Path file) throws IOException {
    try (final OutputStream out = Files.newOutputStream(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
      content.transferTo(out);
    }
    final FileSystem fileSystem = file.getFileSystem();
    if (fileSystem.supportedFileAttributeViews().contains("posix")) {
      // readable and loadable by everyone, writable by the owner only, as the libraries of a system are
      Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
    }
  }

  /**
   * Finds the packages whose libraries a server lacks. A package is missing whole, or not at all, as its first library
   * decides: NSS, for one, loads its modules from the folder of its own library.
   *
   * @param platform    the jcefmaven identifier of the platform
   * @param hostFolders the folders the dynamic loader of the server searches
   * @return the missing packages, empty if the server has every library
   * @throws BrowserUnavailableException if the server lacks a library that mcav expects every server to have
   */
  List<Pin> findMissing(final String platform, final List<Path> hostFolders) {
    final List<String> missingHost = new ArrayList<>();
    for (final String soname : this.hostLibraries.getOrDefault(platform, List.of())) {
      if (!isPresent(soname, hostFolders)) {
        missingHost.add(soname);
      }
    }
    if (!missingHost.isEmpty()) {
      throw new BrowserUnavailableException(
        "The browser needs " + String.join(", ", missingHost) + ", which this server lacks and mcav does not bring"
      );
    }
    final List<Pin> missing = new ArrayList<>();
    for (final Pin pin : this.getPins(platform)) {
      if (!isPresent(pin.sonames().getFirst(), hostFolders)) {
        missing.add(pin);
      }
    }
    return missing;
  }

  /**
   * Links the libraries of packages a server lacks into a folder of a session.
   *
   * @param installation the folder of the installation, which holds them
   * @param session      the folder of the session
   * @param missing      the packages the server lacks, see {@link #findMissing(String, List)}
   * @return the folder of the links
   * @throws IOException if a link cannot be created
   */
  static Path link(final Path installation, final Path session, final List<Pin> missing) throws IOException {
    final Path libraries = session.resolve(SESSION_FOLDER);
    Files.createDirectory(libraries);
    for (final Pin pin : missing) {
      final List<String> sonames = pin.sonames();
      for (final String soname : sonames) {
        Files.createSymbolicLink(libraries.resolve(soname), installation.resolve(soname));
      }
      LOGGER.debug("The server lacks {}, which the browser gets from {}", sonames, installation);
    }
    return libraries;
  }

  /**
   * Checks whether the dynamic loader of a server finds a library.
   *
   * @param soname      the soname
   * @param hostFolders the folders the loader searches
   * @return true if a folder holds it
   */
  static boolean isPresent(final String soname, final List<Path> hostFolders) {
    for (final Path folder : hostFolders) {
      if (Files.exists(folder.resolve(soname))) {
        return true;
      }
    }
    return false;
  }

  /**
   * Lists the folders the dynamic loader of a Linux system searches: those of {@code /etc/ld.so.conf} and the files it
   * includes, and the default folders, the multiarch ones of the platform included.
   *
   * @param root     the root of the file system
   * @param platform the jcefmaven identifier of the platform
   * @return the folders, in the order the loader tries them
   */
  static List<Path> hostFolders(final Path root, final String platform) {
    final Set<Path> folders = new LinkedHashSet<>();
    readConfiguration(root, root.resolve("etc/ld.so.conf"), folders, 0);
    final String triplet = platform.endsWith("arm64") ? "aarch64-linux-gnu" : "x86_64-linux-gnu";
    for (final String folder : List.of("lib/" + triplet, "usr/lib/" + triplet, "lib64", "usr/lib64", "lib", "usr/lib")) {
      folders.add(root.resolve(folder));
    }
    return List.copyOf(folders);
  }

  private static void readConfiguration(final Path root, final Path file, final Set<Path> folders, final int depth) {
    if (depth > MAX_INCLUDE_DEPTH) {
      return;
    }
    final List<String> lines;
    try {
      lines = Files.readAllLines(file, StandardCharsets.UTF_8);
    } catch (final IOException exception) {
      // a system without the file, or with one that cannot be read, has the default folders only
      return;
    }
    for (final String raw : lines) {
      final int comment = raw.indexOf('#');
      final String line = (comment >= 0 ? raw.substring(0, comment) : raw).trim();
      if (line.startsWith("include ")) {
        final String pattern = line.substring("include ".length()).trim();
        for (final Path included : glob(root, pattern)) {
          readConfiguration(root, included, folders, depth + 1);
        }
      } else if (line.startsWith("/")) {
        folders.add(root.resolve(line.substring(1)));
      }
    }
  }

  /**
   * Lists the files an {@code include} line of the loader's configuration names.
   *
   * @param root    the root of the file system
   * @param pattern the pattern, whose last part may hold wildcards
   * @return the files, sorted by name
   */
  @VisibleForTesting
  static List<Path> glob(final Path root, final String pattern) {
    final String relative = pattern.startsWith("/") ? pattern.substring(1) : pattern;
    final int slash = relative.lastIndexOf('/');
    final String name = relative.substring(slash + 1);
    final List<Path> matches = new ArrayList<>();
    if (name.isEmpty()) {
      // a folder, or the root itself, which holds no library
      return matches;
    }
    // the pattern is text, whose wildcards no file system but Linux's allows in a path, so only its folder is resolved
    final Path parent = slash < 0 ? root : root.resolve(relative.substring(0, slash));
    final FileSystem fileSystem = root.getFileSystem();
    final PathMatcher matcher = fileSystem.getPathMatcher("glob:" + name);
    try (final DirectoryStream<Path> entries = Files.newDirectoryStream(parent)) {
      for (final Path entry : entries) {
        final Path entryName = Objects.requireNonNull(entry.getFileName(), "an entry of a folder has a name");
        if (matcher.matches(entryName)) {
          matches.add(entry);
        }
      }
    } catch (final IOException exception) {
      // an include of a folder that is not there includes nothing
    }
    Collections.sort(matches);
    return matches;
  }

  /**
   * One pinned package.
   *
   * @param platform the jcefmaven identifier of the platform
   * @param name     the name of the package
   * @param version  its version
   * @param sha256   the SHA-256 hash of the package
   * @param size     its size in bytes
   * @param pool     its path in the archive
   * @param files    the paths of its libraries, mapped to their sonames, the one that decides first
   */
  record Pin(String platform, String name, String version, String sha256, long size, String pool, Map<String, String> files) {
    /**
     * Reads the fields of a line of the resource.
     *
     * @param fields the fields
     * @param line   the line, for the message of a failure
     * @return the pin
     * @throws IllegalArgumentException if a field is invalid
     */
    static Pin parse(final List<String> fields, final String line) {
      if (fields.size() < 7) {
        throw new IllegalArgumentException("The pin " + line + " has " + fields.size() + " fields instead of at least 7");
      }
      final Map<String, String> files = new LinkedHashMap<>();
      for (final String entry : fields.subList(6, fields.size())) {
        final int equals = entry.indexOf('=');
        final String soname = requireMatch(SONAME, equals > 0 ? entry.substring(0, equals) : entry, line);
        final String path = requireMatch(PACKAGE_PATH, entry.substring(equals + 1), line);
        files.put(path, soname);
      }
      return new Pin(
        requireMatch(PLATFORM, fields.get(0), line),
        fields.get(1),
        fields.get(2),
        requireMatch(HASH, fields.get(3), line),
        Long.parseLong(fields.get(4)),
        requireMatch(POOL_PATH, fields.get(5), line),
        Collections.unmodifiableMap(files)
      );
    }

    /**
     * Gets the sonames of the package, the one that decides first.
     *
     * @return the sonames
     */
    List<String> sonames() {
      return List.copyOf(new LinkedHashSet<>(this.files.values()));
    }
  }
}
