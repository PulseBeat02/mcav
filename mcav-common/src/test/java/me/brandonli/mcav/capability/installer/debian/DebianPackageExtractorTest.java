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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.base.Strings;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import me.brandonli.mcav.capability.installer.InstallationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

final class DebianPackageExtractorTest {

  private static final String LIBRARY = "./usr/lib/aarch64-linux-gnu/libexample.so.1.2.3";
  private static final String LIBRARY_PATH = "usr/lib/aarch64-linux-gnu/libexample.so.1.2.3";
  private static final String LINK = "./usr/lib/aarch64-linux-gnu/libexample.so.1";
  private static final String LINK_PATH = "usr/lib/aarch64-linux-gnu/libexample.so.1";
  private static final String LINK_TARGET = "libexample.so.1.2.3";
  private static final byte[] CONTENT = "library".getBytes(StandardCharsets.US_ASCII);

  private static final List<String> TREES = List.of("usr/lib", "lib");
  private static final List<String> PROGRAM_TREES = List.of("usr/lib", "lib", "usr/bin", "usr/share/tool");
  private static final String PROGRAM = "./usr/bin/tool";
  private static final String PROGRAM_PATH = "usr/bin/tool";

  private static final Configuration POSIX = Configuration.unix().toBuilder().setAttributeViews("basic", "owner", "posix", "unix").build();
  private static final Set<PosixFilePermission> EXECUTABLE = PosixFilePermissions.fromString("rwxr-xr-x");
  private static final Set<PosixFilePermission> REGULAR = PosixFilePermissions.fromString("rw-r--r--");
  private static final Set<PosixFilePermission> FOLDER = PosixFilePermissions.fromString("rwxr-xr-x");

  private static final String UNCOMPRESSED = "data.tar";
  private static final String DEEP_DIRECTORY = "d".repeat(120);

  // the first tar header of an uncompressed package: the ar magic, debian-binary with its 4 bytes and the control
  // member with its 7 bytes and a padding byte come first, each member behind a 60 byte header
  private static final int FIRST_TAR_HEADER = 8 + 60 + 4 + 60 + 7 + 1 + 60;
  private static final int TAR_HEADER_SIZE = 512;
  private static final int TAR_MODE_OFFSET = 100;
  private static final int TAR_SIZE_OFFSET = 124;
  private static final int TAR_CHECKSUM_OFFSET = 148;
  private static final int TAR_CHECKSUM_LENGTH = 8;
  private static final int CHECKSUM_DIGITS = 6;
  private static final int FIRST_AR_SIZE = 8 + 48;
  private static final int DATA_MEMBER_NAME = FIRST_TAR_HEADER - 60;
  private static final int TAR_NAME_LENGTH = 100;
  private static final int UNSIGNED_BYTE_MASK = 0xFF;

  private static final byte PAX_HEADER = 'x';
  private static final byte GNU_LONG_NAME = 'L';
  private static final String PAX_ENTRY = "./PaxHeaders/entry";
  private static final int MAXIMUM_METADATA_SIZE = 64 * 1024;

  private FileSystem fileSystem;
  private Path archive;
  private Path destination;

  @BeforeEach
  void setUp() {
    this.fileSystem = Jimfs.newFileSystem(POSIX);
    this.archive = this.fileSystem.getPath("/package.deb");
    this.destination = this.fileSystem.getPath("/destination");
  }

  @AfterEach
  void tearDown() throws IOException {
    this.fileSystem.close();
  }

  @Test
  void shouldExtractTheLibraryTreesAndSkipEverythingElse() throws IOException {
    final byte[] other = "other".getBytes(StandardCharsets.US_ASCII);
    final byte[] debianPackage = new DebianPackageBuilder()
      .directory("./")
      .directory("./usr/")
      .directory("./usr/lib/")
      .file(LIBRARY, CONTENT)
      .symbolicLink(LINK, LINK_TARGET)
      .file("./lib/aarch64-linux-gnu/libother.so.0", other)
      .file("./usr/share/doc/libexample/copyright", other)
      .file("./etc/example.conf", other)
      .file("./usr/lib/../../../escape.so", other)
      .build();
    this.extract(debianPackage);

    final byte[] library = Files.readAllBytes(this.destination.resolve(LIBRARY_PATH));
    final Path link = this.destination.resolve(LINK_PATH);
    final Path linkTarget = Files.readSymbolicLink(link);
    final byte[] linked = Files.readAllBytes(link);
    final byte[] otherLibrary = Files.readAllBytes(this.destination.resolve("lib/aarch64-linux-gnu/libother.so.0"));
    assertArrayEquals(CONTENT, library);
    assertEquals(this.fileSystem.getPath(LINK_TARGET), linkTarget);
    assertArrayEquals(CONTENT, linked);
    assertArrayEquals(other, otherLibrary);

    final boolean documentation = Files.exists(this.destination.resolve("usr/share"));
    final boolean configuration = Files.exists(this.destination.resolve("etc"));
    final boolean escaped = Files.exists(this.fileSystem.getPath("/escape.so"));
    assertFalse(documentation);
    assertFalse(configuration);
    assertFalse(escaped);
  }

  @Test
  void shouldExtractTheTreesTheCallerNames() throws IOException {
    final byte[] other = "other".getBytes(StandardCharsets.US_ASCII);
    final byte[] debianPackage = new DebianPackageBuilder()
      .executableFile(PROGRAM, CONTENT)
      .file("./usr/share/tool/firmware.bin", CONTENT)
      .file("./usr/share/doc/tool/copyright", other)
      .build();
    this.extract(debianPackage, PROGRAM_TREES);

    final byte[] program = Files.readAllBytes(this.destination.resolve(PROGRAM_PATH));
    final byte[] firmware = Files.readAllBytes(this.destination.resolve("usr/share/tool/firmware.bin"));
    assertArrayEquals(CONTENT, program);
    assertArrayEquals(CONTENT, firmware);

    final boolean documentation = Files.exists(this.destination.resolve("usr/share/doc"));
    assertFalse(documentation);
  }

  @Test
  void shouldMakeAFileExecutableOnlyWhenItsEntryIs() throws IOException {
    final byte[] debianPackage = new DebianPackageBuilder().executableFile(PROGRAM, CONTENT).file(LIBRARY, CONTENT).build();
    this.extract(debianPackage, PROGRAM_TREES);

    final Set<PosixFilePermission> program = Files.getPosixFilePermissions(this.destination.resolve(PROGRAM_PATH));
    final Set<PosixFilePermission> library = Files.getPosixFilePermissions(this.destination.resolve(LIBRARY_PATH));
    assertEquals(EXECUTABLE, program);
    assertEquals(REGULAR, library);
  }

  @Test
  void shouldCreateFoldersThatOnlyTheirOwnerMayWrite() throws IOException {
    final byte[] debianPackage = new DebianPackageBuilder().directory("./usr/bin/").file(LIBRARY, CONTENT).build();
    this.extract(debianPackage, PROGRAM_TREES);

    final Set<PosixFilePermission> directory = Files.getPosixFilePermissions(this.destination.resolve("usr/bin"));
    final Set<PosixFilePermission> parent = Files.getPosixFilePermissions(this.destination.resolve("usr/lib/aarch64-linux-gnu"));
    assertEquals(FOLDER, directory);
    assertEquals(FOLDER, parent);
  }

  @Test
  void shouldCreateTheDestinationOfAPackageWithNothingToExtract() throws IOException {
    final byte[] debianPackage = new DebianPackageBuilder().file("./etc/example.conf", CONTENT).build();
    this.extract(debianPackage);

    final Set<PosixFilePermission> destinationPermissions = Files.getPosixFilePermissions(this.destination);
    assertEquals(FOLDER, destinationPermissions);
  }

  @Test
  void shouldExtractOntoAFileSystemWithoutPermissions() throws IOException {
    final byte[] debianPackage = new DebianPackageBuilder()
      .directory("./usr/bin/")
      .executableFile(PROGRAM, CONTENT)
      .file(LIBRARY, CONTENT)
      .symbolicLink(LINK, LINK_TARGET)
      .build();
    try (final FileSystem basic = Jimfs.newFileSystem(Configuration.unix())) {
      final Path basicArchive = basic.getPath("/package.deb");
      final Path basicDestination = basic.getPath("/destination");
      Files.write(basicArchive, debianPackage);
      DebianPackageExtractor.extract(basicArchive, basicDestination, PROGRAM_TREES);

      final byte[] program = Files.readAllBytes(basicDestination.resolve(PROGRAM_PATH));
      final byte[] linked = Files.readAllBytes(basicDestination.resolve(LINK_PATH));
      assertArrayEquals(CONTENT, program);
      assertArrayEquals(CONTENT, linked);
    }
  }

  @Test
  void shouldReplaceAnEarlierEntryOfTheSameName() throws IOException {
    final byte[] newer = "newer".getBytes(StandardCharsets.US_ASCII);
    final byte[] debianPackage = new DebianPackageBuilder().file(LIBRARY, CONTENT).file(LIBRARY, newer).build();
    this.extract(debianPackage);

    final byte[] library = Files.readAllBytes(this.destination.resolve(LIBRARY_PATH));
    assertArrayEquals(newer, library);
  }

  @ParameterizedTest
  @ValueSource(strings = { "data.tar.xz", "data.tar.gz", UNCOMPRESSED })
  void shouldReadEveryCompressionDebianUses(final String dataMember) throws IOException {
    final byte[] debianPackage = new DebianPackageBuilder().dataMember(dataMember).file(LIBRARY, CONTENT).build();
    this.extract(debianPackage);

    final byte[] library = Files.readAllBytes(this.destination.resolve(LIBRARY_PATH));
    assertArrayEquals(CONTENT, library);
  }

  @Test
  void shouldRefuseZstdNamingIt() {
    final byte[] debianPackage = new DebianPackageBuilder().dataMember("data.tar.zst").file(LIBRARY, CONTENT).build();
    final InstallationException failure = this.extractFailure(debianPackage);

    final String message = failure.getMessage();
    final String reason = this.findReason(failure);
    assertEquals("Debian package /package.deb could not be extracted!", message);
    assertEquals("The data member is compressed with zstd, which is not supported!", reason);
  }

  @Test
  void shouldRefuseAnUnknownCompression() {
    final byte[] debianPackage = new DebianPackageBuilder().dataMember("data.tar.bz2").file(LIBRARY, CONTENT).build();

    final String reason = this.extractFailureReason(debianPackage);
    assertEquals("Data member data.tar.bz2 has an unknown compression!", reason);
  }

  @Test
  void shouldRefuseAFileThatIsNotAnArArchive() {
    final byte[] notAPackage = "not a package".getBytes(StandardCharsets.US_ASCII);

    final String reason = this.extractFailureReason(notAPackage);
    assertEquals("The file is not an ar archive!", reason);
  }

  @Test
  void shouldRefuseAPackageWithoutADataMember() {
    final byte[] debianPackage = new DebianPackageBuilder().dataMember("payload.tar").file(LIBRARY, CONTENT).build();

    final String reason = this.extractFailureReason(debianPackage);
    assertEquals("The package ends before its data member!", reason);
  }

  @Test
  void shouldRefuseAnArMemberSizeThatIsNoNumber() {
    final byte[] debianPackage = new DebianPackageBuilder().file(LIBRARY, CONTENT).build();
    final byte[] size = "12x       ".getBytes(StandardCharsets.US_ASCII);
    System.arraycopy(size, 0, debianPackage, FIRST_AR_SIZE, size.length);

    final String reason = this.extractFailureReason(debianPackage);
    assertEquals("Ar member size 12x is not a number!", reason);
  }

  @Test
  void shouldReadANameThatFillsTheWholeNameField() throws IOException {
    final String directory = "./usr/lib/";
    final String fileName = "f".repeat(TAR_NAME_LENGTH - directory.length());
    final byte[] debianPackage = new DebianPackageBuilder().file(directory + fileName, CONTENT).build();
    this.extract(debianPackage);

    final byte[] library = Files.readAllBytes(this.destination.resolve("usr/lib").resolve(fileName));
    assertArrayEquals(CONTENT, library);
  }

  @Test
  void shouldReadArMemberNamesWithTheGnuTerminator() throws IOException {
    final byte[] debianPackage = new DebianPackageBuilder().dataMember(UNCOMPRESSED).file(LIBRARY, CONTENT).build();
    final byte[] name = "data.tar/".getBytes(StandardCharsets.US_ASCII);
    System.arraycopy(name, 0, debianPackage, DATA_MEMBER_NAME, name.length);
    this.extract(debianPackage);

    final byte[] library = Files.readAllBytes(this.destination.resolve(LIBRARY_PATH));
    assertArrayEquals(CONTENT, library);
  }

  @ParameterizedTest
  @EnumSource(DebianPackageBuilder.LongNames.class)
  void shouldReadNamesLongerThanATarHeaderHolds(final DebianPackageBuilder.LongNames longNames) throws IOException {
    final String name = "./usr/lib/%s/libdeep.so.1".formatted(DEEP_DIRECTORY);
    final byte[] debianPackage = new DebianPackageBuilder().longNames(longNames).file(name, CONTENT).build();
    this.extract(debianPackage);

    final Path library = this.destination.resolve("usr/lib/%s/libdeep.so.1".formatted(DEEP_DIRECTORY));
    final byte[] extracted = Files.readAllBytes(library);
    assertArrayEquals(CONTENT, extracted);
  }

  @ParameterizedTest
  @EnumSource(value = DebianPackageBuilder.LongNames.class, names = { "GNU", "PAX" })
  void shouldReadLinkTargetsLongerThanATarHeaderHolds(final DebianPackageBuilder.LongNames longNames) throws IOException {
    final String target = "%s/libdeep.so.1".formatted(DEEP_DIRECTORY);
    final byte[] debianPackage = new DebianPackageBuilder()
      .longNames(longNames)
      .file("./usr/lib/%s".formatted(target), CONTENT)
      .symbolicLink("./usr/lib/libdeep.so.1", target)
      .build();
    this.extract(debianPackage);

    final Path link = this.destination.resolve("usr/lib/libdeep.so.1");
    final Path linkTarget = Files.readSymbolicLink(link);
    assertEquals(this.fileSystem.getPath(target), linkTarget);
  }

  // the last one only comes back by name, which a link on the way to the destination could lead elsewhere
  @ParameterizedTest
  @ValueSource(strings = { "/etc/passwd", "../../../../outside", "", "../../../../destination/usr/lib/libexample.so.1.2.3" })
  void shouldRefuseSymbolicLinksThatLeaveTheDestination(final String target) {
    final byte[] debianPackage = new DebianPackageBuilder().symbolicLink(LINK, target).build();

    final String reason = this.extractFailureReason(debianPackage);
    final String expected = "Symbolic link %s points to %s outside the destination!".formatted(LINK, target);
    assertEquals(expected, reason);
  }

  // up/../.. stays inside by its text, but up leads to the destination itself, so it would leave by two folders
  @ParameterizedTest
  @ValueSource(strings = { "up/../..", "./up", "up/." })
  void shouldRefuseSymbolicLinksThatAreNotNormalized(final String target) {
    final byte[] debianPackage = new DebianPackageBuilder()
      .symbolicLink("./usr/lib/up", "../..")
      .symbolicLink("./usr/lib/out", target)
      .build();

    final String reason = this.extractFailureReason(debianPackage);
    final String expected = "Symbolic link ./usr/lib/out points to %s, which is not a normalized path!".formatted(target);
    assertEquals(expected, reason);
  }

  @Test
  void shouldKeepASymbolicLinkInANewFolderThatPointsInsideTheDestination() throws IOException {
    final String target = "../lib/tool/tool";
    final byte[] debianPackage = new DebianPackageBuilder()
      .executableFile("./usr/lib/tool/tool", CONTENT)
      .symbolicLink(PROGRAM, target)
      .build();
    this.extract(debianPackage, PROGRAM_TREES);

    final Path link = this.destination.resolve(PROGRAM_PATH);
    final Path linkTarget = Files.readSymbolicLink(link);
    final byte[] linked = Files.readAllBytes(link);
    assertEquals(this.fileSystem.getPath(target), linkTarget);
    assertArrayEquals(CONTENT, linked);
  }

  @ParameterizedTest
  @ValueSource(strings = { "./usr/lib/link/evil.so", "./usr/lib/link" })
  void shouldRefuseEntriesThroughOrOverASymbolicLink(final String name) {
    final byte[] debianPackage = new DebianPackageBuilder()
      .directory("./usr/lib/real/")
      .symbolicLink("./usr/lib/link", "real")
      .file(name, CONTENT)
      .build();

    final String reason = this.extractFailureReason(debianPackage);
    final String expected = "Tar entry %s goes through or replaces a symbolic link!".formatted(name);
    assertEquals(expected, reason);
  }

  @Test
  void shouldRefuseAHeaderWithAWrongChecksum() {
    final byte[] debianPackage = new DebianPackageBuilder().corruptChecksum(true).file(LIBRARY, CONTENT).build();

    final String reason = this.extractFailureReason(debianPackage);
    final boolean namesTheChecksum = reason.startsWith("Tar header checksum ");
    assertTrue(namesTheChecksum, reason);
  }

  @Test
  void shouldRefuseHardLinksInTheLibraryTreesOnly() {
    final byte[] outside = new DebianPackageBuilder().hardLink("./usr/share/hard", "./usr/share/file").build();
    final byte[] inside = new DebianPackageBuilder().hardLink("./usr/lib/hard", "./usr/lib/file").build();
    this.writeArchive(outside);
    assertDoesNotThrow(() -> DebianPackageExtractor.extract(this.archive, this.destination, TREES));

    final String reason = this.extractFailureReason(inside);
    assertEquals("Tar entry ./usr/lib/hard has the unsupported type 1!", reason);
  }

  @Test
  void shouldRefuseEntriesAboveTheSizeLimits() throws IOException {
    final byte[] tenBytes = "0123456789".getBytes(StandardCharsets.US_ASCII);
    final byte[] debianPackage = new DebianPackageBuilder()
      .file("./usr/lib/first.so", tenBytes)
      .file("./usr/lib/second.so", tenBytes)
      .build();
    this.writeArchive(debianPackage);

    assertDoesNotThrow(() -> DebianPackageExtractor.extract(this.archive, this.destination, TREES, 10, 20));

    final Path entryDestination = this.fileSystem.getPath("/entry");
    final InstallationException entryFailure = assertThrows(InstallationException.class, () ->
      DebianPackageExtractor.extract(this.archive, entryDestination, TREES, 9, 20)
    );
    final String entryReason = this.findReason(entryFailure);
    assertEquals("Tar entry of 10 bytes exceeds the maximum entry size!", entryReason);

    final Path totalDestination = this.fileSystem.getPath("/total");
    final InstallationException totalFailure = assertThrows(InstallationException.class, () ->
      DebianPackageExtractor.extract(this.archive, totalDestination, TREES, 10, 19)
    );
    final String totalReason = this.findReason(totalFailure);
    assertEquals("The data archive exceeds the maximum total size!", totalReason);
  }

  @Test
  void shouldRefuseTarNumbersThatAreNotOctal() {
    final byte[] debianPackage = new DebianPackageBuilder().dataMember(UNCOMPRESSED).file(LIBRARY, CONTENT).build();
    final byte[] badSize = debianPackage.clone();
    this.rewriteTarField(badSize, TAR_SIZE_OFFSET, "00000000009");

    final String sizeReason = this.extractFailureReason(badSize);
    assertEquals("Tar header field 00000000009 is not an octal number!", sizeReason);

    final byte[] badChecksum = debianPackage.clone();
    final byte[] checksum = "zz\0     ".getBytes(StandardCharsets.US_ASCII);
    System.arraycopy(checksum, 0, badChecksum, FIRST_TAR_HEADER + TAR_CHECKSUM_OFFSET, checksum.length);

    final String checksumReason = this.extractFailureReason(badChecksum);
    assertEquals("Tar header field zz is not an octal number!", checksumReason);

    final byte[] badMode = debianPackage.clone();
    this.rewriteTarField(badMode, TAR_MODE_OFFSET, "000064x");

    final String modeReason = this.extractFailureReason(badMode);
    assertEquals("Tar header field 000064x is not an octal number!", modeReason);
  }

  @Test
  void shouldRefuseATruncatedArchive() {
    final byte[] debianPackage = new DebianPackageBuilder().dataMember(UNCOMPRESSED).file(LIBRARY, CONTENT).build();
    final byte[] insideHeader = Arrays.copyOf(debianPackage, FIRST_TAR_HEADER + TAR_HEADER_SIZE / 2);
    final byte[] insideData = Arrays.copyOf(debianPackage, FIRST_TAR_HEADER + TAR_HEADER_SIZE + 1);

    final String headerReason = this.extractFailureReason(insideHeader);
    assertEquals("The data archive ends inside a header!", headerReason);

    final InstallationException dataFailure = this.extractFailure(insideData);
    final Throwable dataCause = dataFailure.getCause();
    assertInstanceOf(EOFException.class, dataCause);
  }

  @Test
  void shouldRefuseTruncatedOrOversizedMetadata() {
    final String longName = "./usr/lib/%s/libdeep.so.1".formatted(DEEP_DIRECTORY);
    final byte[] longNamePackage = new DebianPackageBuilder().dataMember(UNCOMPRESSED).file(longName, CONTENT).build();
    final byte[] truncated = Arrays.copyOf(longNamePackage, FIRST_TAR_HEADER + TAR_HEADER_SIZE + 10);

    final String truncatedReason = this.extractFailureReason(truncated);
    assertEquals("The data archive ends inside metadata!", truncatedReason);

    final byte[] largest = new byte[MAXIMUM_METADATA_SIZE];
    final byte[] tooLarge = new byte[MAXIMUM_METADATA_SIZE + 1];
    final byte[] largestPackage = new DebianPackageBuilder().rawEntry(PAX_ENTRY, GNU_LONG_NAME, largest).build();
    final byte[] tooLargePackage = new DebianPackageBuilder().rawEntry(PAX_ENTRY, GNU_LONG_NAME, tooLarge).build();
    this.writeArchive(largestPackage);
    assertDoesNotThrow(() -> DebianPackageExtractor.extract(this.archive, this.destination, TREES));

    final String tooLargeReason = this.extractFailureReason(tooLargePackage);
    assertEquals("Tar metadata of 65537 bytes exceeds the maximum metadata size!", tooLargeReason);
  }

  @ParameterizedTest
  @ValueSource(strings = { "abc path=x\n", "12", "99 path=x\n", "3 path=x\n", "1 x" })
  void shouldRefusePaxRecordsWithAWrongLength(final String records) {
    final byte[] recordBytes = records.getBytes(StandardCharsets.UTF_8);
    final byte[] debianPackage = new DebianPackageBuilder().rawEntry(PAX_ENTRY, PAX_HEADER, recordBytes).build();

    final String reason = this.extractFailureReason(debianPackage);
    final boolean wrongLength = reason.equals("A pax record has a wrong length!") || reason.equals("A pax record has no length!");
    assertTrue(wrongLength, reason);
  }

  @Test
  void shouldRefuseAPaxRecordWithoutAValue() {
    final DebianPackageBuilder builder = new DebianPackageBuilder();
    final String record = builder.paxRecord("novalue");
    final byte[] recordBytes = record.getBytes(StandardCharsets.UTF_8);
    final byte[] debianPackage = builder.rawEntry(PAX_ENTRY, PAX_HEADER, recordBytes).build();

    final String reason = this.extractFailureReason(debianPackage);
    assertEquals("A pax record has no value!", reason);
  }

  @Test
  void shouldIgnoreAPaxRecordWithAnEmptyKey() throws IOException {
    final DebianPackageBuilder builder = new DebianPackageBuilder();
    final String record = builder.paxRecord("=value");
    final byte[] recordBytes = record.getBytes(StandardCharsets.UTF_8);
    final byte[] debianPackage = builder.rawEntry(PAX_ENTRY, PAX_HEADER, recordBytes).file(LIBRARY, CONTENT).build();
    this.extract(debianPackage);

    final byte[] library = Files.readAllBytes(this.destination.resolve(LIBRARY_PATH));
    assertArrayEquals(CONTENT, library);
  }

  @Test
  void shouldRefuseANameThatIsNoPathOfTheFileSystem() {
    final DebianPackageBuilder builder = new DebianPackageBuilder();
    final String record = builder.paxRecord("path=./usr/lib/a\0b");
    final byte[] recordBytes = record.getBytes(StandardCharsets.UTF_8);
    final byte[] debianPackage = builder.rawEntry(PAX_ENTRY, PAX_HEADER, recordBytes).file(LIBRARY, CONTENT).build();

    final String reason = this.extractFailureReason(debianPackage);
    assertEquals("Tar entry name ./usr/lib/a\0b is not a path of this system!", reason);
  }

  @Test
  void shouldExtractThroughADestinationThatIsASymbolicLink() throws IOException {
    final Path alias = this.fileSystem.getPath("/alias");
    Files.createDirectories(this.destination);
    Files.createSymbolicLink(alias, this.destination);
    final byte[] debianPackage = new DebianPackageBuilder().file(LIBRARY, CONTENT).build();
    this.writeArchive(debianPackage);
    DebianPackageExtractor.extract(this.archive, alias, TREES);

    final byte[] library = Files.readAllBytes(this.destination.resolve(LIBRARY_PATH));
    assertArrayEquals(CONTENT, library);
  }

  private void extract(final byte[] debianPackage) {
    this.extract(debianPackage, TREES);
  }

  private void extract(final byte[] debianPackage, final List<String> trees) {
    this.writeArchive(debianPackage);
    DebianPackageExtractor.extract(this.archive, this.destination, trees);
  }

  private String extractFailureReason(final byte[] debianPackage) {
    final InstallationException failure = this.extractFailure(debianPackage);
    return this.findReason(failure);
  }

  private String findReason(final InstallationException failure) {
    final Throwable cause = Objects.requireNonNull(failure.getCause(), "The failure must have a cause!");
    return Objects.requireNonNull(cause.getMessage(), "The cause must have a message!");
  }

  private InstallationException extractFailure(final byte[] debianPackage) {
    this.writeArchive(debianPackage);
    return assertThrows(InstallationException.class, () -> DebianPackageExtractor.extract(this.archive, this.destination, TREES));
  }

  private void writeArchive(final byte[] debianPackage) {
    try {
      Files.write(this.archive, debianPackage);
    } catch (final IOException exception) {
      throw new AssertionError(exception);
    }
  }

  // Writes a field of the first tar header of an uncompressed package and recomputes the header checksum, so the
  // extractor reads the field instead of refusing the checksum.
  private void rewriteTarField(final byte[] debianPackage, final int fieldOffset, final String value) {
    final byte[] valueBytes = value.getBytes(StandardCharsets.US_ASCII);
    System.arraycopy(valueBytes, 0, debianPackage, FIRST_TAR_HEADER + fieldOffset, valueBytes.length);
    final int checksumStart = FIRST_TAR_HEADER + TAR_CHECKSUM_OFFSET;
    Arrays.fill(debianPackage, checksumStart, checksumStart + TAR_CHECKSUM_LENGTH, (byte) ' ');
    int checksum = 0;
    for (int index = FIRST_TAR_HEADER; index < FIRST_TAR_HEADER + TAR_HEADER_SIZE; index++) {
      checksum += debianPackage[index] & UNSIGNED_BYTE_MASK;
    }

    final String octal = Integer.toOctalString(checksum);
    final String checksumField = Strings.padStart(octal, CHECKSUM_DIGITS, '0') + "\0 ";
    final byte[] checksumBytes = checksumField.getBytes(StandardCharsets.US_ASCII);
    System.arraycopy(checksumBytes, 0, debianPackage, checksumStart, checksumBytes.length);
  }
}
