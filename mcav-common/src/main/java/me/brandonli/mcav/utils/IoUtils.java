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
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.Reader;
import java.lang.reflect.Type;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;
import me.brandonli.mcav.MCAVException;
import me.brandonli.mcav.capability.installer.Download;
import me.brandonli.mcav.media.source.uri.UriSource;
import me.brandonli.mcav.utils.http.DownloadException;
import me.brandonli.mcav.utils.http.HttpDownloader;
import me.brandonli.mcav.utils.os.Arch;
import me.brandonli.mcav.utils.os.Bits;
import me.brandonli.mcav.utils.os.Os;
import me.brandonli.mcav.utils.os.Platform;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * File, hashing, network, and archive helpers used throughout the library.
 *
 * <p>I/O failures are reported as a {@link FileOperationException}, and a port search that finds no free port as an
 * {@link MCAVException}; argument errors and unsafe archive entries use their documented exception types.
 *
 * <p>This class is thread-safe.
 *
 * @since 1.0.0
 */
public final class IoUtils {

  /**
   * The largest picture {@link #downloadImage(UriSource)} downloads. A frame of the largest map screen is a few
   * megabytes as a PNG, so this leaves room for an unusually large picture while a server that never stops sending
   * cannot fill the disk.
   *
   * @since 1.0.0
   */
  public static final long MAXIMUM_IMAGE_BYTES = 64L * 1024L * 1024L;

  private static final Logger LOGGER = LoggerFactory.getLogger(IoUtils.class);

  private static final AtomicBoolean CACHE_FALLBACK_REPORTED = new AtomicBoolean();

  private static final long MAXIMUM_ZIP_ENTRY_SIZE = 512L * 1024L * 1024L;
  private static final long MAXIMUM_ZIP_TOTAL_SIZE = 2L * 1024L * 1024L * 1024L;
  private static final int COPY_BUFFER_SIZE = 64 * 1024;

  private static final int FIRST_VNC_PORT = 5900;
  private static final int LAST_PORT = 65535;

  private static final Set<PosixFilePermission> EXECUTABLE_PERMISSIONS = PosixFilePermissions.fromString("rwxr-xr-x");
  private static final String POSIX_ATTRIBUTE_VIEW = "posix";

  private static final int MOVE_ATTEMPTS = 3;
  private static final Duration MOVE_RETRY_DELAY = Duration.ofMillis(100);

  private static final int ZIP_SIGNATURE_LENGTH = 4;
  private static final byte[] ZIP_LOCAL_FILE_HEADER = { 0x50, 0x4B, 0x03, 0x04 };
  private static final byte[] ZIP_END_OF_CENTRAL_DIRECTORY = { 0x50, 0x4B, 0x05, 0x06 };
  private static final int ZIP_LOCAL_FILE_HEADER_REMAINDER = 26;
  private static final int ZIP_END_OF_CENTRAL_DIRECTORY_REMAINDER = 18;
  private static final int ZIP_COMMENT_LENGTH_LOW = 16;
  private static final int ZIP_COMMENT_LENGTH_HIGH = 17;

  private static final String SHA_256 = "SHA-256";
  private static final String SHA_1 = "SHA-1";
  private static final int HEX_DIGITS_PER_BYTE = 2;

  private static final int UNSIGNED_BYTE_MASK = 0xFF;
  private static final int BITS_PER_BYTE = 8;

  private static final String USER_HOME_PROPERTY = "user.home";
  private static final String CACHE_ROOT = ".mcav";
  private static final String CACHE_DIRECTORY = "cache";

  private static final String TEMPORARY_DIRECTORY_PROPERTY = "java.io.tmpdir";
  private static final String TEMPORARY_CACHE_ROOT = "mcav";

  private static final String EXECUTION_PROBE_PREFIX = "execution-probe-";
  private static final String EXECUTION_PROBE_SUFFIX = ".tmp";

  private static final String TRUNCATED_ARCHIVE_MESSAGE = "Zip archive %s is truncated!";

  private static final String PARENT_DIRECTORY = "..";

  private IoUtils() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated!");
  }

  /**
   * Extracts the file name from the path of a URL.
   *
   * @param url the URL, such as {@code https://example.com/files/video.mp4}
   * @return the last segment of the path, such as {@code video.mp4}, or an empty string if the path is empty
   * @throws IllegalArgumentException if the URL is not a valid URI
   * @throws NullPointerException     if {@code url} is null
   * @since 1.0.0
   */
  public static String getFileNameFromUrl(final String url) {
    Preconditions.checkNotNull(url, "URL must not be null!");

    final URI uri = URI.create(url);
    final String uriPath = uri.getPath();
    if (uriPath == null || uriPath.isEmpty()) {
      return "";
    }

    final int lastSlash = uriPath.lastIndexOf('/');
    return uriPath.substring(lastSlash + 1);
  }

  /**
   * Probes TCP ports from 5900 through 65535 and returns the first one that can be bound. The probe socket
   * is immediately closed: another process can claim the returned port before the caller binds it.
   *
   * @return the first free port at or above 5900
   * @throws MCAVException if no port is free
   * @since 1.0.0
   */
  public static int getNextFreeVncPort() {
    return findFreePort(FIRST_VNC_PORT, LAST_PORT);
  }

  @VisibleForTesting
  static int findFreePort(final int firstPort, final int lastPort) {
    for (int port = firstPort; port <= lastPort; port++) {
      try (final ServerSocket socket = new ServerSocket(port)) {
        return socket.getLocalPort();
      } catch (final IOException exception) {
        // the port is in use, try the next one
      }
    }

    final String message = "Port range %s-%s has no free port!".formatted(firstPort, lastPort);
    throw new MCAVException(message);
  }

  /**
   * Creates a directory and all of its missing parent directories.
   *
   * @param path the directory to create
   * @return true if the directory was created, false if it already existed
   * @throws FileOperationException if the directory cannot be created, for example because a file of the same name
   *                                exists
   * @throws NullPointerException   if {@code path} is null
   * @since 1.0.0
   */
  public static boolean createDirectoryIfNotExists(final Path path) {
    Preconditions.checkNotNull(path, "Path must not be null!");

    final boolean exists = Files.isDirectory(path);
    if (exists) {
      return false;
    }

    try {
      Files.createDirectories(path);

      return true;
    } catch (final IOException exception) {
      final String message = "Directory %s could not be created!".formatted(path);
      throw new FileOperationException(message, exception);
    }
  }

  /**
   * Creates an empty file and all of its missing parent directories.
   *
   * @param path the file to create
   * @return true if the file was created, false if it already existed
   * @throws FileOperationException if the file cannot be created
   * @throws NullPointerException   if {@code path} is null
   * @since 1.0.0
   */
  public static boolean createFileIfNotExists(final Path path) {
    Preconditions.checkNotNull(path, "Path must not be null!");

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
      final String message = "File %s could not be created!".formatted(path);
      throw new FileOperationException(message, exception);
    }
  }

  /**
   * Marks a file as executable for its owner and readable for everyone, without giving other users write access.
   * Does nothing on file systems without POSIX permissions, such as Windows.
   *
   * @param file the file to mark
   * @throws FileOperationException if the permissions cannot be changed
   * @throws NullPointerException   if {@code file} is null
   * @since 1.0.0
   */
  public static void markExecutable(final Path file) {
    Preconditions.checkNotNull(file, "File must not be null!");

    final FileSystem fileSystem = file.getFileSystem();
    final Set<String> views = fileSystem.supportedFileAttributeViews();
    final boolean supportsPosix = views.contains(POSIX_ATTRIBUTE_VIEW);
    if (!supportsPosix) {
      return;
    }

    try {
      Files.setPosixFilePermissions(file, EXECUTABLE_PERMISSIONS);
    } catch (final IOException exception) {
      final String message = "File %s could not be marked executable!".formatted(file);
      throw new FileOperationException(message, exception);
    }
  }

  /**
   * Deletes a file or a directory tree. Missing paths are ignored, and symbolic links are deleted without following
   * them, including links whose target no longer exists.
   *
   * @param path the file or directory to delete
   * @throws FileOperationException if a file cannot be deleted
   * @throws NullPointerException   if {@code path} is null
   * @since 1.0.0
   */
  public static void deleteRecursively(final Path path) {
    Preconditions.checkNotNull(path, "Path must not be null!");

    try {
      deleteTree(path);
    } catch (final IOException exception) {
      final String message = "Path %s could not be deleted!".formatted(path);
      throw new FileOperationException(message, exception);
    }
  }

  private static void deleteTree(final Path path) throws IOException {
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
   * move instead.
   *
   * <p>A move that is denied access is tried up to three times in total, 100 and 200 milliseconds apart, because on
   * Windows another process such as a virus scanner or the search indexer may hold a freshly written file for a
   * moment. A move that is still denied after that, such as one that lacks the permission for good, fails with the
   * last {@link AccessDeniedException} as its cause. Moves of the same target from several threads are not coordinated here; the
   * callers that need that, such as {@link HttpDownloader}, serialize their moves themselves.
   *
   * @param source the file to move
   * @param target the destination, which is replaced if it exists
   * @throws FileOperationException if the file cannot be moved
   * @throws NullPointerException   if {@code source} or {@code target} is null
   * @since 1.0.0
   */
  public static void moveReplacing(final Path source, final Path target) {
    Preconditions.checkNotNull(source, "Source must not be null!");
    Preconditions.checkNotNull(target, "Target must not be null!");

    try {
      moveReplacing(source, target, Files::move, MOVE_RETRY_DELAY);
    } catch (final IOException exception) {
      final String message = "File %s could not be moved to %s!".formatted(source, target);
      throw new FileOperationException(message, exception);
    }
  }

  @VisibleForTesting
  static void moveReplacing(final Path source, final Path target, final FileMover mover, final Duration retryDelay) throws IOException {
    for (int attempt = 1; ; attempt++) {
      try {
        moveOnce(source, target, mover);
        return;
      } catch (final AccessDeniedException exception) {
        rethrowOnLastMoveAttempt(attempt, exception);
        sleepBeforeMoveRetry(retryDelay, attempt, exception);
      }
    }
  }

  private static void rethrowOnLastMoveAttempt(final int attempt, final AccessDeniedException failure) throws AccessDeniedException {
    if (attempt == MOVE_ATTEMPTS) {
      throw failure;
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
      final InterruptedIOException interrupted = new InterruptedIOException("Interrupted while waiting to retry a denied move!");
      interrupted.initCause(failure);
      throw interrupted;
    }
  }

  /**
   * Computes the SHA-256 hash of a file. The file is streamed, so it is never held in memory completely.
   *
   * @param path the file to hash
   * @return the hash as lowercase hexadecimal
   * @throws FileOperationException if the file cannot be read
   * @throws NullPointerException   if {@code path} is null
   * @since 1.0.0
   */
  public static String getSha256Hash(final Path path) {
    Preconditions.checkNotNull(path, "Path must not be null!");

    return hashFile(path, SHA_256);
  }

  /**
   * Computes the SHA-1 hash of a file. The file is streamed, so it is never held in memory completely. SHA-1 is
   * only suitable for identifying files, not for security purposes.
   *
   * @param path the file to hash
   * @return the hash as lowercase hexadecimal
   * @throws FileOperationException if the file cannot be read
   * @throws NullPointerException   if {@code path} is null
   * @since 1.0.0
   */
  public static String getSha1Hash(final Path path) {
    Preconditions.checkNotNull(path, "Path must not be null!");

    return hashFile(path, SHA_1);
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
      final String message = "File %s could not be hashed!".formatted(path);
      throw new FileOperationException(message, exception);
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
      final String message = "Digest algorithm %s is not provided by the Java runtime!".formatted(algorithm);
      throw new IllegalStateException(message, exception);
    }
  }

  /**
   * Converts bytes into lowercase hexadecimal, two characters per byte.
   *
   * @param bytes the bytes to convert
   * @return the hexadecimal string, which is empty for an empty array
   * @throws NullPointerException if {@code bytes} is null
   * @since 1.0.0
   */
  public static String bytesToHex(final byte[] bytes) {
    Preconditions.checkNotNull(bytes, "Bytes must not be null!");

    final StringBuilder hex = new StringBuilder(bytes.length * HEX_DIGITS_PER_BYTE);
    for (final byte value : bytes) {
      final int unsigned = value & UNSIGNED_BYTE_MASK;
      final String digits = Integer.toHexString(unsigned);
      if (digits.length() == 1) {
        hex.append('0');
      }
      hex.append(digits);
    }

    return hex.toString();
  }

  /**
   * Returns the name of the file or directory a path points to.
   *
   * @param path the path
   * @return the last element of the path
   * @throws IllegalArgumentException if the path has no elements, such as a root directory
   * @throws NullPointerException     if {@code path} is null
   * @since 1.0.0
   */
  public static String getName(final Path path) {
    Preconditions.checkNotNull(path, "Path must not be null!");

    final Path fileName = path.getFileName();
    if (fileName == null) {
      final String message = "Path %s has no file name!".formatted(path);
      throw new IllegalArgumentException(message);
    }

    return fileName.toString();
  }

  /**
   * Downloads an image into the cache folder. The file name is derived from the URL, so downloading the same URL
   * again overwrites the cached copy.
   *
   * <p>The download stops as soon as it passes {@link #MAXIMUM_IMAGE_BYTES}, because the server behind the URL chooses
   * how much it sends and a picture of a map screen is far smaller than that.
   *
   * @param source the URL of the image
   * @return the cached file path; releasing a decoded image does not remove this file
   * @throws DownloadException       if the image cannot be downloaded, including when it is larger than
   *                                  {@link #MAXIMUM_IMAGE_BYTES}
   * @throws FileOperationException   if the downloaded image cannot be moved into the cache folder
   * @throws IllegalArgumentException if the source does not describe an absolute HTTP or HTTPS URI with a host
   * @throws NullPointerException     if {@code source} is null
   * @since 1.0.0
   */
  public static Path downloadImage(final UriSource source) {
    Preconditions.checkNotNull(source, "Source must not be null!");

    final String url = source.getResource();
    final byte[] urlBytes = url.getBytes(StandardCharsets.UTF_8);
    final UUID id = UUID.nameUUIDFromBytes(urlBytes);
    final String fileName = id.toString();
    final Path cache = getCachedFolder();
    final Path destination = cache.resolve(fileName);
    final URI uri = URI.create(url);
    HttpDownloader.download(uri, destination, MAXIMUM_IMAGE_BYTES);
    return destination;
  }

  /**
   * Returns the cache folder of the library, which is {@code ~/.mcav/cache}. The folder is created if it does not
   * exist.
   *
   * <p>The cache holds the programs MCAV downloads and runs, such as VLC and yt-dlp. When that folder cannot be created
   * or written, for instance because the home directory is read-only, or lies on a file system mounted {@code noexec}
   * that cannot run them, the cache moves to {@code mcav/cache} in the temporary directory of the JVM
   * ({@code java.io.tmpdir}), and a warning is logged the first time. That folder is used even if it cannot run programs either; a
   * program run from it then fails when it is installed.
   *
   * @return the absolute path of the cache folder
   * @throws FileOperationException if neither folder can be created
   * @since 1.0.0
   */
  public static Path getCachedFolder() {
    return getCachedFolder(Files::isExecutable, CACHE_FALLBACK_REPORTED);
  }

  // test seam for the mutants of the fallback from a home cache on a noexec mount, which only a mount option reaches,
  // and of the warning logged once, whose flag for the whole JVM another test may have set already
  @VisibleForTesting
  static Path getCachedFolder(final Predicate<Path> executable, final AtomicBoolean fallbackReported) {
    final @Nullable String homeOrNull = System.getProperty(USER_HOME_PROPERTY);
    final String home = Objects.requireNonNull(homeOrNull, "The JVM always sets the user home!");
    final Path homeCache = Path.of(home, CACHE_ROOT, CACHE_DIRECTORY);
    final boolean usable = createUsableDirectory(homeCache, executable);
    if (usable) {
      return homeCache.toAbsolutePath();
    }

    final @Nullable String temporaryOrNull = System.getProperty(TEMPORARY_DIRECTORY_PROPERTY);
    final String temporary = Objects.requireNonNull(temporaryOrNull, "The JVM always sets the temporary directory!");
    final Path temporaryCache = Path.of(temporary, TEMPORARY_CACHE_ROOT, CACHE_DIRECTORY);
    final boolean firstFallback = fallbackReported.compareAndSet(false, true);
    if (firstFallback) {
      LOGGER.warn("Cache folder {} cannot be written or cannot run programs, using {} instead.", homeCache, temporaryCache);
    }

    createDirectoryIfNotExists(temporaryCache);
    return temporaryCache.toAbsolutePath();
  }

  // Creates a directory if it is missing and returns whether a file written into it can be run. A directory that
  // cannot be created or written cannot hold the cache either, so that failure is the answer and not reported.
  private static boolean createUsableDirectory(final Path directory, final Predicate<Path> executable) {
    try {
      createDirectoryIfNotExists(directory);
      final Path probe = Files.createTempFile(directory, EXECUTION_PROBE_PREFIX, EXECUTION_PROBE_SUFFIX);
      return isExecutableProbe(probe, executable);
    } catch (final FileOperationException | IOException exception) {
      return false;
    }
  }

  // Linux refuses the execute access check on every regular file of a noexec mount, even one marked executable, so a
  // probe marked executable answers for the whole file system.
  private static boolean isExecutableProbe(final Path probe, final Predicate<Path> executable) throws IOException {
    try {
      markExecutable(probe);
      return executable.test(probe);
    } finally {
      Files.delete(probe);
    }
  }

  /**
   * Creates a folder if it is missing and returns whether programs written into it can run. The answer is
   * {@code false} when the folder cannot be created or written, or lies on a file system mounted {@code noexec}; a probe
   * file written into the folder, marked executable and deleted again finds that out.
   *
   * @param folder the folder to create and check
   * @return whether programs written into the folder can run
   * @throws NullPointerException if {@code folder} is null
   * @since 1.0.0
   */
  public static boolean createRunnableFolder(final Path folder) {
    Preconditions.checkNotNull(folder, "Folder must not be null!");

    return createUsableDirectory(folder, Files::isExecutable);
  }

  /**
   * Reads the download list of an installer from a classpath resource.
   *
   * @param resourcePath the full path from the classpath root, such as {@code mcav/common/installers/yt-dlp.json}
   * @return the downloads described by the resource
   * @throws FileOperationException if the resource is missing, is not valid JSON, or lists a download without a platform
   *                                or URL
   * @throws NullPointerException   if {@code resourcePath} is null
   * @since 1.0.0
   */
  public static Download[] readDownloadsFromJsonResource(final String resourcePath) {
    Preconditions.checkNotNull(resourcePath, "Resource path must not be null!");

    try (final Reader reader = getResourceAsStreamReader(resourcePath)) {
      return parseDownloads(reader);
    } catch (final IOException exception) {
      final String message = "Download list %s could not be read!".formatted(resourcePath);
      throw new FileOperationException(message, exception);
    }
  }

  // Parses a download list. Malformed JSON is reported as an IOException, because a resource that cannot be parsed is
  // an I/O failure of that resource.
  @VisibleForTesting
  static Download[] parseDownloads(final Reader reader) throws IOException {
    final DownloadFields @Nullable [] parsed = readDownloadArray(reader);
    if (parsed == null) {
      return new Download[0];
    }

    final Download[] downloads = new Download[parsed.length];
    for (int index = 0; index < parsed.length; index++) {
      downloads[index] = buildDownload(parsed[index]);
    }

    return downloads;
  }

  private static DownloadFields @Nullable [] readDownloadArray(final Reader reader) throws IOException {
    final Gson gson = new Gson();
    final TypeToken<DownloadFields[]> typeToken = new TypeToken<>() {};
    final Type downloadArrayType = typeToken.getType();
    try {
      return gson.fromJson(reader, downloadArrayType);
    } catch (final JsonSyntaxException exception) {
      final String message = exception.getMessage();
      throw new IOException(message, exception);
    }
  }

  // Gson passes null for a null entry, a missing value or an unknown enum constant, so they only surface when the
  // constructors of Platform and Download check the values.
  private static Download buildDownload(final DownloadFields fields) throws IOException {
    try {
      final PlatformFields platformFields = fields.platform();
      final Os os = platformFields.os();
      final Arch arch = platformFields.arch();
      final Bits bits = platformFields.bits();
      final Platform platform = Platform.ofPlatform(os, arch, bits);
      final String url = fields.url();
      final @Nullable String hash = fields.hash();
      return new Download(platform, url, hash);
    } catch (final NullPointerException | IllegalArgumentException exception) {
      throw new IOException("Download list has an incomplete download!", exception);
    }
  }

  /**
   * Opens a classpath resource of the library as a UTF-8 reader.
   *
   * @param resource the path of the resource, relative to the classpath root
   * @return a reader for the resource, which the caller must close
   * @throws FileOperationException if the resource does not exist
   * @throws NullPointerException   if the resource is null
   * @since 1.0.0
   */
  public static Reader getResourceAsStreamReader(final String resource) {
    Preconditions.checkNotNull(resource, "Resource must not be null!");

    final InputStream stream = getResourceAsInputStream(resource);
    return new InputStreamReader(stream, StandardCharsets.UTF_8);
  }

  /**
   * Opens a classpath resource of the library.
   *
   * @param resource the path of the resource, relative to the classpath root
   * @return an input stream for the resource, which the caller must close
   * @throws FileOperationException if the resource does not exist
   * @throws NullPointerException   if the resource is null
   * @since 1.0.0
   */
  public static InputStream getResourceAsInputStream(final String resource) {
    Preconditions.checkNotNull(resource, "Resource must not be null!");

    return getResourceAsInputStream(resource, IoUtils.class);
  }

  /**
   * Opens a classpath resource using the class loader of the specified class.
   *
   * @param resource    the path of the resource, relative to the classpath root
   * @param loaderClass the class whose class loader is used to find the resource
   * @return an input stream for the resource, which the caller must close
   * @throws FileOperationException if the resource does not exist, or the class has no class loader to find it with
   * @throws NullPointerException   if the resource or the class is null
   * @since 1.0.0
   */
  public static InputStream getResourceAsInputStream(final String resource, final Class<?> loaderClass) {
    Preconditions.checkNotNull(resource, "Resource must not be null!");
    Preconditions.checkNotNull(loaderClass, "Class must not be null!");

    final ClassLoader classLoader = loaderClass.getClassLoader();
    final InputStream stream = classLoader == null ? null : classLoader.getResourceAsStream(resource);
    if (stream == null) {
      final String message = "Resource %s does not exist!".formatted(resource);
      throw new FileOperationException(message);
    }

    return stream;
  }

  /**
   * Extracts a zip archive into a directory.
   *
   * <p>The destination is resolved to its real directory. Entries that would escape it or use an existing symbolic
   * link below it are rejected. Single entries larger than 512 MiB or archives larger than 2 GiB in total are
   * rejected before bytes beyond either limit are written. Extraction may leave files written before a failure.
   * The destination must not be modified concurrently: these checks do not prevent another process from replacing
   * a parent directory between a check and a write.
   *
   * @param archive     the zip archive
   * @param destination the directory to extract into, which is created if it does not exist
   * @throws FileOperationException     if the file is not a zip archive, cannot be read, or the files cannot be
   *                                    written
   * @throws ZipEntryIntegrityException if the archive contains an entry that is not safe to extract
   * @throws NullPointerException       if the archive or the destination is null
   * @since 1.0.0
   */
  public static void unzip(final Path archive, final Path destination) {
    Preconditions.checkNotNull(archive, "Archive must not be null!");
    Preconditions.checkNotNull(destination, "Destination must not be null!");

    unzip(archive, destination, MAXIMUM_ZIP_ENTRY_SIZE, MAXIMUM_ZIP_TOTAL_SIZE);
  }

  @VisibleForTesting
  static void unzip(final Path archive, final Path destination, final long maximumEntrySize, final long maximumTotalSize) {
    Preconditions.checkNotNull(archive, "Archive must not be null!");
    Preconditions.checkNotNull(destination, "Destination must not be null!");
    Preconditions.checkArgument(maximumEntrySize >= 0, "Entry size limit must not be negative!");
    Preconditions.checkArgument(maximumTotalSize >= 0, "Total size limit must not be negative!");

    final Path absoluteDestination = destination.toAbsolutePath();
    final Path normalizedDestination = absoluteDestination.normalize();
    createDirectoryIfNotExists(normalizedDestination);
    try {
      requireZipArchive(archive);
      final Path realDestination = normalizedDestination.toRealPath();
      extractArchive(archive, realDestination, maximumEntrySize, maximumTotalSize);
    } catch (final IOException exception) {
      final String message = "Archive %s could not be extracted!".formatted(archive);
      throw new FileOperationException(message, exception);
    }
  }

  private static void requireZipArchive(final Path archive) throws IOException {
    try (final InputStream stream = Files.newInputStream(archive)) {
      final byte[] signature = stream.readNBytes(ZIP_SIGNATURE_LENGTH);
      final boolean hasEntries = Arrays.equals(signature, ZIP_LOCAL_FILE_HEADER);
      final boolean empty = Arrays.equals(signature, ZIP_END_OF_CENTRAL_DIRECTORY);
      if (!hasEntries && !empty) {
        final String message = "File %s is not a zip archive!".formatted(archive);
        throw new ZipException(message);
      }

      final int remainingHeaderSize = empty ? ZIP_END_OF_CENTRAL_DIRECTORY_REMAINDER : ZIP_LOCAL_FILE_HEADER_REMAINDER;
      final byte[] header = stream.readNBytes(remainingHeaderSize);
      if (header.length != remainingHeaderSize) {
        final String message = TRUNCATED_ARCHIVE_MESSAGE.formatted(archive);
        throw new ZipException(message);
      }

      if (empty) {
        requireCompleteComment(stream, header, archive);
      }
    }
  }

  private static void requireCompleteComment(final InputStream stream, final byte[] header, final Path archive) throws IOException {
    final int commentLow = header[ZIP_COMMENT_LENGTH_LOW] & UNSIGNED_BYTE_MASK;
    final int commentHigh = header[ZIP_COMMENT_LENGTH_HIGH] & UNSIGNED_BYTE_MASK;
    final int commentLength = commentLow | (commentHigh << BITS_PER_BYTE);
    final byte[] comment = stream.readNBytes(commentLength);
    if (comment.length != commentLength) {
      final String message = TRUNCATED_ARCHIVE_MESSAGE.formatted(archive);
      throw new ZipException(message);
    }
  }

  private static void extractArchive(final Path archive, final Path destination, final long maximumEntrySize, final long maximumTotalSize)
    throws IOException {
    try (final InputStream fileStream = Files.newInputStream(archive); final ZipInputStream zip = new ZipInputStream(fileStream)) {
      extractEntries(zip, destination, maximumEntrySize, maximumTotalSize);
    }
  }

  private static void extractEntries(
    final ZipInputStream zip,
    final Path destination,
    final long maximumEntrySize,
    final long maximumTotalSize
  ) throws IOException {
    long totalSize = 0;
    ZipEntry entry = zip.getNextEntry();
    while (entry != null) {
      final long remainingTotalSize = maximumTotalSize - totalSize;
      final long written = extractEntry(zip, entry, destination, maximumEntrySize, remainingTotalSize);
      totalSize += written;
      zip.closeEntry();
      entry = zip.getNextEntry();
    }
  }

  private static long extractEntry(
    final ZipInputStream zip,
    final ZipEntry entry,
    final Path destination,
    final long maximumEntrySize,
    final long remainingTotalSize
  ) throws IOException {
    final String name = entry.getName();
    final Path target = resolveInside(destination, name);
    if (entry.isDirectory()) {
      // a directory has no data; closing the entry would inflate whatever it carries, outside every size budget
      if (zip.read() != -1) {
        final String message = "Zip directory entry %s must not carry data!".formatted(name);
        throw new ZipEntryIntegrityException(message);
      }

      Files.createDirectories(target);
      return 0;
    }

    final Path parentOrNull = target.getParent();
    final Path parent = Objects.requireNonNull(parentOrNull, "An entry inside the destination always has a parent!");
    Files.createDirectories(parent);
    return writeZipEntry(zip, target, name, maximumEntrySize, remainingTotalSize);
  }

  private static Path resolveInside(final Path destination, final String name) {
    final Path resolved;
    try {
      resolved = destination.resolve(name);
    } catch (final InvalidPathException exception) {
      final String message = "Zip entry name %s is not a path of this system!".formatted(name);
      final ZipEntryIntegrityException refused = new ZipEntryIntegrityException(message);
      refused.initCause(exception);
      throw refused;
    }

    final Path target = resolved.normalize();
    final boolean insideDestination = isInside(destination, target);
    if (!insideDestination) {
      final String message = "Zip entry %s escapes the destination directory!".formatted(name);
      throw new ZipEntryIntegrityException(message);
    }

    final boolean emptyName = hasEmptyName(destination, target);
    if (emptyName) {
      final String message = "Zip entry %s has an empty name in its path!".formatted(name);
      throw new ZipEntryIntegrityException(message);
    }

    rejectSymbolicLinks(destination, target, name);
    return target;
  }

  private static boolean isInside(final Path destination, final Path target) {
    if (!target.startsWith(destination)) {
      return false;
    }

    // the zip file system keeps a .. that follows an empty name, as in the entry ../\x, when normalizing
    final Path relative = destination.relativize(target);
    for (final Path component : relative) {
      final String componentName = component.toString();
      if (componentName.equals(PARENT_DIRECTORY)) {
        return false;
      }
    }

    return true;
  }

  private static boolean hasEmptyName(final Path destination, final Path target) {
    final Path relative = destination.relativize(target);
    Path rebuilt = destination;
    for (final Path component : relative) {
      final String componentName = component.toString();
      rebuilt = rebuilt.resolve(componentName);
    }

    // the zip file system keeps an empty name, as in the entry a/\x, which corrupts its directory tree
    return !rebuilt.equals(target);
  }

  private static void rejectSymbolicLinks(final Path destination, final Path target, final String name) {
    final Path relative = destination.relativize(target);
    Path current = destination;
    for (final Path component : relative) {
      current = current.resolve(component);
      final boolean symbolicLink = Files.isSymbolicLink(current);
      if (symbolicLink) {
        final String message = "Zip entry %s uses a symbolic link in the destination!".formatted(name);
        throw new ZipEntryIntegrityException(message);
      }
    }
  }

  private static long writeZipEntry(
    final ZipInputStream zip,
    final Path target,
    final String name,
    final long maximumEntrySize,
    final long remainingTotalSize
  ) throws IOException {
    try (
      final OutputStream output = Files.newOutputStream(
        target,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE,
        LinkOption.NOFOLLOW_LINKS
      )
    ) {
      return copyZipEntry(zip, output, name, maximumEntrySize, remainingTotalSize);
    }
  }

  private static long copyZipEntry(
    final ZipInputStream zip,
    final OutputStream output,
    final String name,
    final long maximumEntrySize,
    final long remainingTotalSize
  ) throws IOException {
    long entrySize = 0;
    final byte[] buffer = new byte[COPY_BUFFER_SIZE];
    int read = zip.read(buffer);
    while (read != -1) {
      if (read > maximumEntrySize - entrySize) {
        final String message = "Zip entry %s exceeds the maximum size!".formatted(name);
        throw new ZipEntryIntegrityException(message);
      }
      if (read > remainingTotalSize - entrySize) {
        throw new ZipEntryIntegrityException("Total extracted size exceeds the limit!");
      }
      output.write(buffer, 0, read);
      entrySize += read;
      read = zip.read(buffer);
    }

    return entrySize;
  }

  @FunctionalInterface
  @VisibleForTesting
  interface FileMover {
    void move(Path source, Path target, CopyOption... options) throws IOException;
  }

  private record DownloadFields(PlatformFields platform, String url, @Nullable String hash) {}

  private record PlatformFields(Os os, Arch arch, Bits bits) {}
}
