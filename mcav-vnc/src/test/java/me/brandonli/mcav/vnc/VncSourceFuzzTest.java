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
import java.util.Optional;
import org.junit.jupiter.api.Tag;

// Any setting is refused as VncSourceBuilder documents or kept as given; RfbGuardFuzzTest covers the protocol itself.
@Tag("fuzz")
final class VncSourceFuzzTest {

  @FuzzTest(maxDuration = "30s")
  void shouldKeepEveryAcceptedSettingAsGiven(final FuzzedDataProvider data) {
    final String host = data.consumeString(64);
    final int port = data.consumeInt();
    final String username = data.consumeString(32);
    final String password = data.consumeString(32);
    final int width = data.consumeInt();
    final int height = data.consumeInt();
    final int frameRate = data.consumeInt();

    final VncSource first;
    final VncSource second;
    try {
      first = this.build(host, port, username, password, width, height, frameRate);
      second = this.build(host, port, username, password, width, height, frameRate);
    } catch (final IllegalArgumentException exception) {
      final boolean documented = host.isBlank() || port < 1 || port > 65_535 || width < 0 || height < 0 || frameRate < 1;
      assertTrue(documented, () -> "settings refused without a documented reason: %s".formatted(exception.getMessage()));
      return;
    }
    final String builtHost = first.getHost();
    final int builtPort = first.getPort();
    final Optional<String> builtUsername = first.getUsername();
    final Optional<String> builtPassword = first.getPassword();
    final int builtWidth = first.getScreenWidth();
    final int builtHeight = first.getScreenHeight();
    final int builtFrameRate = first.getTargetFrameRate();
    final Optional<String> expectedUsername = Optional.of(username);
    final Optional<String> expectedPassword = Optional.of(password);
    assertEquals(host, builtHost);
    assertEquals(port, builtPort);
    assertEquals(expectedUsername, builtUsername);
    assertEquals(expectedPassword, builtPassword);
    assertEquals(width, builtWidth);
    assertEquals(height, builtHeight);
    assertEquals(frameRate, builtFrameRate);

    final int firstHash = first.hashCode();
    final int secondHash = second.hashCode();
    assertEquals(first, second, "two sources of the same settings are equal");
    assertEquals(firstHash, secondHash, "and have the same hash");

    final String resource = first.getResource();
    final boolean named = resource.contains(host) && resource.endsWith(":%s".formatted(port));
    assertTrue(named, () -> "the resource %s names the host and the port".formatted(resource));
  }

  private VncSource build(
    final String host,
    final int port,
    final String username,
    final String password,
    final int width,
    final int height,
    final int frameRate
  ) {
    final VncSourceBuilder builder = VncSource.builder();
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
