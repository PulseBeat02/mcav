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
package me.brandonli.mcav.utils.http;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.code_intelligence.jazzer.api.BugDetectors;
import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.api.SilentCloseable;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import me.brandonli.mcav.testing.LocalHttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;

@Tag("fuzz")
final class HttpDownloaderFuzzTest {

  private static final String FILE_PATH = "/file";
  private static final String MOVED_PATH_PREFIX = "/moved/";

  private static final byte[] CONTENT = "pinned content".getBytes(StandardCharsets.UTF_8);
  private static final byte[] OTHER_CONTENT = "other content".getBytes(StandardCharsets.UTF_8);

  private static final int[] REDIRECT_STATUSES = { 301, 302, 303, 307, 308 };

  private static final int RELATIVE_LOCATION = 0;
  private static final int OWN_ORIGIN = 1;
  private static final int OTHER_ORIGIN = 2;

  private LocalHttpServer server;
  private LocalHttpServer other;
  private long redirects;

  @BeforeEach
  void setUp() {
    this.server = LocalHttpServer.start();
    this.other = LocalHttpServer.start();
    this.server.respond(FILE_PATH, 200, CONTENT);
    this.other.respond(FILE_PATH, 200, OTHER_CONTENT);
  }

  private boolean isLocalServer(final String host, final int port) {
    return this.server.isServing(host, port) || this.other.isServing(host, port);
  }

  @AfterEach
  void tearDown() {
    this.server.close();
    this.other.close();
  }

  @FuzzTest(maxDuration = "30s")
  void shouldFollowARedirectWithinItsOriginOnlyOrRefuseIt(final FuzzedDataProvider data) {
    // Jazzer blocks every connection in the target, so this allows the local servers until the next fuzz test
    final SilentCloseable _ = BugDetectors.allowNetworkConnections(this::isLocalServer);
    final int status = data.pickValue(REDIRECT_STATUSES);
    final int origin = data.consumeInt(RELATIVE_LOCATION, OTHER_ORIGIN);
    final String suffix = data.consumeRemainingAsString();
    final String location = this.originOf(origin) + suffix;
    this.redirects++;
    final String path = MOVED_PATH_PREFIX + this.redirects;
    this.server.respondRedirect(path, status, location);
    final URI uri = this.server.uri(path);

    final byte[] content;
    try (final InputStream body = HttpDownloader.openPinnedStream(uri)) {
      content = body.readAllBytes();
    } catch (final IOException | DownloadException exception) {
      this.assertOtherNeverAsked();
      return;
    }

    assertArrayEquals(CONTENT, content);

    this.assertOtherNeverAsked();
  }

  private String originOf(final int origin) {
    if (origin == RELATIVE_LOCATION) {
      return "";
    }

    final LocalHttpServer target = origin == OWN_ORIGIN ? this.server : this.other;
    final URI root = target.uri("");
    return root.toString();
  }

  private void assertOtherNeverAsked() {
    final int otherRequests = this.other.getTotalRequestCount();
    assertEquals(0, otherRequests);
  }
}
