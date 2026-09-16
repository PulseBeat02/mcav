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
package me.brandonli.mcav.vm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import me.brandonli.mcav.vm.testing.EqualityAssertions;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link VMSettings}.
 */
final class VMSettingsTest {

  @Test
  void keepsTheGivenValues() {
    final VMSettings settings = VMSettings.of(5905, 640, 480, 25);
    final int port = settings.getPort();
    final int width = settings.getWidth();
    final int height = settings.getHeight();
    final int frameRate = settings.getTargetFps();
    assertEquals(5905, port);
    assertEquals(640, width);
    assertEquals(480, height);
    assertEquals(25, frameRate);
  }

  @Test
  void picksAFreeVncPort() throws IOException {
    final VMSettings settings = VMSettings.of(320, 200, 10);
    final int port = settings.getPort();
    final int width = settings.getWidth();
    final boolean vncRange = port >= VMProcess.FIRST_VNC_PORT && port <= 65535;
    assertTrue(vncRange, () -> "port " + port);
    assertEquals(320, width);
    final InetAddress loopback = InetAddress.ofLiteral("127.0.0.1");
    try (final ServerSocket socket = new ServerSocket(port, 1, loopback)) {
      final int bound = socket.getLocalPort();
      assertEquals(port, bound);
    }
  }

  @Test
  void acceptsTheLimitsOfEveryRange() {
    final VMSettings lowest = VMSettings.of(5900, 1, 1, 1);
    final VMSettings highest = VMSettings.of(65535, 1, 1, 1);
    final int lowestPort = lowest.getPort();
    final int highestPort = highest.getPort();
    assertEquals(5900, lowestPort);
    assertEquals(65535, highestPort);
  }

  @Test
  void rejectsInvalidValues() {
    assertThrows(IllegalArgumentException.class, () -> VMSettings.of(5899, 640, 480, 30));
    assertThrows(IllegalArgumentException.class, () -> VMSettings.of(65536, 640, 480, 30));
    assertThrows(IllegalArgumentException.class, () -> VMSettings.of(5900, 0, 480, 30));
    assertThrows(IllegalArgumentException.class, () -> VMSettings.of(5900, 640, 0, 30));
    assertThrows(IllegalArgumentException.class, () -> VMSettings.of(5900, 640, 480, 0));
    assertThrows(IllegalArgumentException.class, () -> VMSettings.of(0, 480, 30));
    assertThrows(IllegalArgumentException.class, () -> VMSettings.of(640, 0, 30));
    assertThrows(IllegalArgumentException.class, () -> VMSettings.of(640, 480, 0));
  }

  @Test
  void comparesEveryValue() {
    final VMSettings settings = VMSettings.of(5901, 640, 480, 30);
    final VMSettings equal = VMSettings.of(5901, 640, 480, 30);
    final VMSettings otherPort = VMSettings.of(5902, 640, 480, 30);
    final VMSettings otherWidth = VMSettings.of(5901, 641, 480, 30);
    final VMSettings otherHeight = VMSettings.of(5901, 640, 481, 30);
    final VMSettings otherFrameRate = VMSettings.of(5901, 640, 480, 31);
    EqualityAssertions.assertEqualityContract(settings, equal, otherPort, otherWidth, otherHeight, otherFrameRate);
  }

  @Test
  void buildsTheHashCodeFromEveryValueInOrder() {
    final VMSettings settings = VMSettings.of(5901, 640, 480, 30);
    final int hash = settings.hashCode();
    // ((port * 31 + width) * 31 + height) * 31 + frameRate, so every value changes the hash on its own
    final int expected = ((5901 * 31 + 640) * 31 + 480) * 31 + 30;
    assertEquals(expected, hash);
  }

  @Test
  void givesSettingsThatDifferInOneValueDifferentHashCodes() {
    final VMSettings settings = VMSettings.of(5901, 640, 480, 30);
    final VMSettings otherPort = VMSettings.of(5902, 640, 480, 30);
    final VMSettings otherWidth = VMSettings.of(5901, 641, 480, 30);
    final VMSettings otherHeight = VMSettings.of(5901, 640, 481, 30);
    final VMSettings otherFrameRate = VMSettings.of(5901, 640, 480, 31);
    final int hash = settings.hashCode();
    final int portHash = otherPort.hashCode();
    final int widthHash = otherWidth.hashCode();
    final int heightHash = otherHeight.hashCode();
    final int frameRateHash = otherFrameRate.hashCode();
    assertNotEquals(hash, portHash);
    assertNotEquals(hash, widthHash);
    assertNotEquals(hash, heightHash);
    assertNotEquals(hash, frameRateHash);
  }

  @Test
  void describesItself() {
    final VMSettings settings = VMSettings.of(5901, 640, 480, 30);
    final String text = settings.toString();
    assertEquals("VMSettings[port=5901, 640x480@30]", text);
  }
}
