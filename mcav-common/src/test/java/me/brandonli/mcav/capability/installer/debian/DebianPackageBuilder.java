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

import com.google.common.base.Strings;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import org.tukaani.xz.LZMA2Options;
import org.tukaani.xz.XZOutputStream;

// Builds Debian packages in memory the way dpkg-deb lays them out: an ar archive of debian-binary, a control member
// and a data member holding a tar archive. Tests build their fixtures with it instead of downloading real packages.
final class DebianPackageBuilder {

  private static final byte[] AR_MAGIC = "!<arch>\n".getBytes(StandardCharsets.US_ASCII);
  private static final String AR_MODIFIED = "0";
  private static final String AR_OWNER = "0";
  private static final String AR_MODE = "100644";
  private static final byte AR_PADDING = '\n';
  private static final int AR_ALIGNMENT = 2;

  private static final String BINARY_MEMBER = "debian-binary";
  private static final byte[] BINARY_VERSION = "2.0\n".getBytes(StandardCharsets.US_ASCII);
  private static final String CONTROL_MEMBER = "control.tar.xz";
  // an odd length, so the member after it starts behind a padding byte
  private static final byte[] CONTROL = "control".getBytes(StandardCharsets.US_ASCII);

  private static final int BLOCK_SIZE = 512;
  private static final int NAME_LENGTH = 100;
  private static final int MODE_OFFSET = 100;
  private static final int OWNER_OFFSET = 108;
  private static final int GROUP_OFFSET = 116;
  private static final int SIZE_OFFSET = 124;
  private static final int MODIFIED_OFFSET = 136;
  private static final int CHECKSUM_OFFSET = 148;
  private static final int CHECKSUM_LENGTH = 8;
  private static final int TYPE_OFFSET = 156;
  private static final int LINK_NAME_OFFSET = 157;
  private static final int MAGIC_OFFSET = 257;
  private static final int PREFIX_OFFSET = 345;
  private static final int PREFIX_LENGTH = 155;
  private static final int END_BLOCKS = 2;

  private static final byte CHECKSUM_PLACEHOLDER = ' ';
  private static final int UNSIGNED_BYTE_MASK = 0xFF;

  private static final String FILE_MODE = "0000644";
  private static final String EXECUTABLE_MODE = "0000755";
  private static final int NUMBER_DIGITS = 7;
  private static final int SIZE_DIGITS = 11;
  private static final int CHECKSUM_DIGITS = 6;
  private static final String CHECKSUM_TERMINATOR = "\0 ";
  private static final char OCTAL_PADDING = '0';
  private static final byte[] USTAR_MAGIC = ("ustar\0" + "00").getBytes(StandardCharsets.US_ASCII);
  private static final byte[] GNU_MAGIC = "ustar  \0".getBytes(StandardCharsets.US_ASCII);

  private static final byte REGULAR_FILE = '0';
  private static final byte HARD_LINK = '1';
  private static final byte SYMBOLIC_LINK = '2';
  private static final byte DIRECTORY = '5';
  private static final byte GNU_LONG_NAME = 'L';
  private static final byte GNU_LONG_LINK_NAME = 'K';
  private static final byte PAX_HEADER = 'x';

  private static final String GNU_LONG_NAME_ENTRY = "././@LongLink";
  private static final String PAX_ENTRY = "./PaxHeaders/entry";
  private static final String PAX_PATH_KEY = "path=";
  private static final String PAX_LINK_PATH_KEY = "linkpath=";
  private static final String PAX_IGNORED_RECORD = "mtime=0";
  private static final String PATH_SEPARATOR = "/";

  private static final byte[] NO_DATA = new byte[0];

  private final List<Entry> entries;

  private String dataMember;
  private LongNames longNames;
  private boolean corruptChecksum;

  DebianPackageBuilder() {
    this.entries = new ArrayList<>();
    this.dataMember = "data.tar.xz";
    this.longNames = LongNames.GNU;
    this.corruptChecksum = false;
  }

  DebianPackageBuilder file(final String name, final byte[] content) {
    this.entries.add(new Entry(name, REGULAR_FILE, content, "", FILE_MODE, Layout.STANDARD));
    return this;
  }

  DebianPackageBuilder executableFile(final String name, final byte[] content) {
    this.entries.add(new Entry(name, REGULAR_FILE, content, "", EXECUTABLE_MODE, Layout.STANDARD));
    return this;
  }

  DebianPackageBuilder directory(final String name) {
    this.entries.add(new Entry(name, DIRECTORY, NO_DATA, "", FILE_MODE, Layout.STANDARD));
    return this;
  }

  DebianPackageBuilder symbolicLink(final String name, final String target) {
    this.entries.add(new Entry(name, SYMBOLIC_LINK, NO_DATA, target, FILE_MODE, Layout.STANDARD));
    return this;
  }

  DebianPackageBuilder hardLink(final String name, final String target) {
    this.entries.add(new Entry(name, HARD_LINK, NO_DATA, target, FILE_MODE, Layout.STANDARD));
    return this;
  }

  DebianPackageBuilder rawEntry(final String name, final byte type, final byte[] data) {
    this.entries.add(new Entry(name, type, data, "", FILE_MODE, Layout.RAW));
    return this;
  }

  DebianPackageBuilder dataMember(final String dataMember) {
    this.dataMember = dataMember;
    return this;
  }

  DebianPackageBuilder longNames(final LongNames longNames) {
    this.longNames = longNames;
    return this;
  }

  DebianPackageBuilder corruptChecksum(final boolean corruptChecksum) {
    this.corruptChecksum = corruptChecksum;
    return this;
  }

  byte[] build() {
    final byte[] tar = this.buildTar();
    final byte[] data = this.compress(tar);
    final ByteArrayOutputStream archive = new ByteArrayOutputStream();
    archive.writeBytes(AR_MAGIC);
    this.writeMember(archive, BINARY_MEMBER, BINARY_VERSION);
    this.writeMember(archive, CONTROL_MEMBER, CONTROL);
    this.writeMember(archive, this.dataMember, data);
    return archive.toByteArray();
  }

  byte[] buildTar() {
    final ByteArrayOutputStream tar = new ByteArrayOutputStream();
    for (final Entry entry : this.entries) {
      this.writeAnyEntry(tar, entry);
    }

    final byte[] end = new byte[BLOCK_SIZE * END_BLOCKS];
    tar.writeBytes(end);
    return tar.toByteArray();
  }

  private void writeMember(final ByteArrayOutputStream archive, final String name, final byte[] data) {
    final String header = "%-16s%-12s%-6s%-6s%-8s%-10s`\n".formatted(name, AR_MODIFIED, AR_OWNER, AR_OWNER, AR_MODE, data.length);
    final byte[] headerBytes = header.getBytes(StandardCharsets.US_ASCII);
    archive.writeBytes(headerBytes);
    archive.writeBytes(data);
    if (data.length % AR_ALIGNMENT != 0) {
      archive.write(AR_PADDING);
    }
  }

  private byte[] compress(final byte[] tar) {
    final ByteArrayOutputStream compressed = new ByteArrayOutputStream();
    try (final OutputStream output = this.openCompressor(compressed)) {
      output.write(tar);
    } catch (final IOException exception) {
      throw new UncheckedIOException(exception);
    }

    return compressed.toByteArray();
  }

  private OutputStream openCompressor(final ByteArrayOutputStream compressed) throws IOException {
    if (this.dataMember.endsWith(".xz")) {
      final LZMA2Options options = new LZMA2Options();
      return new XZOutputStream(compressed, options);
    }

    if (this.dataMember.endsWith(".gz")) {
      return new GZIPOutputStream(compressed);
    }

    return compressed;
  }

  // a raw entry is written as given, with no long name handling
  private void writeAnyEntry(final ByteArrayOutputStream tar, final Entry entry) {
    if (entry.layout() == Layout.RAW) {
      this.writeRawEntry(tar, entry.name(), entry.type(), entry.data());
      return;
    }

    this.writeEntry(tar, entry);
  }

  private void writeEntry(final ByteArrayOutputStream tar, final Entry entry) {
    final boolean longName = this.isLong(entry.name());
    final boolean longLinkTarget = this.isLong(entry.linkTarget());
    if (!longName && !longLinkTarget) {
      this.writeHeaderAndData(tar, entry.name(), "", entry);
      return;
    }

    switch (this.longNames) {
      case GNU -> this.writeGnuLongNames(tar, entry, longName, longLinkTarget);
      case PAX -> this.writePaxNames(tar, entry, longName, longLinkTarget);
      case USTAR_PREFIX -> this.writePrefixedName(tar, entry);
    }
  }

  private boolean isLong(final String name) {
    final byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
    return bytes.length > NAME_LENGTH;
  }

  private void writeGnuLongNames(final ByteArrayOutputStream tar, final Entry entry, final boolean longName, final boolean longLinkTarget) {
    if (longName) {
      this.writeGnuMetadata(tar, GNU_LONG_NAME, entry.name());
    }

    if (longLinkTarget) {
      this.writeGnuMetadata(tar, GNU_LONG_LINK_NAME, entry.linkTarget());
    }

    this.writeTruncated(tar, entry);
  }

  private void writeGnuMetadata(final ByteArrayOutputStream tar, final byte type, final String value) {
    final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    final byte[] terminated = Arrays.copyOf(bytes, bytes.length + 1);
    final Entry metadata = new Entry(GNU_LONG_NAME_ENTRY, type, terminated, "", FILE_MODE, Layout.STANDARD);
    this.writeHeaderAndData(tar, GNU_LONG_NAME_ENTRY, "", metadata);
  }

  private void writePaxNames(final ByteArrayOutputStream tar, final Entry entry, final boolean longName, final boolean longLinkTarget) {
    final StringBuilder records = new StringBuilder();
    records.append(this.paxRecord(PAX_IGNORED_RECORD));
    if (longName) {
      records.append(this.paxRecord(PAX_PATH_KEY + entry.name()));
    }

    if (longLinkTarget) {
      records.append(this.paxRecord(PAX_LINK_PATH_KEY + entry.linkTarget()));
    }

    final String recordText = records.toString();
    final byte[] recordBytes = recordText.getBytes(StandardCharsets.UTF_8);
    this.writeRawEntry(tar, PAX_ENTRY, PAX_HEADER, recordBytes);
    this.writeTruncated(tar, entry);
  }

  // A pax record starts with its own length in bytes, digits included, so the length is found by trying each width.
  String paxRecord(final String keyValue) {
    final String record = " %s\n".formatted(keyValue);
    final int recordLength = record.getBytes(StandardCharsets.UTF_8).length;
    int length = recordLength + 1;
    while (Integer.toString(length).length() + recordLength != length) {
      length++;
    }

    return length + record;
  }

  private void writeTruncated(final ByteArrayOutputStream tar, final Entry entry) {
    final String name = this.truncate(entry.name());
    final String linkTarget = this.truncate(entry.linkTarget());
    final Entry truncated = new Entry(name, entry.type(), entry.data(), linkTarget, entry.mode(), Layout.STANDARD);
    this.writeHeaderAndData(tar, name, "", truncated);
  }

  private String truncate(final String name) {
    final boolean longName = this.isLong(name);
    return longName ? name.substring(0, NAME_LENGTH) : name;
  }

  private void writePrefixedName(final ByteArrayOutputStream tar, final Entry entry) {
    final String name = entry.name();
    final int split = name.lastIndexOf(PATH_SEPARATOR);
    final String prefix = name.substring(0, split);
    final String rest = name.substring(split + 1);
    this.writeHeaderAndData(tar, rest, prefix, entry);
  }

  private void writeRawEntry(final ByteArrayOutputStream tar, final String name, final byte type, final byte[] data) {
    final Entry raw = new Entry(name, type, data, "", FILE_MODE, Layout.STANDARD);
    this.writeHeaderAndData(tar, name, "", raw);
  }

  private void writeHeaderAndData(final ByteArrayOutputStream tar, final String name, final String prefix, final Entry entry) {
    final byte[] header = this.createHeader(name, prefix, entry);
    tar.writeBytes(header);
    tar.writeBytes(entry.data());
    final int padding = (BLOCK_SIZE - (entry.data().length % BLOCK_SIZE)) % BLOCK_SIZE;
    tar.writeBytes(new byte[padding]);
  }

  private byte[] createHeader(final String name, final String prefix, final Entry entry) {
    final byte[] header = new byte[BLOCK_SIZE];
    this.put(header, 0, name);
    this.put(header, MODE_OFFSET, entry.mode());
    final byte[] data = entry.data();
    this.put(header, OWNER_OFFSET, this.toOctal(0, NUMBER_DIGITS));
    this.put(header, GROUP_OFFSET, this.toOctal(0, NUMBER_DIGITS));
    this.put(header, SIZE_OFFSET, this.toOctal(data.length, SIZE_DIGITS));
    this.put(header, MODIFIED_OFFSET, this.toOctal(0, SIZE_DIGITS));
    header[TYPE_OFFSET] = entry.type();
    this.put(header, LINK_NAME_OFFSET, entry.linkTarget());
    final boolean prefixed = !prefix.isEmpty();
    final byte[] magic = prefixed ? USTAR_MAGIC : GNU_MAGIC;
    System.arraycopy(magic, 0, header, MAGIC_OFFSET, magic.length);
    final byte[] prefixBytes = prefix.getBytes(StandardCharsets.UTF_8);
    System.arraycopy(prefixBytes, 0, header, PREFIX_OFFSET, Math.min(prefixBytes.length, PREFIX_LENGTH));
    this.putChecksum(header);
    return header;
  }

  private void putChecksum(final byte[] header) {
    Arrays.fill(header, CHECKSUM_OFFSET, CHECKSUM_OFFSET + CHECKSUM_LENGTH, CHECKSUM_PLACEHOLDER);
    int checksum = 0;
    for (final byte value : header) {
      checksum += value & UNSIGNED_BYTE_MASK;
    }

    final int written = this.corruptChecksum ? checksum + 1 : checksum;
    final String checksumField = this.toOctal(written, CHECKSUM_DIGITS) + CHECKSUM_TERMINATOR;
    this.put(header, CHECKSUM_OFFSET, checksumField);
  }

  private String toOctal(final long value, final int digits) {
    final String octal = Long.toOctalString(value);
    return Strings.padStart(octal, digits, OCTAL_PADDING);
  }

  private void put(final byte[] header, final int offset, final String value) {
    final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    System.arraycopy(bytes, 0, header, offset, bytes.length);
  }

  enum LongNames {
    GNU,
    PAX,
    USTAR_PREFIX,
  }

  private enum Layout {
    STANDARD,
    RAW,
  }

  // the entry is a private tuple that is never compared or hashed, so its array component needs no deep equality
  @SuppressWarnings("ArrayRecordComponent")
  private record Entry(String name, byte type, byte[] data, String linkTarget, String mode, Layout layout) {}
}
