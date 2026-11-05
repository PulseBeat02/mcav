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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import me.brandonli.mcav.testing.LocalHttpServer;
import me.brandonli.mcav.utils.http.HttpStatusException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class DebianSourcesTest {

  private static final String MAIN_PIN =
    "https://snapshot.debian.org/archive/debian/20261018T202950Z/pool/main/l/lame/libmp3lame0_3.100-6_amd64.deb";
  private static final String SECURITY_PIN =
    "https://snapshot.debian.org/archive/debian-security/20261018T181625Z/pool/updates/main/x/xz-utils/liblzma5_5.4.1-1+deb12u2_amd64.deb";

  private static final String OUTSIDE_URL = "https://ftp.debian.org/debian/pool/main/l/lame/libmp3lame0_3.100-6_amd64.deb";
  private static final String NO_SEGMENT_URL = "https://snapshot.debian.org/archive/debian";
  private static final String EMPTY_ARCHIVE_URL = "https://snapshot.debian.org/archive//20261018T202950Z/pool/main/l/lame/lame_1_amd64.deb";
  private static final String EMPTY_RUN_URL = "https://snapshot.debian.org/archive/debian//pool/main/l/lame/lame_1_amd64.deb";
  private static final String NO_RUN_URL = "https://snapshot.debian.org/archive/debian/pool/main/l/lame/lame_1_amd64.deb";
  private static final String NO_POOL_URL = "https://snapshot.debian.org/archive/debian/20261018T202950Z/dists/bookworm/Release";

  private static final String SNAPSHOT_ARCHIVE_PATH = "/snapshot/archive/";
  private static final String LIVE_ARCHIVE_PATH = "/live/";

  private static final String FIRST_SNAPSHOT_PATH = "/snapshot/archive/debian/20261018T202950Z/pool/main/f/first/first_1_amd64.deb";
  private static final String FIRST_LIVE_PATH = "/live/debian/pool/main/f/first/first_1_amd64.deb";
  private static final String SECOND_SNAPSHOT_PATH = "/snapshot/archive/debian/20261018T202950Z/pool/main/s/second/second_1_amd64.deb";
  private static final String SECOND_LIVE_PATH = "/live/debian/pool/main/s/second/second_1_amd64.deb";
  private static final String OUTSIDE_PATH = "/elsewhere/first_1_amd64.deb";
  private static final String FIRST_MIRROR_PATH = "/snapshot/file/1111/first_1_amd64.deb";
  private static final String SECOND_MIRROR_PATH = "/snapshot/file/2222/second_1_amd64.deb";

  private static final String ARCHIVE_FILE = "package.deb";

  private static final byte[] FIRST_PACKAGE = "first package".getBytes(StandardCharsets.UTF_8);
  private static final byte[] SECOND_PACKAGE = "second package".getBytes(StandardCharsets.UTF_8);
  private static final byte[] OTHER_FILE = "another file".getBytes(StandardCharsets.UTF_8);

  private static final long MAXIMUM_BYTES = 1024L;

  private static final int OK_STATUS = 200;
  private static final int UNAVAILABLE_STATUS = 503;
  private static final int GATEWAY_TIMEOUT_STATUS = 504;

  private static final String NOT_FOUND_MESSAGE = "Request for %s got status 404, which is not a success!";

  private static final int RETRIED_ATTEMPTS = 3;

  private DebianSources debian;
  private LocalHttpServer server;
  private DebianSources sources;
  private Path archive;

  @BeforeEach
  void setUp(@TempDir final Path folder) {
    this.debian = DebianSources.ofDebian();
    this.server = LocalHttpServer.start();
    final URI snapshotArchive = this.server.uri(SNAPSHOT_ARCHIVE_PATH);
    final URI liveArchive = this.server.uri(LIVE_ARCHIVE_PATH);
    this.sources = new DebianSources(snapshotArchive.toString(), liveArchive.toString());
    this.archive = folder.resolve(ARCHIVE_FILE);
  }

  @AfterEach
  void tearDown() {
    this.server.close();
  }

  @Test
  void shouldMapAMainPinToTheLivePool() {
    final Optional<String> liveUrl = this.debian.findLiveUrl(MAIN_PIN);

    final Optional<String> expected = Optional.of("https://deb.debian.org/debian/pool/main/l/lame/libmp3lame0_3.100-6_amd64.deb");
    assertEquals(expected, liveUrl);
  }

  @Test
  void shouldMapASecurityPinToTheLiveSecurityPool() {
    final Optional<String> liveUrl = this.debian.findLiveUrl(SECURITY_PIN);

    final Optional<String> expected = Optional.of(
      "https://deb.debian.org/debian-security/pool/updates/main/x/xz-utils/liblzma5_5.4.1-1+deb12u2_amd64.deb"
    );
    assertEquals(expected, liveUrl);
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = { OUTSIDE_URL, NO_SEGMENT_URL, EMPTY_ARCHIVE_URL, EMPTY_RUN_URL, NO_RUN_URL, NO_POOL_URL })
  void shouldFindNoLiveUrlForAUrlThatIsNotAPinnedPoolFile(final String url) {
    final Optional<String> liveUrl = this.debian.findLiveUrl(url);

    assertEquals(Optional.empty(), liveUrl);
  }

  @Test
  void shouldDownloadFromTheLivePoolWhenItHasThePackage() throws IOException {
    this.server.respond(FIRST_LIVE_PATH, OK_STATUS, FIRST_PACKAGE);
    this.server.respond(FIRST_SNAPSHOT_PATH, OK_STATUS, FIRST_PACKAGE);

    this.download(FIRST_SNAPSHOT_PATH, FIRST_PACKAGE);

    final byte[] downloaded = Files.readAllBytes(this.archive);
    assertArrayEquals(FIRST_PACKAGE, downloaded);

    final int snapshotRequests = this.server.getRequestCount(FIRST_SNAPSHOT_PATH);
    assertEquals(0, snapshotRequests);
  }

  @Test
  void shouldDownloadFromTheSnapshotWhenTheLivePoolLacksThePackage() throws IOException {
    this.server.respond(FIRST_SNAPSHOT_PATH, OK_STATUS, FIRST_PACKAGE);

    this.download(FIRST_SNAPSHOT_PATH, FIRST_PACKAGE);

    final byte[] downloaded = Files.readAllBytes(this.archive);
    assertArrayEquals(FIRST_PACKAGE, downloaded);

    final int liveRequests = this.server.getRequestCount(FIRST_LIVE_PATH);
    assertEquals(1, liveRequests);
  }

  @Test
  void shouldDownloadFromTheSnapshotWhenTheLivePoolFails() throws IOException {
    this.server.respond(FIRST_LIVE_PATH, UNAVAILABLE_STATUS, OTHER_FILE);
    this.server.respond(FIRST_SNAPSHOT_PATH, OK_STATUS, FIRST_PACKAGE);

    this.download(FIRST_SNAPSHOT_PATH, FIRST_PACKAGE);

    final byte[] downloaded = Files.readAllBytes(this.archive);
    assertArrayEquals(FIRST_PACKAGE, downloaded);

    final int liveRequests = this.server.getRequestCount(FIRST_LIVE_PATH);
    assertEquals(RETRIED_ATTEMPTS, liveRequests);
  }

  @Test
  void shouldDownloadFromTheSnapshotWhenTheLivePoolServesAnotherFile() throws IOException {
    this.server.respond(FIRST_LIVE_PATH, OK_STATUS, OTHER_FILE);
    this.server.respond(FIRST_SNAPSHOT_PATH, OK_STATUS, FIRST_PACKAGE);

    this.download(FIRST_SNAPSHOT_PATH, FIRST_PACKAGE);

    final byte[] downloaded = Files.readAllBytes(this.archive);
    assertArrayEquals(FIRST_PACKAGE, downloaded);
  }

  @Test
  void shouldTryAFailedSourceLastForTheNextPackages() throws IOException {
    this.server.respond(FIRST_SNAPSHOT_PATH, OK_STATUS, FIRST_PACKAGE);
    this.server.respond(SECOND_LIVE_PATH, OK_STATUS, SECOND_PACKAGE);

    this.download(FIRST_SNAPSHOT_PATH, FIRST_PACKAGE);
    this.download(SECOND_SNAPSHOT_PATH, SECOND_PACKAGE);

    final byte[] downloaded = Files.readAllBytes(this.archive);
    assertArrayEquals(SECOND_PACKAGE, downloaded);

    final int secondSnapshotRequests = this.server.getRequestCount(SECOND_SNAPSHOT_PATH);
    final int secondLiveRequests = this.server.getRequestCount(SECOND_LIVE_PATH);
    assertEquals(1, secondSnapshotRequests);
    assertEquals(1, secondLiveRequests);
  }

  @Test
  void shouldReportEverySourceWhenNoneHasThePackage() {
    final HttpStatusException exception = assertThrows(HttpStatusException.class, () -> this.download(FIRST_SNAPSHOT_PATH, FIRST_PACKAGE));

    final Throwable[] suppressed = exception.getSuppressed();
    assertEquals(1, suppressed.length);
    assertInstanceOf(HttpStatusException.class, suppressed[0]);

    final boolean archiveExists = Files.exists(this.archive);
    assertFalse(archiveExists);
  }

  @Test
  void shouldDownloadFromTheMirrorWhenTheLivePoolLacksThePackage() throws IOException {
    this.server.respond(FIRST_MIRROR_PATH, OK_STATUS, FIRST_PACKAGE);
    this.server.respond(FIRST_SNAPSHOT_PATH, GATEWAY_TIMEOUT_STATUS, OTHER_FILE);

    this.downloadWithMirror(FIRST_SNAPSHOT_PATH, FIRST_MIRROR_PATH, FIRST_PACKAGE);

    final byte[] downloaded = Files.readAllBytes(this.archive);
    assertArrayEquals(FIRST_PACKAGE, downloaded);

    final int liveRequests = this.server.getRequestCount(FIRST_LIVE_PATH);
    final int snapshotRequests = this.server.getRequestCount(FIRST_SNAPSHOT_PATH);
    assertEquals(1, liveRequests);
    assertEquals(0, snapshotRequests);
  }

  @Test
  void shouldDownloadFromTheSnapshotWhenTheMirrorFails() throws IOException {
    this.server.respond(FIRST_SNAPSHOT_PATH, OK_STATUS, FIRST_PACKAGE);

    this.downloadWithMirror(FIRST_SNAPSHOT_PATH, FIRST_MIRROR_PATH, FIRST_PACKAGE);

    final byte[] downloaded = Files.readAllBytes(this.archive);
    assertArrayEquals(FIRST_PACKAGE, downloaded);

    final int liveRequests = this.server.getRequestCount(FIRST_LIVE_PATH);
    final int mirrorRequests = this.server.getRequestCount(FIRST_MIRROR_PATH);
    assertEquals(1, liveRequests);
    assertEquals(1, mirrorRequests);
  }

  @Test
  void shouldTryTheMirrorFirstAfterTheLivePoolFailed() throws IOException {
    this.server.respond(FIRST_MIRROR_PATH, OK_STATUS, FIRST_PACKAGE);
    this.server.respond(SECOND_LIVE_PATH, OK_STATUS, SECOND_PACKAGE);
    this.server.respond(SECOND_MIRROR_PATH, OK_STATUS, SECOND_PACKAGE);

    this.downloadWithMirror(FIRST_SNAPSHOT_PATH, FIRST_MIRROR_PATH, FIRST_PACKAGE);
    this.downloadWithMirror(SECOND_SNAPSHOT_PATH, SECOND_MIRROR_PATH, SECOND_PACKAGE);

    final byte[] downloaded = Files.readAllBytes(this.archive);
    assertArrayEquals(SECOND_PACKAGE, downloaded);

    final int firstLiveRequests = this.server.getRequestCount(FIRST_LIVE_PATH);
    final int secondLiveRequests = this.server.getRequestCount(SECOND_LIVE_PATH);
    final int secondMirrorRequests = this.server.getRequestCount(SECOND_MIRROR_PATH);
    assertEquals(1, firstLiveRequests);
    assertEquals(0, secondLiveRequests);
    assertEquals(1, secondMirrorRequests);
  }

  @Test
  void shouldReportTheLiveFailureWithTheOtherTwoSuppressed() {
    final HttpStatusException exception = assertThrows(HttpStatusException.class, () ->
      this.downloadWithMirror(FIRST_SNAPSHOT_PATH, FIRST_MIRROR_PATH, FIRST_PACKAGE)
    );

    final String message = exception.getMessage();
    final URI liveUri = this.server.uri(FIRST_LIVE_PATH);
    final String expected = NOT_FOUND_MESSAGE.formatted(liveUri);
    assertEquals(expected, message);

    final Throwable[] suppressed = exception.getSuppressed();
    assertEquals(2, suppressed.length);

    final String mirrorMessage = suppressed[0].getMessage();
    final String snapshotMessage = suppressed[1].getMessage();
    final URI mirrorUri = this.server.uri(FIRST_MIRROR_PATH);
    final URI snapshotUri = this.server.uri(FIRST_SNAPSHOT_PATH);
    final String expectedMirrorMessage = NOT_FOUND_MESSAGE.formatted(mirrorUri);
    final String expectedSnapshotMessage = NOT_FOUND_MESSAGE.formatted(snapshotUri);
    assertEquals(expectedMirrorMessage, mirrorMessage);
    assertEquals(expectedSnapshotMessage, snapshotMessage);
  }

  @Test
  void shouldDownloadOnlyThePinnedUrlOutsideTheSnapshotArchive() throws IOException {
    this.server.respond(OUTSIDE_PATH, OK_STATUS, FIRST_PACKAGE);

    this.download(OUTSIDE_PATH, FIRST_PACKAGE);

    final byte[] downloaded = Files.readAllBytes(this.archive);
    assertArrayEquals(FIRST_PACKAGE, downloaded);

    final int requests = this.server.getTotalRequestCount();
    assertEquals(1, requests);
  }

  private void download(final String pinnedPath, final byte[] expected) {
    final URI pinnedUri = this.server.uri(pinnedPath);
    final HashCode hash = Hashing.sha256().hashBytes(expected);
    this.sources.download(pinnedUri, null, this.archive, hash.toString(), MAXIMUM_BYTES);
  }

  private void downloadWithMirror(final String pinnedPath, final String mirrorPath, final byte[] expected) {
    final URI pinnedUri = this.server.uri(pinnedPath);
    final URI mirrorUri = this.server.uri(mirrorPath);
    final HashCode hash = Hashing.sha256().hashBytes(expected);
    this.sources.download(pinnedUri, mirrorUri, this.archive, hash.toString(), MAXIMUM_BYTES);
  }
}
