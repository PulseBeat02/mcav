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
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.FileSystem;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
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

// Extracts the pinned, hash-checked CEF archive, refusing anything that could write outside the target: links, special
// files, names that leave it, and too many entries or bytes. Archive permissions are not copied, as the macOS archive
// makes everything world-writable. The library reads metadata such as a PAX header without a bound, which only the
// pinned hash makes acceptable.
final class ArchiveExtractor {

  private static final int MAXIMUM_ENTRIES = 10_000;

  private static final long MAXIMUM_TOTAL_BYTES = 2L * 1024 * 1024 * 1024;

  private static final Set<PosixFilePermission> EXECUTABLE = PosixFilePermissions.fromString("rwxr-xr-x");
  private static final Set<PosixFilePermission> REGULAR = PosixFilePermissions.fromString("rw-r--r--");
  private static final Set<PosixFilePermission> FOLDER = PosixFilePermissions.fromString("rwxr-xr-x");

  private static final Logger LOGGER = LoggerFactory.getLogger(ArchiveExtractor.class);

  private static final String NAME_NOT_ALLOWED = "Archive entry name %s is not allowed!";

  private static final int EXECUTE_BITS = 0111;

  private static final int BUFFER_BYTES = 64 * 1024;

  private static final String POSIX_ATTRIBUTE_VIEW = "posix";

  private static final String PARENT_FOLDER = "..";

  // CREATE_NEW refuses an existing link; zipfs rejects NOFOLLOW_LINKS here
  private static final Set<StandardOpenOption> NEW_FILE = Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);

  private final int maximumEntries;
  private final long maximumTotalBytes;

  ArchiveExtractor() {
    this(MAXIMUM_ENTRIES, MAXIMUM_TOTAL_BYTES);
  }

  @VisibleForTesting
  ArchiveExtractor(final int maximumEntries, final long maximumTotalBytes) {
    this.maximumEntries = maximumEntries;
    this.maximumTotalBytes = maximumTotalBytes;
  }

  void extract(final InputStream archive, final Path target) throws IOException {
    final PosixPermissions permissions = permissionsOf(target);
    this.extract(archive, target, permissions);
  }

  @VisibleForTesting
  void extract(final InputStream archive, final Path target, final PosixPermissions permissions) throws IOException {
    final Path root = target.toRealPath();
    final GzipCompressorInputStream gzip = new GzipCompressorInputStream(new NonClosingInputStream(archive));
    try (final TarArchiveInputStream tar = new TarArchiveInputStream(gzip)) {
      this.extractEntries(tar, root, permissions);
    }
  }

  private static PosixPermissions permissionsOf(final Path path) {
    final FileSystem fileSystem = path.getFileSystem();
    final boolean supportsPosix = fileSystem.supportedFileAttributeViews().contains(POSIX_ATTRIBUTE_VIEW);
    return supportsPosix ? PosixPermissions.SET : PosixPermissions.SKIPPED;
  }

  private void extractEntries(final TarArchiveInputStream tar, final Path root, final PosixPermissions permissions) throws IOException {
    int entries = 0;
    long written = 0L;
    TarArchiveEntry entry = tar.getNextEntry();
    while (entry != null) {
      entries++;
      if (entries > this.maximumEntries) {
        final String message = "The archive has more than %s entries!".formatted(this.maximumEntries);
        throw new IOException(message);
      }
      // the library counts links and special files as files, so their type is checked first
      if (this.isLinkOrSpecial(entry)) {
        final String message = "Archive entry %s is not a plain file or folder!".formatted(entry.getName());
        throw new IOException(message);
      }
      final Path destination = resolve(root, entry.getName());
      if (entry.isDirectory()) {
        createFolders(destination, permissions);
      } else {
        final long remaining = this.maximumTotalBytes - written;
        written += this.writeFile(tar, destination, remaining, permissions);
        this.setFileMode(destination, entry, permissions);
      }
      entry = tar.getNextEntry();
    }
  }

  private void setFileMode(final Path destination, final TarArchiveEntry entry, final PosixPermissions permissions) throws IOException {
    final boolean executable = (entry.getMode() & EXECUTE_BITS) != 0;
    if (permissions == PosixPermissions.SET) {
      Files.setPosixFilePermissions(destination, executable ? EXECUTABLE : REGULAR);
    }
  }

  private boolean isLinkOrSpecial(final TarArchiveEntry entry) {
    final boolean isLink = entry.isSymbolicLink() || entry.isLink();
    final boolean isDevice = entry.isCharacterDevice() || entry.isBlockDevice();
    return isLink || isDevice || entry.isFIFO();
  }

  // a folder entry such as ./ resolves to the root itself
  @VisibleForTesting
  static Path resolve(final Path root, final String name) throws IOException {
    if (name.isEmpty() || name.indexOf('\\') >= 0) {
      final String message = NAME_NOT_ALLOWED.formatted(name);
      throw new IOException(message);
    }

    final Path relative;
    try {
      relative = root.getFileSystem().getPath(name);
    } catch (final InvalidPathException exception) {
      final String message = NAME_NOT_ALLOWED.formatted(name);
      throw new IOException(message, exception);
    }
    // a Windows drive-relative name has a root without being absolute
    if (relative.getRoot() != null) {
      final String message = "Archive entry name %s is absolute!".formatted(name);
      throw new IOException(message);
    }

    Path current = root;
    for (final Path element : relative) {
      if (element.toString().equals(PARENT_FOLDER)) {
        final String message = "Archive entry name %s leaves the folder!".formatted(name);
        throw new IOException(message);
      }
      if (Files.isSymbolicLink(current)) {
        final String message = "Archive entry name %s passes a link!".formatted(name);
        throw new IOException(message);
      }
      current = current.resolve(element);
    }
    return current.normalize();
  }

  private long writeFile(final InputStream tar, final Path destination, final long remaining, final PosixPermissions permissions)
    throws IOException {
    final Path parent = Objects.requireNonNull(destination.getParent(), "An entry lies below the folder!");
    createFolders(parent, permissions);
    final byte[] buffer = new byte[BUFFER_BYTES];
    final FileAttribute<?>[] attributes = initialAttributes(permissions, REGULAR);
    try (
      final SeekableByteChannel channel = Files.newByteChannel(destination, NEW_FILE, attributes);
      final OutputStream output = Channels.newOutputStream(channel)
    ) {
      return this.copyAtMost(tar, output, buffer, remaining);
    }
  }

  private long copyAtMost(final InputStream tar, final OutputStream output, final byte[] buffer, final long remaining) throws IOException {
    long written = 0L;
    int count = tar.read(buffer);
    while (count >= 0) {
      written += count;
      if (written > remaining) {
        final String message = "The archive extracts to more than the allowed size!";
        throw new IOException(message);
      }
      output.write(buffer, 0, count);
      count = tar.read(buffer);
    }
    return written;
  }

  static void createFolders(final Path folder) throws IOException {
    final PosixPermissions permissions = permissionsOf(folder);
    createFolders(folder, permissions);
  }

  @VisibleForTesting
  static void createFolders(final Path folder, final PosixPermissions permissions) throws IOException {
    final List<Path> missing = new ArrayList<>();
    Path current = folder.toAbsolutePath();
    while (Files.notExists(current, LinkOption.NOFOLLOW_LINKS)) {
      missing.add(current);
      current = Objects.requireNonNull(current.getParent(), "The root of a file system exists!");
    }
    final FileAttribute<?>[] attributes = initialAttributes(permissions, FOLDER);
    Files.createDirectories(folder, attributes);

    if (permissions == PosixPermissions.SET) {
      for (final Path created : missing) {
        Files.setPosixFilePermissions(created, FOLDER);
      }
    }
  }

  // a failed extraction leaves nothing wider than its final permissions
  private static FileAttribute<?>[] initialAttributes(final PosixPermissions permissions, final Set<PosixFilePermission> mode) {
    if (permissions == PosixPermissions.SKIPPED) {
      return new FileAttribute<?>[0];
    }

    final FileAttribute<Set<PosixFilePermission>> attribute = PosixFilePermissions.asFileAttribute(mode);
    return new FileAttribute<?>[] { attribute };
  }

  // an installation made before MCAV set permissions itself may let its group replace the native code (rwxrwxr-x)
  static void tighten(final Path folder) throws IOException {
    final PosixPermissions permissions = permissionsOf(folder);
    if (permissions == PosixPermissions.SKIPPED || !Files.isDirectory(folder)) {
      return;
    }

    final TighteningVisitor visitor = new TighteningVisitor();
    Files.walkFileTree(folder, visitor);
  }

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
      LOGGER.warn("The group may still write {} of the browser's installation ({}).", path, exception.toString());
    }
  }

  // visible because a walk run as root never reaches visitFileFailed
  @VisibleForTesting
  static final class TighteningVisitor extends SimpleFileVisitor<Path> {

    @Override
    public FileVisitResult preVisitDirectory(final Path directory, final BasicFileAttributes attributes) {
      takeWriteAway(directory);

      return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFile(final Path file, final BasicFileAttributes attributes) {
      // a link has no permissions of its own; changing them would change its target
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

  private static final class NonClosingInputStream extends FilterInputStream {

    NonClosingInputStream(final InputStream input) {
      super(input);
    }

    @Override
    public void close() {}
  }
}
