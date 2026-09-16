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
package me.brandonli.mcav.media.source.device;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.brandonli.mcav.media.source.SourceDetector;
import me.brandonli.mcav.testing.EqualityAssertions;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link DeviceSource}, {@link DeviceSourceImpl} and {@link DeviceSourceDetector}.
 */
final class DeviceSourceTest {

  @Test
  void describesTheDevice() {
    final DeviceSource source = DeviceSource.device(3);
    final int deviceId = source.getDeviceId();
    final String resource = source.getResource();
    final String name = source.getName();
    final boolean isStatic = source.isStatic();
    final boolean isDynamic = source.isDynamic();
    final String text = source.toString();
    assertEquals(3, deviceId);
    assertEquals("3", resource);
    assertEquals("device", name);
    assertFalse(isStatic);
    assertTrue(isDynamic);
    assertEquals("DeviceSource[3]", text);
  }

  @Test
  void followsTheEqualityContract() {
    final DeviceSource source = DeviceSource.device(1);
    final DeviceSource equalSource = DeviceSource.device(1);
    final DeviceSource otherSource = DeviceSource.device(2);
    EqualityAssertions.assertEqualityContract(source, equalSource, otherSource);
  }

  @Test
  void rejectsNegativeIndices() {
    assertThrows(IllegalArgumentException.class, () -> DeviceSource.device(-1));
  }

  @Test
  void detectsNonNegativeIntegers() {
    final DeviceSourceDetector detector = new DeviceSourceDetector();
    final boolean zero = detector.isDetectedSource("0");
    final boolean negative = detector.isDetectedSource("-1");
    final boolean text = detector.isDetectedSource("camera");
    final DeviceSource created = detector.createSource("5");
    final DeviceSource expected = DeviceSource.device(5);
    final int priority = detector.getPriority();
    assertTrue(zero);
    assertFalse(negative);
    assertFalse(text);
    assertEquals(expected, created);
    assertEquals(SourceDetector.HIGH_PRIORITY, priority);
  }

  @Test
  void detectorRejectsNullInput() {
    final DeviceSourceDetector detector = new DeviceSourceDetector();
    assertThrows(NullPointerException.class, () -> detector.isDetectedSource(null));
    assertThrows(NullPointerException.class, () -> detector.createSource(null));
  }
}
