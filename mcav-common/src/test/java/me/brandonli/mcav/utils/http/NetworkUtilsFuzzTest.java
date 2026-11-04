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

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.code_intelligence.jazzer.api.BugDetectors;
import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.api.SilentCloseable;
import com.code_intelligence.jazzer.junit.FuzzTest;
import com.google.common.net.InetAddresses;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import me.brandonli.mcav.testing.LocalHttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;

@Tag("fuzz")
final class NetworkUtilsFuzzTest {

  private static final String ANSWER_PATH_PREFIX = "/address/";

  private static final int ANSWER_LIMIT_BYTES = 256;

  private static final int LOWEST_STATUS = 200;
  private static final int HIGHEST_STATUS = 599;

  private LocalHttpServer server;
  private long answers;

  @BeforeEach
  void setUp() {
    this.server = LocalHttpServer.start();
  }

  @AfterEach
  void tearDown() {
    this.server.close();
  }

  @FuzzTest(maxDuration = "30s")
  void shouldTakeOnlyAnAddressThatTheServiceAnsweredWithSuccess(final FuzzedDataProvider data) {
    // Jazzer blocks every connection in the target, so this allows the local servers until the next fuzz test
    final SilentCloseable _ = BugDetectors.allowNetworkConnections(this.server::isServing);
    final int status = data.consumeInt(LOWEST_STATUS, HIGHEST_STATUS);
    final byte[] body = data.consumeRemainingAsBytes();
    this.answers++;
    final String path = ANSWER_PATH_PREFIX + this.answers;
    this.server.respond(path, status, body);
    final URI service = this.server.uri(path);

    final Optional<String> address = NetworkUtils.lookUpPublicAddress(service);
    final Optional<String> expected = this.findExpectedAddress(status, body);
    assertEquals(expected, address);
  }

  private Optional<String> findExpectedAddress(final int status, final byte[] body) {
    final String answer = new String(body, StandardCharsets.UTF_8);
    final String trimmed = answer.trim();
    final boolean accepted = status == 200 && body.length <= ANSWER_LIMIT_BYTES && InetAddresses.isInetAddress(trimmed);
    if (!accepted) {
      return Optional.empty();
    }

    return Optional.of(trimmed);
  }
}
