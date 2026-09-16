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
package me.brandonli.mcav.utils;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.lang.reflect.Type;
import java.net.BindException;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;
import me.brandonli.mcav.capability.installer.Download;
import me.brandonli.mcav.media.source.uri.UriSource;
import me.brandonli.mcav.utils.http.HttpDownloader;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * File, hashing, network, and archive helpers used throughout the library.
 *
 * <p>Methods that fail throw {@link UncheckedIOException}, so callers that cannot recover from I/O errors do not
 * have to handle checked exceptions.
 */
public final class IOUtils {

  private static final String INSTALLER_RESOURCE_FOLDER = "installers/";
  private static final long MAX_ZIP_ENTRY_SIZE = 512L * 1024L * 1024L;
  private static final long MAX_ZIP_TOTAL_SIZE = 2L * 1024L * 1024L * 1024L;
  private static final int COPY_BUFFER_SIZE = 64 * 1024;
  private static final int FIRST_VNC_PORT = 5900;
  private static final int LAST_PORT = 65535;
  private static final Set<PosixFilePermission> EXECUTABLE_PERMISSIONS = PosixFilePermissions.fromString("rwxr-xr-x");
  private static final int MOVE_ATTEMPTS = 3;
  private static final Duration MOVE_RETRY_DELAY = Duration.ofMillis(100);
  private static final int ZIP_SIGNATURE_LENGTH = 4;
  private static final byte[] ZIP_LOCAL_FILE_HEADER = { 0x50, 0x4B, 0x03, 0x04 };
  private static final byte[] ZIP_END_OF_CENTRAL_DIRECTORY = { 0x50, 0x4B, 0x05, 0x06 };

  private IOUtils() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Extracts the file name from the path of a URL.
   *
   * @param url the URL, such as {@code https://example.com/files/video.mp4}
   * @return the last segment of the path, such as {@code video.mp4}, or an empty string if the path is empty
   * @throws IllegalArgumentException if the URL is not a valid URI
   */
  public static String getFileNameFromUrl(final String url) {
    Preconditions.checkNotNull(url, "URL must not be null");
    final URI uri = URI.create(url);
    final String uriPath = uri.getPath();
    if (uriPath == null || uriPath.isEmpty()) {
      return "";
    }
    final int lastSlash = uriPath.lastIndexOf('/');
    return uriPath.substring(lastSlash + 1);
  }

  /**
   * Finds a free TCP port, starting at the standard VNC port 5900.
   *
   * @return the first free port at or above 5900
   * @throws UncheckedIOException if no port is free
   */
  public static int getNextFreeVNCPort() {
    return findFreePort(FIRST_VNC_PORT, LAST_PORT);
  }

  /**
   * Finds the first port in a range that nothing is listening on.
   *
   * @param firstPort the first port to try
   * @param lastPort  the last port to try
   * @return the free port
   * @throws UncheckedIOException if every port in the range is in use
   */
  @VisibleForTesting
  static int findFreePort(final int firstPort, final int lastPort) {
    for (int port = firstPort; port <= lastPort; port++) {
      try (final ServerSocket socket = new ServerSocket(port)) {
        return socket.getLocalPort();
      } catch (final IOException exception) {
        // the port is in use, try the next one
      }
    }
    final String message = "No free port available in range %d-%d".formatted(firstPort, lastPort);
    final BindException noFreePort = new BindException(message);
    throw new UncheckedIOException(message, noFreePort);
  }

  /**
   * Creates a directory and all of its missing parent directories.
   *
   * @param path the directory to create
   * @return true if the directory was created, false if it already existed
   * @throws UncheckedIOException if the directory cannot be created, for example because a file of the same name
   *                              exists
   */
  public static boolean createDirectoryIfNotExists(final Path path) {
    Preconditions.checkNotNull(path, "Path must not be null");
    final boolean exists = Files.isDirectory(path);
    if (exists) {
      return false;
    }
    try {
      Files.createDirectories(path);
      return true;
    } catch (final IOException exception) {
      final String message = exception.getMessage();
      throw new UncheckedIOException(message, exception);
    }
  }

  /**
   * Creates an empty file and all of its missing parent directories.
   *
   * @param path the file to create
   * @return true if the file was created, false if it already existed
   * @throws UncheckedIOException if the file cannot be created
   */
  public static boolean createFileIfNotExists(final Path path) {
    Preconditions.checkNotNull(path, "Path must not be null");
    final boolean exists = Files.exists(path);
    if (exists) {
      return false;
    }
    try {
      final Path parent = path.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.createFile(path);
      return true;
    } catch (final IOException exception) {
      final String message = exception.getMessage();
      throw new UncheckedIOException(message, exception);
    }
  }

  /**
   * Marks a file as executable for its owner and readable for everyone, without giving other users write access.
   * Does nothing on file systems without POSIX permissions, such as Windows.
   *
   * @param file the file to mark
   * @throws UncheckedIOException if the permissions cannot be changed
   */
  public static void markExecutable(final Path file) {
    Preconditions.checkNotNull(file, "File must not be null");
    final FileSystem fileSystem = file.getFileSystem();
    final Set<String> views = fileSystem.supportedFileAttributeViews();
    final boolean posix = views.contains("posix");
    if (!posix) {
      return;
    }
    try {
      Files.setPosixFilePermissions(file, EXECUTABLE_PERMISSIONS);
    } catch (final IOException exception) {
      final String message = exception.getMessage();
      throw new UncheckedIOException(message, exception);
    }
  }

  /**
   * Deletes a file or a directory tree. Missing paths are ignored, and symbolic links are deleted without following
   * them, including links whose target no longer exists. Like {@link #moveReplacing(Path, Path)}, this method throws the
   * checked {@link IOException}, because its callers are installers that report every failure that way.
   *
   * @param path the file or directory to delete
   * @throws IOException if a file cannot be deleted
   */
  public static void deleteRecursively(final Path path) throws IOException {
    Preconditions.checkNotNull(path, "Path must not be null");
    // a link whose target is gone does not exist when followed, but still has to be deleted
    final boolean exists = Files.exists(path, LinkOption.NOFOLLOW_LINKS);
    if (!exists) {
      return;
    }
    try (final Stream<Path> files = Files.walk(path)) {
      final Comparator<Path> deepestFirst = Comparator.reverseOrder();
      final Stream<Path> sorted = files.sorted(deepestFirst);
      final List<Path> ordered = sorted.toList();
      for (final Path file : ordered) {
        Files.deleteIfExists(file);
      }
    }
  }

  /**
   * Moves a file into place, replacing an existing file. The move is atomic where the file system supports it, so
   * readers see either the old or the new file; file systems without atomic moves replace the file with a regular
   * move instead. Unlike most methods of this class, this one throws the checked {@link IOException}, because its
   * callers already handle it.
   *
   * <p>A move that is denied access is tried up to three times in total, 100 and 200 milliseconds apart, because on
   * Windows another process such as a virus scanner or the search indexer may hold a freshly written file for a
   * moment. A move that is still denied after that, such as one that lacks the permission for good, fails with the
   * last {@link AccessDeniedException}. Moves of the same target from several threads are not coordinated here; the
   * callers that need that, such as {@link HttpDownloader}, serialize their moves themselves.
   *
   * @param source the file to move
   * @param target the destination, which is replaced if it exists
   * @throws IOException if the file cannot be moved
   */
  public static void moveReplacing(final Path source, final Path target) throws IOException {
    Preconditions.checkNotNull(source, "Source must not be null");
    Preconditions.checkNotNull(target, "Target must not be null");
    moveReplacing(source, target, Files::move, MOVE_RETRY_DELAY);
  }

  /**
   * Moves a file with the specified mover, falling back to a regular move when the atomic move is not supported and
   * trying a move that is denied access again after a delay.
   *
   * @param source     the file to move
   * @param target     the destination, which is replaced if it exists
   * @param mover      performs the move
   * @param retryDelay the delay before the second attempt; the third attempt waits twice as long
   * @throws IOException if the file cannot be moved
   */
  @VisibleForTesting
  static void moveReplacing(final Path source, final Path target, final FileMover mover, final Duration retryDelay) throws IOException {
    for (int attempt = 1;; attempt++) {
      try {
        moveOnce(source, target, mover);
        return;
      } catch (final AccessDeniedException exception) {
        if (attempt == MOVE_ATTEMPTS) {
          throw exception;
        }
        sleepBeforeMoveRetry(retryDelay, attempt, exception);
      }
    }
  }

  private static void moveOnce(final Path source, final Path target, final FileMover mover) throws IOException {
    try {
      mover.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (final AtomicMoveNotSupportedException exception) {
      mover.move(source, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private static void sleepBeforeMoveRetry(final Duration retryDelay, final int attempt, final AccessDeniedException failure)
    throws IOException {
    try {
      final Duration delay = retryDelay.multipliedBy(attempt);
      Thread.sleep(delay);
    } catch (final InterruptedException exception) {
      final Thread currentThread = Thread.currentThread();
      currentThread.interrupt();
      final InterruptedIOException interrupted = new InterruptedIOException("Interrupted while waiting to retry a denied move");
      interrupted.initCause(failure);
      throw interrupted;
    }
  }

  /**
   * Moves a file, like {@link Files#move(Path, Path, CopyOption...)}.
   */
  @FunctionalInterface
  @VisibleForTesting
  interface FileMover {
    /**
     * Moves the file.
     *
     * @param source  the file to move
     * @param target  the destination
     * @param options how the file is moved
     * @throws IOException if the file cannot be moved
     */
    void move(Path source, Path target, CopyOption... options) throws IOException;
  }

  /**
   * Computes the SHA-256 hash of a file. The file is streamed, so it is never held in memory completely.
   *
   * @param path the file to hash
   * @return the hash as lowercase hexadecimal
   * @throws UncheckedIOException if the file cannot be read
   */
  public static String getSHA256Hash(final Path path) {
    Preconditions.checkNotNull(path, "Path must not be null");
    return hashFile(path, "SHA-256");
  }

  /**
   * Computes the SHA-1 hash of a file. The file is streamed, so it is never held in memory completely. SHA-1 is
   * only suitable for identifying files, not for security purposes.
   *
   * @param path the file to hash
   * @return the hash as lowercase hexadecimal
   * @throws UncheckedIOException if the file cannot be read
   */
  public static String getSHA1Hash(final Path path) {
    Preconditions.checkNotNull(path, "Path must not be null");
    return hashFile(path, "SHA-1");
  }

  private static String hashFile(final Path path, final String algorithm) {
    final MessageDigest digest = createDigest(algorithm);
    try (final InputStream stream = Files.newInputStream(path)) {
      final byte[] buffer = new byte[COPY_BUFFER_SIZE];
      int read = stream.read(buffer);
      while (read != -1) {
        digest.update(buffer, 0, read);
        read = stream.read(buffer);
      }
    } catch (final IOException exception) {
      final String message = exception.getMessage();
      throw new UncheckedIOException(message, exception);
    }
    final byte[] hash = digest.digest();
    final HexFormat hex = HexFormat.of();
    return hex.formatHex(hash);
  }

  @VisibleForTesting
  static MessageDigest createDigest(final String algorithm) {
    try {
      return MessageDigest.getInstance(algorithm);
    } catch (final NoSuchAlgorithmException exception) {
      // every Java runtime is required to provide SHA-1 and SHA-256, so only a wrong name gets here
      throw new IllegalStateException("The Java runtime does not provide " + algorithm, exception);
    }
  }

  /**
   * Converts bytes into lowercase hexadecimal, two characters per byte.
   *
   * @param bytes the bytes to convert
   * @return the hexadecimal string, which is empty for an empty array
   */
  public static String bytesToHex(final byte[] bytes) {
    Preconditions.checkNotNull(bytes, "Bytes must not be null");
    final StringBuilder hex = new StringBuilder(bytes.length * 2);
    for (final byte value : bytes) {
      final int unsigned = value & 0xFF;
      final String digits = Integer.toHexString(unsigned);
      if (digits.length() == 1) {
        hex.append('0');
      }
      hex.append(digits);
    }
    return hex.toString();
  }

  /**
   * Gets the name of the file or directory a path points to.
   *
   * @param path the path
   * @return the last element of the path
   * @throws IllegalArgumentException if the path has no elements, such as a root directory
   */
  public static String getName(final Path path) {
    Preconditions.checkNotNull(path, "Path must not be null");
    final Path fileName = path.getFileName();
    if (fileName == null) {
      throw new IllegalArgumentException("Path has no file name: " + path);
    }
    return fileName.toString();
  }

  /**
   * Downloads an image into the cache folder. The file name is derived from the URL, so downloading the same URL
   * again overwrites the cached copy.
   *
   * @param source the URL of the image
   * @return the path to the downloaded image
   * @throws UncheckedIOException if the image cannot be downloaded
   */
  public static Path downloadImage(final UriSource source) {
    Preconditions.checkNotNull(source, "Source must not be null");
    final String url = source.getResource();
    final byte[] urlBytes = url.getBytes(StandardCharsets.UTF_8);
    final UUID id = UUID.nameUUIDFromBytes(urlBytes);
    final String fileName = id.toString();
    final Path cache = getCachedFolder();
    final Path destination = cache.resolve(fileName);
    final URI uri = URI.create(url);
    try {
      HttpDownloader.download(uri, destination);
    } catch (final IOException exception) {
      final String message = exception.getMessage();
      throw new UncheckedIOException(message, exception);
    }
    return destination;
  }

  /**
   * Gets the cache folder of the library, which is {@code ~/.mcav/cache}. The folder is created if it does not
   * exist.
   *
   * @return the absolute path of the cache folder
   * @throws UncheckedIOException if the folder cannot be created
   */
  public static Path getCachedFolder() {
    final String home = System.getProperty("user.home");
    final Path cacheDirectory = Path.of(home, ".mcav", "cache");
    createDirectoryIfNotExists(cacheDirectory);
    return cacheDirectory.toAbsolutePath();
  }

  /**
   * Reads the download list of an installer from the {@code installers} resource folder.
   *
   * @param resourcePath the name of the JSON resource, such as {@code yt-dlp.json}
   * @return the downloads described by the resource
   * @throws UncheckedIOException if the resource is missing or not valid JSON
   */
  public static Download[] readDownloadsFromJsonResource(final String resourcePath) {
    Preconditions.checkNotNull(resourcePath, "Resource path must not be null");
    final String installerJson = INSTALLER_RESOURCE_FOLDER + resourcePath;
    final Download[] downloads;
    try (final Reader reader = getResourceAsStreamReader(installerJson)) {
      downloads = parseDownloads(reader);
    } catch (final IOException exception) {
      final String message = exception.getMessage();
      throw new UncheckedIOException(message, exception);
    }
    if (downloads == null) {
      return new Download[0];
    }
    return downloads;
  }

  /**
   * Parses a download list. Malformed JSON is reported as an {@link IOException}, because a resource that cannot be
   * parsed is an I/O failure of that resource, and {@link UncheckedIOException} can only wrap an {@link IOException}.
   */
  private static Download@Nullable[] parseDownloads(final Reader reader) throws IOException {
    final Gson gson = new Gson();
    final TypeToken<Download[]> typeToken = new TypeToken<>() {};
    final Type downloadArrayType = typeToken.getType();
    try {
      return gson.fromJson(reader, downloadArrayType);
    } catch (final JsonSyntaxException exception) {
      final String message = exception.getMessage();
      throw new IOException(message, exception);
    }
  }

  /**
   * Opens a classpath resource of the library as a UTF-8 reader.
   *
   * @param resource the path of the resource, relative to the classpath root
   * @return a reader for the resource, which the caller must close
   * @throws UncheckedIOException if the resource does not exist
   * @throws NullPointerException if the resource is null
   */
  public static Reader getResourceAsStreamReader(final String resource) {
    Preconditions.checkNotNull(resource, "Resource must not be null");
    final InputStream stream = getResourceAsInputStream(resource);
    return new InputStreamReader(stream, StandardCharsets.UTF_8);
  }

  /**
   * Opens a classpath resource of the library.
   *
   * @param resource the path of the resource, relative to the classpath root
   * @return an input stream for the resource, which the caller must close
   * @throws UncheckedIOException if the resource does not exist
   * @throws NullPointerException if the resource is null
   */
  public static InputStream getResourceAsInputStream(final String resource) {
    Preconditions.checkNotNull(resource, "Resource must not be null");
    return getResourceAsInputStream(resource, IOUtils.class);
  }

  /**
   * Opens a classpath resource using the class loader of the specified class.
   *
   * @param resource    the path of the resource, relative to the classpath root
   * @param loaderClass the class whose class loader is used to find the resource
   * @return an input stream for the resource, which the caller must close
   * @throws UncheckedIOException if the resource does not exist, or the class has no class loader to find it with
   * @throws NullPointerException if the resource or the class is null
   */
  public static InputStream getResourceAsInputStream(final String resource, final Class<?> loaderClass) {
    Preconditions.checkNotNull(resource, "Resource must not be null");
    Preconditions.checkNotNull(loaderClass, "Class must not be null");
    final ClassLoader classLoader = loaderClass.getClassLoader();
    final InputStream stream = classLoader == null ? null : classLoader.getResourceAsStream(resource);
    if (stream == null) {
      final String message = "Resource does not exist: " + resource;
      final FileNotFoundException missingResource = new FileNotFoundException(message);
      throw new UncheckedIOException(message, missingResource);
    }
    return stream;
  }

  /**
   * Extracts a zip archive into a directory.
   *
   * <p>Entries that would escape the destination directory are rejected, and single entries larger than 512 MB or
   * archives larger than 2 GB in total are rejected as well, to protect against malicious archives.
   *
   * @param archive     the zip archive
   * @param destination the directory to extract into, which is created if it does not exist
   * @throws UncheckedIOException        if the file is not a zip archive, cannot be read, or the files cannot be
   *                                     written
   * @throws ZipEntryIntegrityException if the archive contains an entry that is not safe to extract
   * @throws NullPointerException        if the archive or the destination is null
   */
  public static void unzip(final Path archive, final Path destination) {
    Preconditions.checkNotNull(archive, "Archive must not be null");
    Preconditions.checkNotNull(destination, "Destination must not be null");
    unzip(archive, destination, MAX_ZIP_ENTRY_SIZE, MAX_ZIP_TOTAL_SIZE);
  }

  /**
   * Extracts a zip archive into a directory with custom size limits.
   *
   * @param archive      the zip archive
   * @param destination  the directory to extract into, which is created if it does not exist
   * @param maxEntrySize the largest number of bytes a single entry may extract to
   * @param maxTotalSize the largest number of bytes the whole archive may extract to
   */
  @VisibleForTesting
  static void unzip(final Path archive, final Path destination, final long maxEntrySize, final long maxTotalSize) {
    Preconditions.checkNotNull(archive, "Archive must not be null");
    Preconditions.checkNotNull(destination, "Destination must not be null");

    final Path absoluteDestination = destination.toAbsolutePath();
    final Path normalizedDestination = absoluteDestination.normalize();
    createDirectoryIfNotExists(normalizedDestination);
    try {
      requireZipArchive(archive);
      extractArchive(archive, normalizedDestination, maxEntrySize, maxTotalSize);
    } catch (final IOException exception) {
      final String message = exception.getMessage();
      throw new UncheckedIOException(message, exception);
    }
  }

  /**
   * Fails unless a file starts like a zip archive. {@link ZipInputStream} reads any other file as an archive without
   * entries, which would let a truncated or wrong download pass as an empty archive.
   */
  private static void requireZipArchive(final Path archive) throws IOException {
    final byte[] signature = new byte[ZIP_SIGNATURE_LENGTH];
    try (final InputStream stream = Files.newInputStream(archive)) {
      stream.readNBytes(signature, 0, ZIP_SIGNATURE_LENGTH);
    }
    final boolean hasEntries = Arrays.equals(signature, ZIP_LOCAL_FILE_HEADER);
    final boolean empty = Arrays.equals(signature, ZIP_END_OF_CENTRAL_DIRECTORY);
    if (!hasEntries && !empty) {
      throw new ZipException("Not a zip archive: " + archive);
    }
  }

  private static void extractArchive(final Path archive, final Path destination, final long maxEntrySize, final long maxTotalSize)
    throws IOException {
    try (final InputStream fileStream = Files.newInputStream(archive); final ZipInputStream zip = new ZipInputStream(fileStream)) {
      extractEntries(zip, destination, maxEntrySize, maxTotalSize);
    }
  }

  private static void extractEntries(final ZipInputStream zip, final Path destination, final long maxEntrySize, final long maxTotalSize)
    throws IOException {
    long totalSize = 0;
    ZipEntry entry = zip.getNextEntry();
    while (entry != null) {
      final long written = extractEntry(zip, entry, destination, maxEntrySize);
      totalSize += written;
      if (totalSize > maxTotalSize) {
        throw new ZipEntryIntegrityException("Total extracted size exceeds the limit");
      }
      zip.closeEntry();
      entry = zip.getNextEntry();
    }
  }

  private static long extractEntry(final ZipInputStream zip, final ZipEntry entry, final Path destination, final long maxEntrySize)
    throws IOException {
    final String name = entry.getName();
    final Path target = resolveInside(destination, name);
    if (entry.isDirectory()) {
      Files.createDirectories(target);
      return 0;
    }
    final Path parentOrNull = target.getParent();
    final Path parent = Objects.requireNonNull(parentOrNull, "An entry inside the destination always has a parent");
    Files.createDirectories(parent);
    return writeZipEntry(zip, target, name, maxEntrySize);
  }

  private static Path resolveInside(final Path destination, final String name) {
    final Path resolved = destination.resolve(name);
    final Path target = resolved.normalize();
    final boolean insideDestination = target.startsWith(destination);
    if (!insideDestination) {
      final String message = "Zip entry escapes the destination directory: %s".formatted(name);
      throw new ZipEntryIntegrityException(message);
    }
    return target;
  }

  private static long writeZipEntry(final ZipInputStream zip, final Path target, final String name, final long maxEntrySize)
    throws IOException {
    long entrySize = 0;
    try (final OutputStream output = Files.newOutputStream(target)) {
      final byte[] buffer = new byte[COPY_BUFFER_SIZE];
      int read = zip.read(buffer);
      while (read != -1) {
        entrySize += read;
        if (entrySize > maxEntrySize) {
          final String message = "Zip entry exceeds the maximum size: %s".formatted(name);
          throw new ZipEntryIntegrityException(message);
        }
        output.write(buffer, 0, read);
        read = zip.read(buffer);
      }
    }
    return entrySize;
  }
}
