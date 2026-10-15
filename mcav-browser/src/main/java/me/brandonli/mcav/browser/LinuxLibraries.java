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
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

// The shared libraries the browser helper needs on Linux that a server may lack. libcef.so needs the X11 client
// libraries, GLib, NSS, ATK and more at load time, even though CEF draws off-screen and never talks to an X server; the
// Docker images Minecraft servers run in have few of them, and the operator of a shared host can install nothing. So
// MCAV downloads them once from the main archive of Debian 11, which is frozen, checks every package against its pinned
// SHA-256 hash, and keeps only the pinned libraries, each as a file named by its soname. For every session, the
// libraries the server lacks are linked into a folder of the session, which is the helper's LD_LIBRARY_PATH: the
// libraries the server has are always its own, so no newer library of the server is ever paired with an older one of
// the bundle. The pins are in RESOURCE. libjcef.so also links against JAWT of the Java runtime, which no package can
// bring, as it belongs to the build of the runtime; a runtime without it, as the headless Java packages of Debian and
// Ubuntu are, is refused with what to install.
final class LinuxLibraries {

  private static final String RESOURCE = "mcav/browser/linux-libraries.txt";

  static final String INSTALL_MARKER = "install.lock";

  static final String SESSION_FOLDER = "lib";

  // The library of the Java runtime that libjcef.so links against: the native interface of AWT.
  static final String JAWT = "libjawt.so";

  // The mirrors of the Debian archive the packages are tried from, in order; the hash decides whether a package is the
  // pinned one, wherever it came from.
  static final List<String> MIRRORS = List.of("https://archive.debian.org/debian/", "https://deb.debian.org/debian/");

  // The mirrors of Debian 11's security updates, for the pins in pool/updates/: the archive, once they reach it, and
  // Debian's snapshot archive, which keeps every file under a permanent address.
  static final List<String> SECURITY_MIRRORS = List.of(
    "https://archive.debian.org/debian-security/",
    "https://snapshot.debian.org/archive/debian-security/20260901T000000Z/"
  );

  private static final Logger LOGGER = LoggerFactory.getLogger(LinuxLibraries.class);

  // Every copy of MCAV in this JVM shares this monitor.
  @VisibleForTesting
  static final Object INSTALL_LOCK = "the installation of the Linux libraries of mcav";

  private static final Pattern PLATFORM = Pattern.compile("[a-z0-9]+-[a-z0-9]+");
  private static final Pattern SONAME = Pattern.compile("lib[A-Za-z0-9_+.-]+\\.so(\\.[0-9]+)*");
  private static final Pattern PACKAGE_PATH = Pattern.compile("(usr/)?lib/[a-z0-9_-]+/(nss/)?[A-Za-z0-9_+.-]+");
  private static final Pattern POOL_PATH = Pattern.compile("pool/(updates/)?main/[a-z0-9+.-]+/[a-z0-9+.-]+/[A-Za-z0-9_+.:~-]+\\.deb");
  private static final String SECURITY_POOL = "pool/updates/";
  private static final byte[] ELF_64_LITTLE_ENDIAN = { 0x7F, 'E', 'L', 'F', 2, 1 };
  private static final int ELF_MACHINE_OFFSET = 18;
  private static final int MACHINE_X86_64 = 62;
  private static final int MACHINE_AARCH64 = 183;
  private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
  private static final String UNPACKED_SUFFIX = ".unpacked";
  private static final int MAXIMUM_INCLUDE_DEPTH = 8;

  private static final String CACHE_FOLDER = "jcef-libraries";
  private static final String INSTALLATION_PREFIX = "debian-11-";
  private static final String NAME_SEPARATOR = "-";
  private static final String LOCK_SUFFIX = ".lock";
  private static final String STAGING_SUFFIX = ".staging";
  private static final String DOWNLOAD_SUFFIX = ".deb";
  private static final int FINGERPRINT_DIGITS = 12;

  private static final long BYTES_PER_MEGABYTE = 1_000_000L;

  private static final String POSIX_ATTRIBUTE_VIEW = "posix";
  private static final String EXECUTABLE_PERMISSIONS = "rwxr-xr-x";

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
  private static final String FOLDER_SEPARATOR = "/";
  private static final String GLOB_SYNTAX = "glob:";

  private static final Pattern FIELD_SEPARATOR = Pattern.compile(" +");
  private static final String HOST_KEYWORD = "host";
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

  // Constructs an installer that uses MCAV's cache folder, the Debian mirrors and the pinned packages.
  LinuxLibraries() {
    this(
      defaultFolder(),
      HttpDownloader::download,
      MIRRORS,
      SECURITY_MIRRORS,
      readResource(Objects.requireNonNull(LinuxLibraries.class.getClassLoader(), "mcav is not loaded by the boot loader!"))
    );
  }

  // Constructs an installer with another folder, download, mirrors and pins, so tests can install from a local copy.
  @VisibleForTesting
  LinuxLibraries(final Path folder, final JcefNatives.Downloader downloader, final List<String> mirrors, final List<String> pins) {
    this(folder, downloader, mirrors, mirrors, pins);
  }

  // Constructs an installer with another folder, download, mirrors of the archive and of its security updates, and
  // pins.
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
    final Map<String, List<String>> host = new LinkedHashMap<>();
    for (final String line : pins) {
      final String trimmed = line.trim();
      if (trimmed.isEmpty() || trimmed.startsWith(COMMENT_PREFIX)) {
        continue;
      }
      final List<String> fields = List.of(FIELD_SEPARATOR.split(trimmed));
      if (fields.getFirst().equals(HOST_KEYWORD)) {
        this.putHostLibraries(host, fields, line);
      } else {
        parsed.add(Pin.parse(fields, line));
      }
    }
    this.pins = List.copyOf(parsed);
    this.hostLibraries = Map.copyOf(host);
  }

  private void putHostLibraries(
    @UnderInitialization LinuxLibraries this,
    final Map<String, List<String>> host,
    final List<String> fields,
    final String line
  ) {
    if (fields.size() < HOST_FIRST_SONAME_FIELD) {
      final String message = "The pin %s names no platform!".formatted(line);
      throw new IllegalArgumentException(message);
    }

    host.put(requireMatch(PLATFORM, fields.get(1), line), requireSonames(fields.subList(HOST_FIRST_SONAME_FIELD, fields.size()), line));
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
      final String message = "The pin %s has the invalid value %s!".formatted(line, value);
      throw new IllegalArgumentException(message);
    }

    return value;
  }

  private static List<String> requireSonames(final List<String> sonames, final String line) {
    for (final String soname : sonames) {
      requireMatch(SONAME, soname, line);
    }
    return List.copyOf(sonames);
  }

  List<Pin> getPins(final String platform) {
    final List<Pin> matching = new ArrayList<>();
    for (final Pin pin : this.pins) {
      if (pin.platform().equals(platform)) {
        matching.add(pin);
      }
    }
    return matching;
  }

  // Installs the libraries of a platform unless they are installed already.
  Path install(final String platform) throws IOException {
    final List<Pin> wanted = this.getPins(platform);
    if (wanted.isEmpty()) {
      final String message = "Platform %s has no libraries in mcav!".formatted(platform);
      throw new IOException(message);
    }

    final String name = INSTALLATION_PREFIX + platform + NAME_SEPARATOR + fingerprint(wanted);
    final Path installation = this.folder.resolve(name);
    final Path marker = installation.resolve(INSTALL_MARKER);
    if (Files.isRegularFile(marker)) {
      // An existing installation may still have the permissions of the umask it was made with.
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
        LOGGER.debug("Installation lock {} of {} was released.", lock, name);
      }
    }
    return installation;
  }

  private void installUnlessMarked(final Path marker, final List<Pin> wanted, final Path installation) throws IOException {
    if (!Files.isRegularFile(marker)) {
      this.installLocked(wanted, installation);
    }
  }

  // Names an installation after its pins, so a changed bundle is installed anew beside the old one.
  @VisibleForTesting
  static String fingerprint(final List<Pin> pins) {
    final Hasher hasher = Hashing.sha256().newHasher();
    for (final Pin pin : pins) {
      hasher.putString(pin.sha256(), StandardCharsets.US_ASCII);
    }
    return hasher.hash().toString().substring(0, FINGERPRINT_DIGITS);
  }

  private void installLocked(final List<Pin> wanted, final Path installation) throws IOException {
    long bytes = 0L;
    for (final Pin pin : wanted) {
      bytes += pin.size();
    }
    LOGGER.info("Downloading {} Linux libraries for the browser ({} MB, once).", wanted.size(), bytes / BYTES_PER_MEGABYTE);
    final String suffix = UUID.randomUUID().toString();
    final Path staging = this.folder.resolve(installation.getFileName() + NAME_SEPARATOR + suffix + STAGING_SUFFIX);
    final Path download = this.folder.resolve(installation.getFileName() + NAME_SEPARATOR + suffix + DOWNLOAD_SUFFIX);
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
    final List<String> bases = pin.pool().startsWith(SECURITY_POOL) ? this.securityMirrors : this.mirrors;
    for (final String mirror : bases) {
      final URI uri = URI.create(mirror + pin.pool());
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

  // Extracts the pinned libraries of a Debian package: the regular files at the pinned paths of its library trees, each
  // written as a file named by its soname. The package is unpacked by DebianPackageExtractor into a folder beside it,
  // which is removed again; links and other files are not copied.
  static void extract(final Path debianPackage, final Map<String, String> files, final Path target) throws IOException {
    final Path unpacked = debianPackage.resolveSibling(debianPackage.getFileName() + UNPACKED_SUFFIX);
    try {
      DebianPackageExtractor.extract(debianPackage, unpacked);
      copyLibraries(debianPackage, files, unpacked, target);
    } catch (final InstallationException exception) {
      throw new IOException(exception.getMessage(), exception);
    } finally {
      IoUtils.deleteRecursively(unpacked);
    }
  }

  private static void copyLibraries(final Path debianPackage, final Map<String, String> files, final Path unpacked, final Path target)
    throws IOException {
    final Set<String> missing = new LinkedHashSet<>();
    for (final Map.Entry<String, String> file : files.entrySet()) {
      final Path library = unpacked.resolve(file.getKey());
      // a link is no library
      if (!Files.isRegularFile(library, LinkOption.NOFOLLOW_LINKS)) {
        missing.add(file.getKey());
        continue;
      }
      writeLibrary(library, target.resolve(file.getValue()));
    }
    if (!missing.isEmpty()) {
      final String message = "The package %s holds none of %s!".formatted(debianPackage.getFileName(), missing);
      throw new IOException(message);
    }
  }

  private static void writeLibrary(final Path library, final Path file) throws IOException {
    try (final OutputStream out = Files.newOutputStream(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
      Files.copy(library, out);
    }
    final FileSystem fileSystem = file.getFileSystem();
    if (fileSystem.supportedFileAttributeViews().contains(POSIX_ATTRIBUTE_VIEW)) {
      Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(EXECUTABLE_PERMISSIONS));
    }
  }

  // Finds the packages whose libraries a server lacks. A package is missing whole, or not at all, as its first library
  // decides: NSS, for one, loads its modules from the folder of its own library.
  List<Pin> findMissing(final String platform, final List<Path> hostFolders) {
    final List<String> missingHost = new ArrayList<>();
    for (final String soname : this.hostLibraries.getOrDefault(platform, List.of())) {
      if (!isPresent(soname, hostFolders, platform)) {
        missingHost.add(soname);
      }
    }
    if (!missingHost.isEmpty()) {
      final String libraries = String.join(LIST_SEPARATOR, missingHost);
      final String message = "The browser needs %s, which this server lacks and mcav does not bring!".formatted(libraries);
      throw new BrowserUnavailableException(message);
    }

    final List<Pin> missing = new ArrayList<>();
    for (final Pin pin : this.getPins(platform)) {
      if (!isPresent(pin.sonames().getFirst(), hostFolders, platform)) {
        missing.add(pin);
      }
    }
    return missing;
  }

  static void requireJawt(final Path java) {
    final Path runtime = java.toAbsolutePath().resolveSibling(PARENT_FOLDER).normalize();
    if (!Files.exists(runtime.resolve(RUNTIME_LIBRARY_FOLDER).resolve(JAWT))) {
      final String message =
        "The browser needs the Java runtime's %s, which %s lacks: the headless Java packages of Debian and Ubuntu (openjdk-*-jre-headless) leave it out; install the full package of the same version (openjdk-*-jre), or run the server on a full Java runtime!".formatted(
          JAWT,
          runtime
        );
      throw new BrowserUnavailableException(message);
    }
  }

  // Links the libraries of packages a server lacks into a folder of a session.
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

  // Checks that a shared library is built for a platform: a 64-bit little-endian ELF file of its machine. A library of
  // another architecture, such as a 32-bit one of a multiarch system, is one the loader skips.
  @VisibleForTesting
  static boolean isBuiltFor(final Path library, final String platform) {
    final byte[] header = new byte[ELF_MACHINE_OFFSET + Short.BYTES];
    final int read;
    try (final InputStream in = Files.newInputStream(library)) {
      read = in.readNBytes(header, 0, header.length);
    } catch (final IOException exception) {
      return false;
    }
    if (read < header.length) {
      return false;
    }

    final byte[] start = Arrays.copyOf(header, ELF_64_LITTLE_ENDIAN.length);
    final int machine =
      (header[ELF_MACHINE_OFFSET] & UNSIGNED_BYTE_MASK) | ((header[ELF_MACHINE_OFFSET + 1] & UNSIGNED_BYTE_MASK) << Byte.SIZE);
    final int wanted = platform.endsWith(ARM64_SUFFIX) ? MACHINE_AARCH64 : MACHINE_X86_64;
    return Arrays.equals(start, ELF_64_LITTLE_ENDIAN) && machine == wanted;
  }

  // Lists the folders the dynamic loader of a Linux system searches: those of /etc/ld.so.conf and the files it
  // includes, and the default folders, the multiarch ones of the platform included.
  static List<Path> hostFolders(final Path root, final String platform) {
    final Set<Path> folders = new LinkedHashSet<>();
    readConfiguration(root, root.resolve(LOADER_CONFIGURATION), folders, 0);
    final String triplet = platform.endsWith(ARM64_SUFFIX) ? AARCH64_TRIPLET : X86_64_TRIPLET;
    for (final String folder : MULTIARCH_FOLDERS) {
      folders.add(root.resolve(folder + triplet));
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
      return;
    }
    for (final String raw : lines) {
      final int comment = raw.indexOf(COMMENT_PREFIX);
      final String line = (comment >= 0 ? raw.substring(0, comment) : raw).trim();
      if (line.startsWith(INCLUDE_PREFIX)) {
        final String pattern = line.substring(INCLUDE_PREFIX.length()).trim();
        // as glibc does, a relative include lies beside the file that includes it
        final Path base = pattern.startsWith(FOLDER_SEPARATOR)
          ? root
          : Objects.requireNonNull(file.getParent(), "A file lies in a folder!");
        final List<Path> included = glob(base, pattern);
        readConfigurations(root, included, folders, depth + 1);
      } else if (line.startsWith(FOLDER_SEPARATOR)) {
        folders.add(root.resolve(line.substring(1)));
      }
    }
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
    final List<Path> matches = new ArrayList<>();
    if (!name.isEmpty()) {
      // the pattern is text, whose wildcards no file system but Linux's allows in a path, so only its folder is
      // resolved; the folder of a name without one is the root, which the empty text resolves to
      final Path parent = root.resolve(relative.substring(0, Math.max(slash, 0)));
      final FileSystem fileSystem = root.getFileSystem();
      final PathMatcher matcher = fileSystem.getPathMatcher(GLOB_SYNTAX + name);
      try (final DirectoryStream<Path> entries = Files.newDirectoryStream(parent)) {
        addMatchingEntries(entries, matcher, matches);
      } catch (final IOException exception) {
        // an include of a folder that is not there includes nothing
      }
      Collections.sort(matches);
    }
    return matches;
  }

  private static void addMatchingEntries(final DirectoryStream<Path> entries, final PathMatcher matcher, final List<Path> matches) {
    for (final Path entry : entries) {
      final Path entryName = Objects.requireNonNull(entry.getFileName(), "An entry of a folder has a name!");
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
        final int equals = entry.indexOf(SONAME_SEPARATOR);
        final String soname = requireMatch(SONAME, equals > 0 ? entry.substring(0, equals) : entry, line);
        final String path = requireMatch(PACKAGE_PATH, entry.substring(equals + 1), line);
        files.put(path, soname);
      }
      return new Pin(
        requireMatch(PLATFORM, fields.get(PIN_PLATFORM_FIELD), line),
        fields.get(PIN_NAME_FIELD),
        fields.get(PIN_VERSION_FIELD),
        requireMatch(HASH, fields.get(PIN_HASH_FIELD), line),
        Long.parseLong(fields.get(PIN_SIZE_FIELD)),
        requireMatch(POOL_PATH, fields.get(PIN_POOL_FIELD), line),
        Collections.unmodifiableMap(files)
      );
    }

    List<String> sonames() {
      return List.copyOf(new LinkedHashSet<>(this.files.values()));
    }
  }
}
