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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import me.brandonli.mcav.vnc.testing.EqualityAssertions;
import org.junit.jupiter.api.Test;

final class VncSourceTest {

  private static final String HOST = "example.org";
  private static final int PORT = 5901;

  private static final int WIDTH = 640;
  private static final int HEIGHT = 480;

  private static final int FRAME_RATE = 24;

  private VncSourceBuilder fullBuilder() {
    final VncSourceBuilder builder = VncSource.builder();
    builder.host(HOST);
    builder.port(PORT);
    builder.username("user");
    builder.password("secret");
    builder.screenWidth(WIDTH);
    builder.screenHeight(HEIGHT);
    builder.targetFrameRate(FRAME_RATE);
    return builder;
  }

  private VncSource build(final Consumer<VncSourceBuilder> change) {
    final VncSourceBuilder builder = this.fullBuilder();
    change.accept(builder);
    return builder.build();
  }

  @Test
  void shouldAvoidAConstantHashAcrossRepresentativeSourceKeys() {
    final Set<Integer> hashes = new HashSet<>();
    for (int index = 0; index < 64; index++) {
      final VncSourceBuilder builder = this.fullBuilder();
      builder.host("desktop-" + index + ".example.org");
      builder.port(PORT + index);
      builder.screenWidth(WIDTH + index * 16);
      final VncSource source = builder.build();
      final int hash = source.hashCode();
      hashes.add(hash);
    }
    final int distinctHashes = hashes.size();
    assertTrue(distinctHashes > 1, "representative source keys must not all use the same hash");
  }

  @Test
  void shouldBuildSourcesWithEverySetting() {
    final VncSourceBuilder builder = this.fullBuilder();
    final VncSource source = builder.build();
    final String host = source.getHost();
    final int port = source.getPort();
    final Optional<String> username = source.getUsername();
    final Optional<String> password = source.getPassword();
    final int width = source.getScreenWidth();
    final int height = source.getScreenHeight();
    final int frameRate = source.getTargetFrameRate();
    assertEquals(HOST, host);
    assertEquals(PORT, port);
    assertEquals(Optional.of("user"), username);
    assertEquals(Optional.of("secret"), password);
    assertEquals(WIDTH, width);
    assertEquals(HEIGHT, height);
    assertEquals(FRAME_RATE, frameRate);
  }

  @Test
  void shouldUseTheDefaultPortFrameRateAndRemoteSize() {
    final VncSourceBuilder builder = VncSource.builder();
    builder.host("localhost");
    final VncSource source = builder.build();
    final int port = source.getPort();
    final int frameRate = source.getTargetFrameRate();
    final int width = source.getScreenWidth();
    final int height = source.getScreenHeight();
    final Optional<String> username = source.getUsername();
    final Optional<String> password = source.getPassword();
    assertEquals(VncSource.DEFAULT_PORT, port);
    assertEquals(5900, port);
    assertEquals(VncSource.DEFAULT_FRAME_RATE, frameRate);
    assertEquals(0, width);
    assertEquals(0, height);
    assertEquals(Optional.empty(), username);
    assertEquals(Optional.empty(), password);
  }

  @Test
  void shouldDescribeTheServerAsAVncUri() {
    final VncSourceBuilder builder = this.fullBuilder();
    final VncSource source = builder.build();
    final String resource = source.getResource();
    final String name = source.getName();
    final String text = source.toString();
    assertEquals("vnc://example.org:5901", resource);
    assertEquals("vnc", name);
    assertEquals("DefaultVncSource{host=example.org, port=5901, width=640, height=480, targetFrameRate=24}", text);
  }

  @Test
  void shouldAcceptTheLimitsOfEveryRange() {
    final VncSourceBuilder builder = VncSource.builder();
    final VncSourceBuilder hostResult = builder.host("h");
    final VncSourceBuilder lowPort = builder.port(1);
    final VncSourceBuilder highPort = builder.port(65535);
    final VncSourceBuilder width = builder.screenWidth(0);
    final VncSourceBuilder height = builder.screenHeight(0);
    final VncSourceBuilder frameRate = builder.targetFrameRate(1);
    final VncSourceBuilder username = builder.username("");
    final VncSourceBuilder password = builder.password("");
    assertSame(builder, hostResult);
    assertSame(builder, lowPort);
    assertSame(builder, highPort);
    assertSame(builder, width);
    assertSame(builder, height);
    assertSame(builder, frameRate);
    assertSame(builder, username);
    assertSame(builder, password);
    final VncSource source = builder.build();
    final int port = source.getPort();
    assertEquals(65535, port);
  }

  @Test
  void shouldRejectInvalidSettings() {
    final VncSourceBuilder builder = VncSource.builder();
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
  void shouldRequireAHost() {
    final VncSourceBuilder builder = VncSource.builder();
    builder.port(PORT);
    assertThrows(IllegalStateException.class, builder::build);
  }

  @Test
  void shouldKeepItsSettingsWhenTheBuilderChangesLater() {
    final VncSourceBuilder builder = this.fullBuilder();
    final VncSource source = builder.build();
    builder.host("example.com").port(5902).username("admin").password("hunter2");
    builder.screenWidth(320).screenHeight(240).targetFrameRate(60);

    final VncSourceBuilder unchangedBuilder = this.fullBuilder();
    final VncSource unchanged = unchangedBuilder.build();
    assertEquals(unchanged, source);
  }

  @Test
  void shouldCompareEverySetting() {
    final VncSourceBuilder sourceBuilder = this.fullBuilder();
    final VncSource source = sourceBuilder.build();
    final VncSourceBuilder equalBuilder = this.fullBuilder();
    final VncSource equal = equalBuilder.build();
    final VncSource otherHost = this.build(builder -> builder.host("example.com"));
    final VncSource otherPort = this.build(builder -> builder.port(5902));
    final VncSource otherUser = this.build(builder -> builder.username("admin"));
    final VncSource otherPassword = this.build(builder -> builder.password("hunter2"));
    final VncSource otherWidth = this.build(builder -> builder.screenWidth(320));
    final VncSource otherHeight = this.build(builder -> builder.screenHeight(240));
    final VncSource otherFrameRate = this.build(builder -> builder.targetFrameRate(60));
    final VncSourceBuilder anonymousBuilder = VncSource.builder();
    anonymousBuilder.host(HOST);
    anonymousBuilder.port(PORT);
    anonymousBuilder.screenWidth(WIDTH);
    anonymousBuilder.screenHeight(HEIGHT);
    anonymousBuilder.targetFrameRate(FRAME_RATE);
    final VncSource anonymous = anonymousBuilder.build();
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
