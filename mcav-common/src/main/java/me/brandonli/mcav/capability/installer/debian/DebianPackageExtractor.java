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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.io.ByteStreams;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import me.brandonli.mcav.capability.installer.InstallationException;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.tukaani.xz.XZInputStream;

// Extracts the trees of a Debian package that the caller names, such as usr/lib, without dpkg, so it needs no
// administrator rights: a package is an ar archive whose data member is a tar archive, compressed with xz or gzip.
// zstd, which no Debian release before trixie uses, is refused. Every entry outside the trees is read and skipped. A
// file is executable when its entry is, and no file or folder is ever writable by anyone but its owner, whatever the
// umask. A symbolic link must stay inside the destination along a normalized relative path, so that no chain of links
// can lead out of it.
final class DebianPackageExtractor {

  private static final long MAXIMUM_ENTRY_SIZE = 512L * 1024L * 1024L;
  private static final long MAXIMUM_TOTAL_SIZE = 2L * 1024L * 1024L * 1024L;
  private static final int MAXIMUM_METADATA_SIZE = 64 * 1024;

  private static final byte[] AR_MAGIC = "!<arch>\n".getBytes(StandardCharsets.US_ASCII);
  private static final int AR_HEADER_LENGTH = 60;
  private static final int AR_NAME_LENGTH = 16;
  private static final int AR_SIZE_OFFSET = 48;
  private static final int AR_SIZE_LENGTH = 10;
  private static final int AR_ALIGNMENT = 2;
  private static final String AR_NAME_TERMINATOR = "/";

  private static final String DATA_MEMBER = "data.tar";
  private static final String XZ_SUFFIX = ".xz";
  private static final String GZIP_SUFFIX = ".gz";
  private static final String ZSTD_SUFFIX = ".zst";

  // a mebibyte is 1024 kibibytes; Debian compresses with xz -6, whose dictionary needs 9 MiB to decompress
  private static final int XZ_MEMORY_LIMIT_KIBIBYTES = 64 * 1024;

  private static final int TAR_BLOCK_SIZE = 512;
  private static final int TAR_NAME_LENGTH = 100;
  private static final int TAR_MODE_OFFSET = 100;
  private static final int TAR_MODE_LENGTH = 8;
  private static final int TAR_SIZE_OFFSET = 124;
  private static final int TAR_SIZE_LENGTH = 12;
  private static final int TAR_CHECKSUM_OFFSET = 148;
  private static final int TAR_CHECKSUM_LENGTH = 8;
  private static final int TAR_TYPE_OFFSET = 156;
  private static final int TAR_LINK_NAME_OFFSET = 157;
  private static final int TAR_MAGIC_OFFSET = 257;
  private static final int TAR_PREFIX_OFFSET = 345;
  private static final int TAR_PREFIX_LENGTH = 155;

  // only POSIX ustar headers have a name prefix; the GNU format, which dpkg-deb writes, keeps other fields there
  private static final byte[] USTAR_MAGIC = "ustar\0".getBytes(StandardCharsets.US_ASCII);

  private static final byte CHECKSUM_PLACEHOLDER = ' ';
  private static final int UNSIGNED_BYTE_MASK = 0xFF;

  private static final byte OLD_REGULAR_FILE = 0;
  private static final byte REGULAR_FILE = '0';
  private static final byte SYMBOLIC_LINK = '2';
  private static final byte DIRECTORY = '5';
  private static final byte GNU_LONG_NAME = 'L';
  private static final byte GNU_LONG_LINK_NAME = 'K';
  private static final byte PAX_HEADER = 'x';

  private static final char NUL = '\0';
  private static final char PAX_LENGTH_SEPARATOR = ' ';
  private static final char PAX_KEY_SEPARATOR = '=';
  private static final char PAX_RECORD_END = '\n';
  private static final String PAX_PATH = "path";
  private static final String PAX_LINK_PATH = "linkpath";
  private static final String PATH_SEPARATOR = "/";
  private static final String PARENT_FOLDER = "..";

  private static final Pattern DECIMAL = Pattern.compile("\\d{1,10}");
  private static final Pattern OCTAL = Pattern.compile("[0-7]{1,12}");
  private static final int OCTAL_RADIX = 8;

  private static final int EXECUTE_BITS = 0111;
  private static final Set<PosixFilePermission> EXECUTABLE = PosixFilePermissions.fromString("rwxr-xr-x");
  private static final Set<PosixFilePermission> REGULAR = PosixFilePermissions.fromString("rw-r--r--");
  private static final Set<PosixFilePermission> FOLDER = PosixFilePermissions.fromString("rwxr-xr-x");
  private static final String POSIX_VIEW = "posix";

  private static final Set<StandardOpenOption> NEW_FILE = Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);

  private final Path destination;
  private final List<String> trees;
  private final boolean supportsPosix;
  private final long maximumEntrySize;

  private long remainingSize;
  private @Nullable String pendingName;
  private @Nullable String pendingLinkName;

  private DebianPackageExtractor(
    final Path destination,
    final List<String> trees,
    final long maximumEntrySize,
    final long maximumTotalSize
  ) {
    final FileSystem fileSystem = destination.getFileSystem();
    final Set<String> views = fileSystem.supportedFileAttributeViews();
    this.destination = destination;
    this.trees = trees;
    this.supportsPosix = views.contains(POSIX_VIEW);
    this.maximumEntrySize = maximumEntrySize;
    this.remainingSize = maximumTotalSize;
  }

  static void extract(final Path archive, final Path destination, final List<String> trees) {
    extract(archive, destination, trees, MAXIMUM_ENTRY_SIZE, MAXIMUM_TOTAL_SIZE);
  }

  @VisibleForTesting
  static void extract(
    final Path archive,
    final Path destination,
    final List<String> trees,
    final long maximumEntrySize,
    final long maximumTotalSize
  ) {
    final Path absolute = destination.toAbsolutePath();
    final Path normalized = absolute.normalize();
    final DebianPackageExtractor extractor = new DebianPackageExtractor(normalized, trees, maximumEntrySize, maximumTotalSize);
    extractor.extractPackage(archive);
  }

  private void extractPackage(final Path archive) {
    try (final InputStream file = Files.newInputStream(archive); final InputStream buffered = new BufferedInputStream(file)) {
      this.createFolders(this.destination);
      this.extractDataMember(buffered);
    } catch (final IOException exception) {
      final String message = "Debian package %s could not be extracted!".formatted(archive);
      throw new InstallationException(message, exception);
    }
  }

  private void extractDataMember(final InputStream archive) throws IOException {
    final byte[] magic = archive.readNBytes(AR_MAGIC.length);
    if (!Arrays.equals(magic, AR_MAGIC)) {
      throw new IOException("The file is not an ar archive!");
    }

    byte[] header = archive.readNBytes(AR_HEADER_LENGTH);
    while (header.length == AR_HEADER_LENGTH) {
      final String name = this.readMemberName(header);
      final long size = this.readMemberSize(header);
      if (name.startsWith(DATA_MEMBER)) {
        this.extractData(name, archive, size);
        return;
      }

      final long padding = size % AR_ALIGNMENT;
      archive.skipNBytes(size + padding);
      header = archive.readNBytes(AR_HEADER_LENGTH);
    }

    throw new IOException("The package ends before its data member!");
  }

  private String readMemberName(final byte[] header) {
    final String field = new String(header, 0, AR_NAME_LENGTH, StandardCharsets.US_ASCII);
    final String name = field.strip();
    if (name.endsWith(AR_NAME_TERMINATOR)) {
      return name.substring(0, name.length() - 1);
    }

    return name;
  }

  private long readMemberSize(final byte[] header) throws IOException {
    final String field = new String(header, AR_SIZE_OFFSET, AR_SIZE_LENGTH, StandardCharsets.US_ASCII);
    final String size = field.strip();
    final boolean decimal = DECIMAL.matcher(size).matches();
    if (!decimal) {
      final String message = "Ar member size %s is not a number!".formatted(size);
      throw new IOException(message);
    }

    return Long.parseLong(size);
  }

  private void extractData(final String name, final InputStream archive, final long size) throws IOException {
    final InputStream member = ByteStreams.limit(archive, size);
    try (final InputStream tar = this.decompress(name, member)) {
      this.extractEntries(tar);
    }
  }

  private InputStream decompress(final String name, final InputStream member) throws IOException {
    if (name.equals(DATA_MEMBER + XZ_SUFFIX)) {
      return new XZInputStream(member, XZ_MEMORY_LIMIT_KIBIBYTES);
    }

    if (name.equals(DATA_MEMBER + GZIP_SUFFIX)) {
      return new GZIPInputStream(member);
    }

    if (name.equals(DATA_MEMBER)) {
      return member;
    }

    if (name.equals(DATA_MEMBER + ZSTD_SUFFIX)) {
      throw new IOException("The data member is compressed with zstd, which is not supported!");
    }

    final String message = "Data member %s has an unknown compression!".formatted(name);
    throw new IOException(message);
  }

  private void extractEntries(final InputStream tar) throws IOException {
    byte[] header = this.readBlock(tar);
    while (!this.isEndOfArchive(header)) {
      this.extractEntry(tar, header);
      header = this.readBlock(tar);
    }
  }

  private byte[] readBlock(final InputStream tar) throws IOException {
    final byte[] block = tar.readNBytes(TAR_BLOCK_SIZE);
    if (block.length != TAR_BLOCK_SIZE) {
      throw new IOException("The data archive ends inside a header!");
    }

    return block;
  }

  private boolean isEndOfArchive(final byte[] header) {
    for (final byte value : header) {
      if (value != 0) {
        return false;
      }
    }

    return true;
  }

  private void extractEntry(final InputStream tar, final byte[] header) throws IOException {
    this.requireValidChecksum(header);
    final long size = this.readOctal(header, TAR_SIZE_OFFSET, TAR_SIZE_LENGTH);
    this.reserve(size);

    final InputStream data = ByteStreams.limit(tar, size);
    final byte type = header[TAR_TYPE_OFFSET];
    switch (type) {
      case GNU_LONG_NAME -> this.pendingName = this.readMetadataName(data, size);
      case GNU_LONG_LINK_NAME -> this.pendingLinkName = this.readMetadataName(data, size);
      case PAX_HEADER -> this.readPaxHeader(data, size);
      default -> this.extractFile(header, type, data);
    }

    ByteStreams.exhaust(data);
    final long padding = (TAR_BLOCK_SIZE - (size % TAR_BLOCK_SIZE)) % TAR_BLOCK_SIZE;
    tar.skipNBytes(padding);
  }

  private void requireValidChecksum(final byte[] header) throws IOException {
    final long stored = this.readOctal(header, TAR_CHECKSUM_OFFSET, TAR_CHECKSUM_LENGTH);
    long computed = 0;
    for (int index = 0; index < header.length; index++) {
      final boolean checksumField = index >= TAR_CHECKSUM_OFFSET && index < TAR_CHECKSUM_OFFSET + TAR_CHECKSUM_LENGTH;
      final byte value = checksumField ? CHECKSUM_PLACEHOLDER : header[index];
      computed += value & UNSIGNED_BYTE_MASK;
    }

    if (computed != stored) {
      final String message = "Tar header checksum %s does not match the computed %s!".formatted(stored, computed);
      throw new IOException(message);
    }
  }

  private long readOctal(final byte[] header, final int offset, final int length) throws IOException {
    final String field = this.readString(header, offset, length);
    final String number = field.strip();
    final boolean octal = OCTAL.matcher(number).matches();
    if (!octal) {
      final String message = "Tar header field %s is not an octal number!".formatted(number);
      throw new IOException(message);
    }

    return Long.parseLong(number, OCTAL_RADIX);
  }

  private String readString(final byte[] bytes, final int offset, final int length) {
    int end = offset;
    while (end < offset + length && bytes[end] != NUL) {
      end++;
    }

    return new String(bytes, offset, end - offset, StandardCharsets.UTF_8);
  }

  private void reserve(final long size) throws IOException {
    if (size > this.maximumEntrySize) {
      final String message = "Tar entry of %s bytes exceeds the maximum entry size!".formatted(size);
      throw new IOException(message);
    }

    if (size > this.remainingSize) {
      throw new IOException("The data archive exceeds the maximum total size!");
    }

    this.remainingSize -= size;
  }

  private String readMetadataName(final InputStream data, final long size) throws IOException {
    final byte[] metadata = this.readMetadata(data, size);
    return this.readString(metadata, 0, metadata.length);
  }

  private byte[] readMetadata(final InputStream data, final long size) throws IOException {
    if (size > MAXIMUM_METADATA_SIZE) {
      final String message = "Tar metadata of %s bytes exceeds the maximum metadata size!".formatted(size);
      throw new IOException(message);
    }

    final byte[] metadata = data.readNBytes((int) size);
    if (metadata.length != size) {
      throw new IOException("The data archive ends inside metadata!");
    }

    return metadata;
  }

  // Reads the path and link path of a pax extended header, a list of records "<length> <key>=<value>\n" whose length
  // counts the bytes of the whole record. Decoding as ISO-8859-1 keeps one character per byte, so lengths stay offsets.
  private void readPaxHeader(final InputStream data, final long size) throws IOException {
    final byte[] metadata = this.readMetadata(data, size);
    final String records = new String(metadata, StandardCharsets.ISO_8859_1);
    int start = 0;
    while (start < records.length()) {
      final int end = this.findPaxRecordEnd(records, start);
      this.applyPaxRecord(records, start, end);
      start = end;
    }
  }

  private int findPaxRecordEnd(final String records, final int start) throws IOException {
    final int separator = records.indexOf(PAX_LENGTH_SEPARATOR, start);
    final String length = separator < 0 ? "" : records.substring(start, separator);
    final boolean decimal = DECIMAL.matcher(length).matches();
    if (!decimal) {
      throw new IOException("A pax record has no length!");
    }

    final long end = start + Long.parseLong(length);
    if (end <= separator || end > records.length() || records.charAt((int) end - 1) != PAX_RECORD_END) {
      throw new IOException("A pax record has a wrong length!");
    }

    return (int) end;
  }

  private void applyPaxRecord(final String records, final int start, final int end) throws IOException {
    final int separator = records.indexOf(PAX_LENGTH_SEPARATOR, start);
    final String record = records.substring(separator + 1, end - 1);
    final int keyEnd = record.indexOf(PAX_KEY_SEPARATOR);
    if (keyEnd < 0) {
      throw new IOException("A pax record has no value!");
    }

    final String key = record.substring(0, keyEnd);
    final String rawValue = record.substring(keyEnd + 1);
    final byte[] valueBytes = rawValue.getBytes(StandardCharsets.ISO_8859_1);
    final String value = new String(valueBytes, StandardCharsets.UTF_8);
    if (key.equals(PAX_PATH)) {
      this.pendingName = value;
    }

    if (key.equals(PAX_LINK_PATH)) {
      this.pendingLinkName = value;
    }
  }

  private void extractFile(final byte[] header, final byte type, final InputStream data) throws IOException {
    final String name = this.takeName(header);
    final String linkName = this.takeLinkName(header);
    final Path target = this.resolve(name);
    final boolean extracted = this.isInExtractedTree(target);
    if (!extracted) {
      return;
    }

    this.rejectSymbolicLinks(target, name);
    switch (type) {
      case OLD_REGULAR_FILE, REGULAR_FILE -> this.writeFile(target, header, data);
      case DIRECTORY -> this.createFolders(target);
      case SYMBOLIC_LINK -> this.createSymbolicLink(target, linkName, name);
      default -> {
        final String message = "Tar entry %s has the unsupported type %s!".formatted(name, (char) type);
        throw new IOException(message);
      }
    }
  }

  private String takeName(final byte[] header) {
    final @Nullable String pending = this.pendingName;
    this.pendingName = null;
    if (pending != null) {
      return pending;
    }

    final String name = this.readString(header, 0, TAR_NAME_LENGTH);
    final int magicEnd = TAR_MAGIC_OFFSET + USTAR_MAGIC.length;
    final boolean ustar = Arrays.equals(header, TAR_MAGIC_OFFSET, magicEnd, USTAR_MAGIC, 0, USTAR_MAGIC.length);
    final String prefix = ustar ? this.readString(header, TAR_PREFIX_OFFSET, TAR_PREFIX_LENGTH) : "";
    if (prefix.isEmpty()) {
      return name;
    }

    return prefix + PATH_SEPARATOR + name;
  }

  private String takeLinkName(final byte[] header) {
    final @Nullable String pending = this.pendingLinkName;
    this.pendingLinkName = null;
    if (pending != null) {
      return pending;
    }

    return this.readString(header, TAR_LINK_NAME_OFFSET, TAR_NAME_LENGTH);
  }

  // Resolves an entry name against the destination. Names that escape it end up outside every extracted tree.
  private Path resolve(final String name) throws IOException {
    final Path entry = this.toPath(name);
    final Path resolved = this.destination.resolve(entry);
    return resolved.normalize();
  }

  private Path toPath(final String name) throws IOException {
    final FileSystem fileSystem = this.destination.getFileSystem();
    try {
      return fileSystem.getPath(name);
    } catch (final InvalidPathException exception) {
      final String message = "Tar entry name %s is not a path of this system!".formatted(name);
      throw new IOException(message, exception);
    }
  }

  private boolean isInExtractedTree(final Path target) {
    for (final String tree : this.trees) {
      final Path root = this.destination.resolve(tree);
      if (target.startsWith(root)) {
        return true;
      }
    }

    return false;
  }

  private void rejectSymbolicLinks(final Path target, final String name) throws IOException {
    final Path relative = this.destination.relativize(target);
    Path current = this.destination;
    for (final Path component : relative) {
      current = current.resolve(component);
      if (Files.isSymbolicLink(current)) {
        final String message = "Tar entry %s goes through or replaces a symbolic link!".formatted(name);
        throw new IOException(message);
      }
    }
  }

  // a later entry of the same name replaces an earlier one
  private void writeFile(final Path target, final byte[] header, final InputStream data) throws IOException {
    final long mode = this.readOctal(header, TAR_MODE_OFFSET, TAR_MODE_LENGTH);
    final boolean executable = (mode & EXECUTE_BITS) != 0;
    final Path parent = this.getParent(target);
    this.createFolders(parent);
    Files.deleteIfExists(target);
    this.writeNewFile(target, data);
    this.setPermissions(target, executable ? EXECUTABLE : REGULAR);
  }

  private void writeNewFile(final Path target, final InputStream data) throws IOException {
    final FileAttribute<?>[] permissions = this.initialPermissions(REGULAR);
    try (
      final SeekableByteChannel channel = Files.newByteChannel(target, NEW_FILE, permissions);
      final OutputStream output = Channels.newOutputStream(channel)
    ) {
      data.transferTo(output);
    }
  }

  private void setPermissions(final Path file, final Set<PosixFilePermission> permissions) throws IOException {
    if (!this.supportsPosix) {
      return;
    }

    Files.setPosixFilePermissions(file, permissions);
  }

  private void createFolders(final Path folder) throws IOException {
    final FileAttribute<?>[] permissions = this.initialPermissions(FOLDER);
    Files.createDirectories(folder, permissions);
  }

  private FileAttribute<?>[] initialPermissions(final Set<PosixFilePermission> permissions) {
    if (!this.supportsPosix) {
      return new FileAttribute<?>[0];
    }

    final FileAttribute<Set<PosixFilePermission>> attribute = PosixFilePermissions.asFileAttribute(permissions);
    return new FileAttribute<?>[] { attribute };
  }

  private void createSymbolicLink(final Path link, final String linkName, final String name) throws IOException {
    final Path linkTarget = this.toPath(linkName);
    final Path parent = this.getParent(link);
    // from the destination, since a link that climbs above it could come back through a link on the way to it
    final Path relativeParent = this.destination.relativize(parent);
    final Path pointed = relativeParent.resolve(linkTarget).normalize();
    if (linkName.isEmpty() || linkTarget.isAbsolute() || pointed.startsWith(PARENT_FOLDER)) {
      final String message = "Symbolic link %s points to %s outside the destination!".formatted(name, linkName);
      throw new IOException(message);
    }

    // the lexical check misses a link that climbs after it descends, which could leave through another link
    final Path normalizedTarget = linkTarget.normalize();
    if (!linkTarget.equals(normalizedTarget)) {
      final String message = "Symbolic link %s points to %s, which is not a normalized path!".formatted(name, linkName);
      throw new IOException(message);
    }

    this.createFolders(parent);
    Files.createSymbolicLink(link, linkTarget);
  }

  private Path getParent(final Path target) {
    final @Nullable Path parent = target.getParent();
    return Objects.requireNonNull(parent, "An entry in an extracted tree always has a parent!");
  }
}
