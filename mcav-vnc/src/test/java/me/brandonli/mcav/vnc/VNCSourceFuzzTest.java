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
package me.brandonli.mcav.vnc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import org.junit.jupiter.api.Tag;

/**
 * Fuzzes the connection settings of a VNC source, which the sandbox builds from what an operator types: every setting
 * is either refused as {@link VNCSource.Builder} documents or ends up in the source exactly as given, two sources of the
 * same settings are equal with the same hash, and the source names its host and port. mcav does not read the RFB
 * protocol itself, that is {@code com.shinyhut:vernacular}, so this is not RFB coverage; the frames a server sends are
 * converted by the code {@code BufferedImageConversionFuzzTest} in mcav-common fuzzes.
 */
@Tag("fuzz")
final class VNCSourceFuzzTest {

  @FuzzTest(maxDuration = "30s")
  void keepsEveryAcceptedSettingAsGiven(final FuzzedDataProvider data) {
    final String host = data.consumeString(64);
    final int port = data.consumeInt();
    final String username = data.consumeString(32);
    final String password = data.consumeString(32);
    final int width = data.consumeInt();
    final int height = data.consumeInt();
    final int frameRate = data.consumeInt();

    final VNCSource first;
    final VNCSource second;
    try {
      first = build(host, port, username, password, width, height, frameRate);
      second = build(host, port, username, password, width, height, frameRate);
    } catch (final IllegalArgumentException refused) {
      final boolean documented = host.isBlank() || port < 1 || port > 65_535 || width < 0 || height < 0 || frameRate < 1;
      assertTrue(documented, () -> "settings refused without a documented reason: " + refused.getMessage());
      return;
    }
    assertEquals(host, first.getHost());
    assertEquals(port, first.getPort());
    assertEquals(username, first.getUsername());
    assertEquals(password, first.getPassword());
    assertEquals(width, first.getScreenWidth());
    assertEquals(height, first.getScreenHeight());
    assertEquals(frameRate, first.getTargetFrameRate());
    assertEquals(first, second, "two sources of the same settings are equal");
    assertEquals(first.hashCode(), second.hashCode(), "and have the same hash");
    final String resource = first.getResource();
    assertTrue(resource.contains(host) && resource.endsWith(":" + port), () -> "the resource " + resource + " names the host and the port");
  }

  private static VNCSource build(
    final String host,
    final int port,
    final String username,
    final String password,
    final int width,
    final int height,
    final int frameRate
  ) {
    final VNCSource.Builder builder = VNCSource.builder();
    builder.host(host);
    builder.port(port);
    builder.username(username);
    builder.password(password);
    builder.screenWidth(width);
    builder.screenHeight(height);
    builder.targetFrameRate(frameRate);
    return builder.build();
  }
}
