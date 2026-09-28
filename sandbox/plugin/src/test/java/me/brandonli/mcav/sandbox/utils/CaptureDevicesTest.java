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
package me.brandonli.mcav.sandbox.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;
import me.brandonli.mcav.utils.os.OS;
import me.brandonli.mcav.utils.os.OSUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

final class CaptureDevicesTest {

  @TempDir
  Path folder;

  @Test
  void isAUtilityClass() throws ReflectiveOperationException {
    final Constructor<CaptureDevices> constructor = CaptureDevices.class.getDeclaredConstructor();
    constructor.setAccessible(true);
    final InvocationTargetException thrown = assertThrows(InvocationTargetException.class, constructor::newInstance);
    assertEquals(UnsupportedOperationException.class, thrown.getCause().getClass());
  }

  @Test
  void listsTheVideoDevicesLinuxDescribesByNumber() throws IOException {
    Files.writeString(Files.createDirectory(this.folder.resolve("video2")).resolve("name"), "OBS Virtual Camera\n");
    Files.writeString(Files.createDirectory(this.folder.resolve("video0")).resolve("name"), "Integrated Camera");
    Files.createDirectory(this.folder.resolve("video10"));
    Files.writeString(Files.createDirectory(this.folder.resolve("video11")).resolve("name"), "x".repeat(100));
    Files.writeString(Files.createDirectory(this.folder.resolve("vbi0")).resolve("name"), "not a camera");
    Files.createDirectory(this.folder.resolve("video1234"));
    final List<CaptureDevices.Device> devices = CaptureDevices.listSysfs(this.folder);
    assertEquals(
      List.of(
        new CaptureDevices.Device(0, "Integrated Camera"),
        new CaptureDevices.Device(2, "OBS Virtual Camera"),
        new CaptureDevices.Device(10, "video10"),
        new CaptureDevices.Device(11, "x".repeat(CaptureDevices.MAX_NAME))
      ),
      devices
    );
  }

  @Test
  void listsNothingWithoutTheFolderOrWhenItCannotBeRead() throws IOException {
    assertEquals(List.of(), CaptureDevices.listSysfs(this.folder.resolve("missing")));
    assertEquals(List.of(), CaptureDevices.listSysfs(Files.writeString(this.folder.resolve("a file"), "")));
    final Path locked = Files.createDirectory(this.folder.resolve("locked"));
    Files.createDirectory(locked.resolve("video0"));
    assumeTrue(Files.getFileStore(locked).supportsFileAttributeView(PosixFileAttributeView.class), "POSIX permissions");
    final Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(locked);
    Files.setPosixFilePermissions(locked, Set.of());
    try {
      assertEquals(List.of(), CaptureDevices.listSysfs(locked));
    } finally {
      Files.setPosixFilePermissions(locked, permissions);
    }
  }

  @Test
  void triesTheFirstNumbersWhereTheSystemDoesNotListDevices() {
    final List<CaptureDevices.Device> devices = CaptureDevices.probe(4, index -> index % 2 == 1);
    assertEquals(List.of(new CaptureDevices.Device(1, "device 1"), new CaptureDevices.Device(3, "device 3")), devices);
    assertEquals(List.of(), CaptureDevices.probe(0, _ -> true));
    assertThrows(IllegalArgumentException.class, () -> CaptureDevices.probe(-1, _ -> true));
  }

  @Test
  void aNumberWithoutADeviceDoesNotOpen() {
    assertFalse(CaptureDevices.opens(63));
  }

  @Test
  void triesDevicesOnlyWhereTheSystemDoesNotListThem() {
    try (MockedStatic<OSUtils> systems = Mockito.mockStatic(OSUtils.class)) {
      systems.when(OSUtils::getOS).thenReturn(OS.WINDOWS);
      assertEquals(List.of(), CaptureDevices.list());
      systems.when(OSUtils::getOS).thenReturn(OS.LINUX);
      assertEquals(CaptureDevices.listSysfs(CaptureDevices.SYSFS), CaptureDevices.list());
    }
  }

  @Test
  void findsAListedDeviceOnlyByItsExactNumber() {
    final List<CaptureDevices.Device> devices = List.of(new CaptureDevices.Device(0, "a"), new CaptureDevices.Device(12, "b"));
    assertEquals(devices.get(1), CaptureDevices.find(devices, "12"));
    assertNull(CaptureDevices.find(devices, "012"));
    assertNull(CaptureDevices.find(devices, "1"));
    assertNull(CaptureDevices.find(devices, " 0"));
    assertNull(CaptureDevices.find(List.of(), "0"));
    assertThrows(NullPointerException.class, () -> CaptureDevices.find(null, "0"));
    assertThrows(NullPointerException.class, () -> CaptureDevices.find(devices, null));
  }
}
