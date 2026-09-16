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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.function.Consumer;
import me.brandonli.mcav.vnc.testing.EqualityAssertions;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link VNCSource}, {@link VNCSourceImpl} and its builder.
 */
final class VNCSourceTest {

  private static VNCSource.Builder fullBuilder() {
    final VNCSource.Builder builder = VNCSource.builder();
    builder.host("example.org");
    builder.port(5901);
    builder.username("user");
    builder.password("secret");
    builder.screenWidth(640);
    builder.screenHeight(480);
    builder.targetFrameRate(24);
    return builder;
  }

  private static VNCSource build(final Consumer<VNCSource.Builder> change) {
    final VNCSource.Builder builder = fullBuilder();
    change.accept(builder);
    return builder.build();
  }

  @Test
  void buildsSourcesWithEverySetting() {
    final VNCSource.Builder builder = fullBuilder();
    final VNCSource source = builder.build();
    final String host = source.getHost();
    final int port = source.getPort();
    final String username = source.getUsername();
    final String password = source.getPassword();
    final int width = source.getScreenWidth();
    final int height = source.getScreenHeight();
    final int frameRate = source.getTargetFrameRate();
    assertEquals("example.org", host);
    assertEquals(5901, port);
    assertEquals("user", username);
    assertEquals("secret", password);
    assertEquals(640, width);
    assertEquals(480, height);
    assertEquals(24, frameRate);
  }

  @Test
  void usesTheDefaultPortFrameRateAndRemoteSize() {
    final VNCSource.Builder builder = VNCSource.builder();
    builder.host("localhost");
    final VNCSource source = builder.build();
    final int port = source.getPort();
    final int frameRate = source.getTargetFrameRate();
    final int width = source.getScreenWidth();
    final int height = source.getScreenHeight();
    final String username = source.getUsername();
    final String password = source.getPassword();
    assertEquals(VNCSource.DEFAULT_PORT, port);
    assertEquals(5900, port);
    assertEquals(VNCSource.DEFAULT_FRAME_RATE, frameRate);
    assertEquals(0, width);
    assertEquals(0, height);
    assertNull(username);
    assertNull(password);
  }

  @Test
  void describesTheServerAsAVncUri() {
    final VNCSource.Builder builder = fullBuilder();
    final VNCSource source = builder.build();
    final String resource = source.getResource();
    final String name = source.getName();
    final String text = source.toString();
    assertEquals("vnc://example.org:5901", resource);
    assertEquals("vnc", name);
    assertEquals("VNCSource[vnc://example.org:5901, 640x480@24]", text);
  }

  @Test
  void acceptsTheLimitsOfEveryRange() {
    final VNCSource.Builder builder = VNCSource.builder();
    final VNCSource.Builder hostResult = builder.host("h");
    final VNCSource.Builder lowPort = builder.port(1);
    final VNCSource.Builder highPort = builder.port(65535);
    final VNCSource.Builder width = builder.screenWidth(0);
    final VNCSource.Builder height = builder.screenHeight(0);
    final VNCSource.Builder frameRate = builder.targetFrameRate(1);
    final VNCSource.Builder username = builder.username("");
    final VNCSource.Builder password = builder.password("");
    assertSame(builder, hostResult);
    assertSame(builder, lowPort);
    assertSame(builder, highPort);
    assertSame(builder, width);
    assertSame(builder, height);
    assertSame(builder, frameRate);
    assertSame(builder, username);
    assertSame(builder, password);
    final VNCSource source = builder.build();
    final int port = source.getPort();
    assertEquals(65535, port);
  }

  @Test
  void rejectsInvalidSettings() {
    final VNCSource.Builder builder = VNCSource.builder();
    assertThrows(NullPointerException.class, () -> builder.host(null));
    assertThrows(IllegalArgumentException.class, () -> builder.host(" "));
    assertThrows(IllegalArgumentException.class, () -> builder.port(0));
    assertThrows(IllegalArgumentException.class, () -> builder.port(65536));
    assertThrows(NullPointerException.class, () -> builder.username(null));
    assertThrows(NullPointerException.class, () -> builder.password(null));
    assertThrows(IllegalArgumentException.class, () -> builder.screenWidth(-1));
    assertThrows(IllegalArgumentException.class, () -> builder.screenHeight(-1));
    assertThrows(IllegalArgumentException.class, () -> builder.targetFrameRate(0));
  }

  @Test
  void requiresAHost() {
    final VNCSource.Builder builder = VNCSource.builder();
    builder.port(5901);
    assertThrows(IllegalStateException.class, builder::build);
  }

  @Test
  void comparesEverySetting() {
    final VNCSource.Builder sourceBuilder = fullBuilder();
    final VNCSource source = sourceBuilder.build();
    final VNCSource.Builder equalBuilder = fullBuilder();
    final VNCSource equal = equalBuilder.build();
    final VNCSource otherHost = build(builder -> builder.host("example.com"));
    final VNCSource otherPort = build(builder -> builder.port(5902));
    final VNCSource otherUser = build(builder -> builder.username("admin"));
    final VNCSource otherPassword = build(builder -> builder.password("hunter2"));
    final VNCSource otherWidth = build(builder -> builder.screenWidth(320));
    final VNCSource otherHeight = build(builder -> builder.screenHeight(240));
    final VNCSource otherFrameRate = build(builder -> builder.targetFrameRate(60));
    final VNCSource.Builder anonymousBuilder = VNCSource.builder();
    anonymousBuilder.host("example.org");
    anonymousBuilder.port(5901);
    anonymousBuilder.screenWidth(640);
    anonymousBuilder.screenHeight(480);
    anonymousBuilder.targetFrameRate(24);
    final VNCSource anonymous = anonymousBuilder.build();
    EqualityAssertions.assertEqualityContract(
      source,
      equal,
      otherHost,
      otherPort,
      otherUser,
      otherPassword,
      otherWidth,
      otherHeight,
      otherFrameRate,
      anonymous
    );
  }
}
