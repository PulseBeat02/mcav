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
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Objects;
import java.util.Set;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

/**
 * Extracts the gzip-compressed tar archive of the CEF natives, refusing anything that could write outside the target
 * folder.
 *
 * <p>The archive is verified against its pinned SHA-256 hash before it gets here, so this is defense in depth: only
 * plain files and folders are extracted, every name is resolved inside the target and checked after normalization,
 * links and special files are refused, and the number of entries and the bytes written are bounded. The permissions
 * of the archive are not copied, because the macOS archive marks everything as writable by every user; files are
 * readable by everyone and writable by the owner only, and executable where the archive marks them so.
 */
final class ArchiveExtractor {

  /**
   * The most entries an archive may have.
   */
  static final int MAX_ENTRIES = 10_000;

  /**
   * The most bytes an archive may extract to.
   */
  static final long MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024;

  private static final Set<PosixFilePermission> EXECUTABLE = PosixFilePermissions.fromString("rwxr-xr-x");
  private static final Set<PosixFilePermission> REGULAR = PosixFilePermissions.fromString("rw-r--r--");
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
    final boolean posix = fileSystem.supportedFileAttributeViews().contains("posix");
    this.extract(archive, target, posix);
  }

  /**
   * Extracts an archive into an empty folder, setting the permissions of the files where the file system has POSIX
   * permissions.
   *
   * @param archive the gzip-compressed tar archive; it is not closed
   * @param target  the folder, which must exist
   * @param posix   whether the permissions of the files are set
   * @throws IOException if the archive is malformed, breaks a rule, or cannot be written
   */
  @VisibleForTesting
  void extract(final InputStream archive, final Path target, final boolean posix) throws IOException {
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
          Files.createDirectories(destination);
        } else {
          final long remaining = this.maxTotalBytes - written;
          written += writeFile(tar, destination, remaining);
          final boolean executable = (entry.getMode() & EXECUTE_BITS) != 0;
          if (posix) {
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
  @VisibleForTesting
  static boolean isLinkOrSpecial(final TarArchiveEntry entry) {
    final boolean link = entry.isSymbolicLink() || entry.isLink();
    final boolean device = entry.isCharacterDevice() || entry.isBlockDevice();
    return link || device || entry.isFIFO();
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
      // refuses a NUL everywhere, and on Windows the characters its file names cannot hold
      relative = root.getFileSystem().getPath(name);
    } catch (final InvalidPathException exception) {
      throw new IOException("The archive entry name is not allowed: " + name, exception);
    }
    // an absolute name has a root on every system, and so do the drive-relative names of Windows
    if (relative.getRoot() != null) {
      throw new IOException("The archive entry name is absolute: " + name);
    }
    // without a root and without "..", the name cannot leave the folder; every folder on the way must be a real one
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

  private static long writeFile(final InputStream tar, final Path destination, final long remaining) throws IOException {
    // every entry lies below the root, so it has a parent
    final Path parent = Objects.requireNonNull(destination.getParent(), "An entry lies below the folder");
    Files.createDirectories(parent);
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
   * Keeps the archive stream of the caller open when the tar stream is closed.
   */
  private static final class NonClosingInputStream extends java.io.FilterInputStream {

    NonClosingInputStream(final InputStream in) {
      super(in);
    }

    @Override
    public void close() {
      // the caller closes the stream
    }
  }
}
