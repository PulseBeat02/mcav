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
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileSystem;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Extracts the gzip-compressed tar archive of the CEF natives, refusing anything that could write outside the target
 * folder.
 *
 * <p>The archive is verified against its pinned SHA-256 hash before it gets here, so this is defense in depth: only
 * plain files and folders are extracted, every name is resolved inside the target and checked after normalization,
 * links and special files are refused, and the number of entries and the bytes written are bounded. The permissions
 * of the archive are not copied, because the macOS archive marks everything as writable by every user; files are
 * readable by everyone and writable by the owner only, and executable where the archive marks them so, and so are the
 * folders, whatever the umask of the server: a folder its group may write lets the group replace the native code.
 *
 * <p>The bounds count the entries the archive library hands over. The library reads the metadata of an entry, such as
 * a GNU long name or a PAX header, whole and without a bound before that, so a crafted archive could exhaust the heap
 * there. That is acceptable only because of the pinned hash: an archive that passes it is the published build of CEF,
 * whose native code runs anyway.
 */
final class ArchiveExtractor {

  /**
   * The most entries an archive may have.
   */
  private static final int MAX_ENTRIES = 10_000;

  /**
   * The most bytes an archive may extract to.
   */
  private static final long MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024;

  private static final Set<PosixFilePermission> EXECUTABLE = PosixFilePermissions.fromString("rwxr-xr-x");
  private static final Set<PosixFilePermission> REGULAR = PosixFilePermissions.fromString("rw-r--r--");
  private static final Set<PosixFilePermission> FOLDER = PosixFilePermissions.fromString("rwxr-xr-x");
  private static final Logger LOGGER = LoggerFactory.getLogger(ArchiveExtractor.class);
  private static final String NOT_TIGHTENED = "The group may still write {} of the browser's installation: {}";
  private static final int EXECUTE_BITS = 0111;
  private static final int BUFFER_BYTES = 64 * 1024;

  private final int maxEntries;
  private final long maxTotalBytes;

  /**
   * Constructs an extractor with the default limits.
   */
  ArchiveExtractor() {
    this(MAX_ENTRIES, MAX_TOTAL_BYTES);
  }

  /**
   * Constructs an extractor with other limits, so tests can reach them with small archives.
   *
   * @param maxEntries    the most entries an archive may have
   * @param maxTotalBytes the most bytes an archive may extract to
   */
  @VisibleForTesting
  ArchiveExtractor(final int maxEntries, final long maxTotalBytes) {
    this.maxEntries = maxEntries;
    this.maxTotalBytes = maxTotalBytes;
  }

  /**
   * Extracts an archive into an empty folder.
   *
   * @param archive the gzip-compressed tar archive; it is not closed
   * @param target  the folder, which must exist
   * @throws IOException if the archive is malformed, breaks a rule, or cannot be written
   */
  void extract(final InputStream archive, final Path target) throws IOException {
    final FileSystem fileSystem = target.getFileSystem();
    final boolean supportsPosix = fileSystem.supportedFileAttributeViews().contains("posix");
    this.extract(archive, target, supportsPosix);
  }

  /**
   * Extracts an archive into an empty folder, setting the permissions of the files where the file system has POSIX
   * permissions.
   *
   * @param archive       the gzip-compressed tar archive; it is not closed
   * @param target        the folder, which must exist
   * @param supportsPosix whether the permissions of the files are set
   * @throws IOException if the archive is malformed, breaks a rule, or cannot be written
   */
  @VisibleForTesting
  void extract(final InputStream archive, final Path target, final boolean supportsPosix) throws IOException {
    final Path root = target.toRealPath();
    final GzipCompressorInputStream gzip = new GzipCompressorInputStream(new NonClosingInputStream(archive));
    try (final TarArchiveInputStream tar = new TarArchiveInputStream(gzip)) {
      int entries = 0;
      long written = 0L;
      TarArchiveEntry entry = tar.getNextEntry();
      while (entry != null) {
        entries++;
        if (entries > this.maxEntries) {
          throw new IOException("The archive has more than " + this.maxEntries + " entries");
        }
        // the archive library counts links and special files as files, so they are refused by their type first
        if (isLinkOrSpecial(entry)) {
          throw new IOException("The archive entry " + entry.getName() + " is not a plain file or folder");
        }
        final Path destination = resolve(root, entry.getName());
        if (entry.isDirectory()) {
          createFolders(destination, supportsPosix);
        } else {
          final long remaining = this.maxTotalBytes - written;
          written += writeFile(tar, destination, remaining, supportsPosix);
          final boolean executable = (entry.getMode() & EXECUTE_BITS) != 0;
          if (supportsPosix) {
            Files.setPosixFilePermissions(destination, executable ? EXECUTABLE : REGULAR);
          }
        }
        entry = tar.getNextEntry();
      }
    }
  }

  /**
   * Checks whether an entry is a link or a special file rather than a plain file or folder.
   *
   * @param entry the entry
   * @return true for symbolic and hard links, devices and named pipes
   */
  private static boolean isLinkOrSpecial(final TarArchiveEntry entry) {
    final boolean isLink = entry.isSymbolicLink() || entry.isLink();
    final boolean isDevice = entry.isCharacterDevice() || entry.isBlockDevice();
    return isLink || isDevice || entry.isFIFO();
  }

  /**
   * Resolves the name of an entry inside the root, refusing names that are absolute, empty, climb out of the root, or
   * cannot be represented by the file system. A folder entry such as {@code ./} resolves to the root itself.
   *
   * @param root the real path of the target folder
   * @param name the name of the entry
   * @return the path inside the root
   * @throws IOException if the name is not allowed
   */
  @VisibleForTesting
  static Path resolve(final Path root, final String name) throws IOException {
    if (name.isEmpty() || name.indexOf('\\') >= 0) {
      throw new IOException("The archive entry name is not allowed: " + name);
    }
    final Path relative;
    try {
      relative = root.getFileSystem().getPath(name);
    } catch (final InvalidPathException exception) {
      throw new IOException("The archive entry name is not allowed: " + name, exception);
    }
    if (relative.getRoot() != null) {
      throw new IOException("The archive entry name is absolute: " + name);
    }
    Path current = root;
    for (final Path element : relative) {
      if (element.toString().equals("..")) {
        throw new IOException("The archive entry name leaves the folder: " + name);
      }
      if (Files.isSymbolicLink(current)) {
        throw new IOException("The archive entry name passes a link: " + name);
      }
      current = current.resolve(element);
    }
    return current.normalize();
  }

  private static long writeFile(final InputStream tar, final Path destination, final long remaining, final boolean supportsPosix)
    throws IOException {
    final Path parent = Objects.requireNonNull(destination.getParent(), "An entry lies below the folder");
    createFolders(parent, supportsPosix);
    long written = 0L;
    final byte[] buffer = new byte[BUFFER_BYTES];
    try (
      final OutputStream out = Files.newOutputStream(
        destination,
        StandardOpenOption.CREATE_NEW,
        StandardOpenOption.WRITE,
        LinkOption.NOFOLLOW_LINKS
      )
    ) {
      int count = tar.read(buffer);
      while (count >= 0) {
        written += count;
        if (written > remaining) {
          throw new IOException("The archive extracts to more than the allowed size");
        }
        out.write(buffer, 0, count);
        count = tar.read(buffer);
      }
    }
    return written;
  }

  /**
   * Creates a folder and the missing folders above it, each readable by everyone and writable by its owner only where
   * the file system has POSIX permissions, whatever the umask of the server.
   *
   * @param folder the folder
   * @throws IOException if a folder cannot be created or its permissions cannot be set
   */
  static void createFolders(final Path folder) throws IOException {
    final FileSystem fileSystem = folder.getFileSystem();
    createFolders(folder, fileSystem.supportedFileAttributeViews().contains("posix"));
  }

  /**
   * Creates a folder and the missing folders above it, setting the permissions of each new one if asked to.
   *
   * @param folder        the folder
   * @param supportsPosix whether the permissions of the new folders are set
   * @throws IOException if a folder cannot be created or its permissions cannot be set
   */
  @VisibleForTesting
  static void createFolders(final Path folder, final boolean supportsPosix) throws IOException {
    final List<Path> missing = new ArrayList<>();
    Path current = folder.toAbsolutePath();
    while (Files.notExists(current, LinkOption.NOFOLLOW_LINKS)) {
      missing.add(current);
      current = Objects.requireNonNull(current.getParent(), "The root of a file system exists");
    }
    Files.createDirectories(folder);
    if (supportsPosix) {
      for (final Path created : missing) {
        Files.setPosixFilePermissions(created, FOLDER);
      }
    }
  }

  /**
   * Takes the write permission of the group and of everyone else away from a folder and everything in it, where the
   * file system has POSIX permissions: an installation made before MCAV set the permissions itself has the ones the
   * umask of its server left, such as {@code rwxrwxr-x}, which let the group replace the native code. A path whose
   * permissions cannot be changed is logged and left as it is.
   *
   * @param folder the folder
   * @throws IOException if the folder cannot be walked
   */
  static void tighten(final Path folder) throws IOException {
    final FileSystem fileSystem = folder.getFileSystem();
    if (!fileSystem.supportedFileAttributeViews().contains("posix") || !Files.isDirectory(folder)) {
      return;
    }
    Files.walkFileTree(
      folder,
      new SimpleFileVisitor<>() {
        @Override
        public FileVisitResult preVisitDirectory(final Path directory, final BasicFileAttributes attributes) {
          takeWriteAway(directory);
          return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(final Path file, final BasicFileAttributes attributes) {
          if (!attributes.isSymbolicLink()) {
            takeWriteAway(file);
          }
          return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(final Path file, final IOException exception) {
          return FileVisitResult.CONTINUE;
        }
      }
    );
  }

  /**
   * Takes the write permission of the group and of everyone else away from one file or folder.
   *
   * @param path the file or folder
   */
  @VisibleForTesting
  static void takeWriteAway(final Path path) {
    try {
      final Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
      final boolean groupMayWrite = permissions.remove(PosixFilePermission.GROUP_WRITE);
      final boolean othersMayWrite = permissions.remove(PosixFilePermission.OTHERS_WRITE);
      if (groupMayWrite || othersMayWrite) {
        Files.setPosixFilePermissions(path, permissions);
      }
    } catch (final IOException exception) {
      LOGGER.warn(NOT_TIGHTENED, path, exception.toString());
    }
  }

  /**
   * Keeps the archive stream of the caller open when the tar stream is closed.
   */
  private static final class NonClosingInputStream extends FilterInputStream {

    NonClosingInputStream(final InputStream in) {
      super(in);
    }

    @Override
    public void close() {}
  }
}
