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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.List;
import me.brandonli.mcav.testing.LocalHttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class DebianPinGeneratorTest {

  private static final URI MAIN_SNAPSHOT = URI.create("https://snapshot.debian.org/archive/debian/20261017T000000Z/");
  private static final URI SECURITY_SNAPSHOT = URI.create("https://snapshot.debian.org/archive/debian-security/20261017T000000Z/");

  private static final String INDEX = """
  Package: libroot1
  Architecture: arm64
  Pre-Depends: libc6 (>= 2.36)
  Depends: libmiddle2 (>= 1.0), libalternative1 | libunused1, libvirtual, libroot-data
  Filename: pool/main/r/root/libroot1_1.0_arm64.deb
  SHA256: 1111
  Description: a root library
   whose description: spans lines

  Package: libmiddle2
  Architecture: arm64
  Depends: libleaf0:any, libroot1
  Filename: pool/main/m/middle/libmiddle2_2.0_arm64.deb
  SHA256: 2222

  Package: libleaf0
  Version: 0.1-1
  Architecture: arm64
  Depends: libgcc-s1, libstdc++6
  Filename: pool/main/l/leaf/libleaf0_0.1_arm64.deb
  SHA256: 3333

  Package: libalternative1
  Version: 1-1
  Architecture: arm64
  Filename: pool/main/a/alternative/libalternative1_1_arm64.deb
  SHA256: 4444

  Package: libunused1
  Version: 1
  Architecture: arm64
  Filename: pool/main/u/unused/libunused1_1_arm64.deb
  SHA256: 5555

  Package: libprovider3
  Architecture: arm64
  Provides: libvirtual (= 3)
  Filename: pool/main/p/provider/libprovider3_3_arm64.deb
  SHA256: 6666

  Package: libroot-data
  Architecture: all
  Depends: libunused1
  Filename: pool/main/r/root/libroot-data_1.0_all.deb
  SHA256: 7777

  Package: libc6
  Architecture: arm64
  Filename: pool/main/g/glibc/libc6_2.36_arm64.deb
  SHA256: 8888
  """;

  private static final String SECURITY_INDEX = """
  Package: libleaf0
  Version: 0.1-1+deb12u1
  Architecture: arm64
  Filename: pool/updates/main/l/leaf/libleaf0_0.1-1+deb12u1_arm64.deb
  SHA256: 9999

  Package: libalternative1
  Version: 1-1~deb12u1
  Architecture: arm64
  Filename: pool/updates/main/a/alternative/libalternative1_1-1~deb12u1_arm64.deb
  SHA256: aaaa

  Package: libunused1
  Version: 1
  Architecture: arm64
  Filename: pool/updates/main/u/unused/libunused1_1_arm64.deb
  SHA256: bbbb
  """;

  private static final List<DebianPinGenerator.Index> MAIN = List.of(new DebianPinGenerator.Index(MAIN_SNAPSHOT, INDEX));
  private static final List<DebianPinGenerator.Index> BOTH = List.of(
    new DebianPinGenerator.Index(MAIN_SNAPSHOT, INDEX),
    new DebianPinGenerator.Index(SECURITY_SNAPSHOT, SECURITY_INDEX)
  );

  private static final String ROOT_PACKAGE = "libroot1";
  private static final String ROOT_DATA_PACKAGE = "libroot-data";
  private static final String MIDDLE_PACKAGE = "libmiddle2";
  private static final String ALTERNATIVE_PACKAGE = "libalternative1";
  private static final String PROVIDER_PACKAGE = "libprovider3";
  private static final String LEAF_PACKAGE = "libleaf0";
  private static final String UNUSED_PACKAGE = "libunused1";

  private static final String LEAF_FILENAME = "pool/main/l/leaf/libleaf0_0.1_arm64.deb";
  private static final String SECURITY_LEAF_FILENAME = "pool/updates/main/l/leaf/libleaf0_0.1-1+deb12u1_arm64.deb";
  private static final String ALTERNATIVE_FILENAME = "pool/main/a/alternative/libalternative1_1_arm64.deb";

  private static final String QEMU_BUNDLE_FILE = "debian-qemu-x86.json";

  private static final String ARCHIVE_PATH = "/archive/debian/20261104T203743Z/pool/main/l/leaf/libleaf0_0.1_arm64.deb";
  private static final String OTHER_ARCHIVE_PATH = "/archive/debian/20261104T000000Z/pool/main/l/leaf/libleaf0_0.1_arm64.deb";
  private static final String FILE_FARM_PATH = "/file/5c772838b9fabdbf5f503bd83ce9bb7fe1b4f2da/libleaf0_0.1_arm64.deb";
  private static final String OTHER_HOST_FILE_FARM_URL =
    "https://snapshot.example.org/file/5c772838b9fabdbf5f503bd83ce9bb7fe1b4f2da/libleaf0_0.1_arm64.deb";

  private static final int OK_STATUS = 200;
  private static final int FOUND_STATUS = 302;
  private static final int UNAVAILABLE_STATUS = 503;
  private static final byte[] NO_BODY = new byte[0];

  private static final int LOOKUP_ATTEMPTS = 3;

  private static final String NO_REDIRECT_MESSAGE = "Snapshot answered %s for %s without a file farm redirect!";

  private DebianPinGenerator generator;
  private LocalHttpServer server;
  private HttpClient client;

  @BeforeEach
  void setUp() {
    this.generator = new DebianPinGenerator(DebianPinGenerator.IndependentPackages.LEFT_OUT);
    this.server = LocalHttpServer.start();
    this.client = HttpClient.newHttpClient();
  }

  @AfterEach
  void tearDown() {
    this.client.close();
    this.server.close();
  }

  @Test
  void shouldListEveryPackageAfterItsDependencies() {
    final List<DebianPinGenerator.IndexEntry> closure = this.generator.resolveClosure(MAIN, List.of(ROOT_PACKAGE));

    final List<String> names = closure.stream().map(DebianPinGenerator.IndexEntry::name).toList();
    final List<String> expected = List.of(LEAF_PACKAGE, MIDDLE_PACKAGE, ALTERNATIVE_PACKAGE, PROVIDER_PACKAGE, ROOT_PACKAGE);
    assertEquals(expected, names);
  }

  @Test
  void shouldIncludeIndependentPackagesWithTheirDependencies() {
    final DebianPinGenerator including = new DebianPinGenerator(DebianPinGenerator.IndependentPackages.INCLUDED);
    final List<DebianPinGenerator.IndexEntry> closure = including.resolveClosure(MAIN, List.of(ROOT_PACKAGE));

    final List<String> names = closure.stream().map(DebianPinGenerator.IndexEntry::name).toList();
    final List<String> expected = List.of(
      LEAF_PACKAGE,
      MIDDLE_PACKAGE,
      ALTERNATIVE_PACKAGE,
      PROVIDER_PACKAGE,
      UNUSED_PACKAGE,
      ROOT_DATA_PACKAGE,
      ROOT_PACKAGE
    );
    assertEquals(expected, names);
  }

  @Test
  void shouldKeepTheFileAndHashOfEachPackage() {
    final List<DebianPinGenerator.IndexEntry> closure = this.generator.resolveClosure(MAIN, List.of(LEAF_PACKAGE));

    final DebianPinGenerator.IndexEntry leaf = closure.getFirst();
    final String leafFilename = leaf.filename();
    final String leafHash = leaf.sha256();
    assertEquals(LEAF_FILENAME, leafFilename);
    assertEquals("3333", leafHash);
  }

  @Test
  void shouldRefuseADependencyMissingFromTheIndex() {
    final List<String> roots = List.of("libmissing1");

    final IllegalStateException failure = assertThrows(IllegalStateException.class, () -> this.generator.resolveClosure(MAIN, roots));
    final String message = failure.getMessage();
    assertEquals("Package libmissing1 is missing from the indexes!", message);
  }

  @Test
  void shouldTakeANewerVersionFromTheSecurityArchive() {
    final List<DebianPinGenerator.IndexEntry> closure = this.generator.resolveClosure(BOTH, List.of(LEAF_PACKAGE));

    final DebianPinGenerator.IndexEntry leaf = closure.getFirst();
    final String leafFilename = leaf.filename();
    final String leafHash = leaf.sha256();
    final URI leafSnapshot = leaf.snapshot();
    assertEquals(SECURITY_LEAF_FILENAME, leafFilename);
    assertEquals("9999", leafHash);
    assertEquals(SECURITY_SNAPSHOT, leafSnapshot);
  }

  @Test
  void shouldKeepANewerVersionFromTheMainArchive() {
    final List<DebianPinGenerator.IndexEntry> closure = this.generator.resolveClosure(BOTH, List.of(ALTERNATIVE_PACKAGE));

    final DebianPinGenerator.IndexEntry alternative = closure.getFirst();
    final String alternativeFilename = alternative.filename();
    final URI alternativeSnapshot = alternative.snapshot();
    assertEquals(ALTERNATIVE_FILENAME, alternativeFilename);
    assertEquals(MAIN_SNAPSHOT, alternativeSnapshot);
  }

  @Test
  void shouldKeepTheMainArchiveForTheSameVersion() {
    final List<DebianPinGenerator.IndexEntry> closure = this.generator.resolveClosure(BOTH, List.of(UNUSED_PACKAGE));

    final DebianPinGenerator.IndexEntry unused = closure.getFirst();
    final URI unusedSnapshot = unused.snapshot();
    assertEquals(MAIN_SNAPSHOT, unusedSnapshot);
  }

  @Test
  void shouldReadABundleFromItsArgument() {
    final DebianPinGenerator.Bundle bundle = this.generator.parseBundle("debian-qemu-x86.json=qemu-system-x86,seabios");

    final DebianPinGenerator.Bundle expected = new DebianPinGenerator.Bundle(QEMU_BUNDLE_FILE, List.of("qemu-system-x86", "seabios"));
    assertEquals(expected, bundle);
  }

  @Test
  void shouldRefuseABundleWithoutAnEqualsSign() {
    final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () ->
      this.generator.parseBundle(QEMU_BUNDLE_FILE)
    );
    final String message = failure.getMessage();
    assertEquals("Bundle debian-qemu-x86.json must be a file name, an equals sign and its root packages!", message);
  }

  @Test
  void shouldFindTheFileFarmUrlThatTheArchiveRedirectsTo() throws IOException, InterruptedException {
    this.server.respondRedirect(ARCHIVE_PATH, FOUND_STATUS, FILE_FARM_PATH);
    final URI url = this.server.uri(ARCHIVE_PATH);

    final String mirror = this.generator.findMirror(this.client, url);
    final URI expected = this.server.uri(FILE_FARM_PATH);
    assertEquals(expected.toString(), mirror);
  }

  @Test
  void shouldRetryALookupThatFailed() throws IOException, InterruptedException {
    this.server.respond(ARCHIVE_PATH, UNAVAILABLE_STATUS, NO_BODY);
    this.server.respondRedirect(ARCHIVE_PATH, FOUND_STATUS, FILE_FARM_PATH);
    final URI url = this.server.uri(ARCHIVE_PATH);

    final String mirror = this.generator.findMirror(this.client, url);
    final URI expected = this.server.uri(FILE_FARM_PATH);
    assertEquals(expected.toString(), mirror);

    final int requests = this.server.getRequestCount(ARCHIVE_PATH);
    assertEquals(2, requests);
  }

  @Test
  void shouldFailWhenTheArchiveDoesNotRedirectToTheFileFarm() {
    this.server.respond(ARCHIVE_PATH, OK_STATUS, NO_BODY);
    this.server.respondRedirect(OTHER_ARCHIVE_PATH, FOUND_STATUS, ARCHIVE_PATH);
    final URI url = this.server.uri(ARCHIVE_PATH);
    final URI otherUrl = this.server.uri(OTHER_ARCHIVE_PATH);

    final IOException answered = assertThrows(IOException.class, () -> this.generator.findMirror(this.client, url));
    final IOException redirected = assertThrows(IOException.class, () -> this.generator.findMirror(this.client, otherUrl));
    final String answeredMessage = answered.getMessage();
    final String redirectedMessage = redirected.getMessage();
    final String expectedAnswered = NO_REDIRECT_MESSAGE.formatted(OK_STATUS, url);
    final String expectedRedirected = NO_REDIRECT_MESSAGE.formatted(FOUND_STATUS, otherUrl);
    assertEquals(expectedAnswered, answeredMessage);
    assertEquals(expectedRedirected, redirectedMessage);
  }

  @Test
  void shouldFailAfterTheLastAttempt() {
    this.server.respond(ARCHIVE_PATH, UNAVAILABLE_STATUS, NO_BODY);
    final URI url = this.server.uri(ARCHIVE_PATH);

    final IOException failure = assertThrows(IOException.class, () -> this.generator.findMirror(this.client, url));
    final String message = failure.getMessage();
    final String expected = NO_REDIRECT_MESSAGE.formatted(UNAVAILABLE_STATUS, url);
    assertEquals(expected, message);

    final int requests = this.server.getRequestCount(ARCHIVE_PATH);
    assertEquals(LOOKUP_ATTEMPTS, requests);
  }

  @Test
  void shouldFailWhenTheRedirectLeavesTheHost() {
    this.server.respondRedirect(ARCHIVE_PATH, FOUND_STATUS, OTHER_HOST_FILE_FARM_URL);
    final URI url = this.server.uri(ARCHIVE_PATH);

    final IOException failure = assertThrows(IOException.class, () -> this.generator.findMirror(this.client, url));
    final String message = failure.getMessage();
    final String expected = NO_REDIRECT_MESSAGE.formatted(FOUND_STATUS, url);
    assertEquals(expected, message);
  }
}
