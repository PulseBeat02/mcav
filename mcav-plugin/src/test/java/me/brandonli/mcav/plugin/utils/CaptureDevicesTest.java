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
package me.brandonli.mcav.plugin.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;
import me.brandonli.mcav.utils.os.Os;
import me.brandonli.mcav.utils.os.OsUtils;
import org.bytedeco.javacv.FrameGrabber;
import org.bytedeco.javacv.OpenCVFrameGrabber;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

final class CaptureDevicesTest {

  @TempDir
  Path folder;

  @Test
  void shouldBeAUtilityClass() throws ReflectiveOperationException {
    final Constructor<CaptureDevices> constructor = CaptureDevices.class.getDeclaredConstructor();
    constructor.setAccessible(true);
    final InvocationTargetException thrown = assertThrows(InvocationTargetException.class, constructor::newInstance);

    final Class<? extends Throwable> thrownClass = thrown.getCause().getClass();
    assertEquals(UnsupportedOperationException.class, thrownClass);
  }

  @Test
  void shouldListWhatLinuxNamesOnLinuxAndTryNumbersElsewhere() throws IOException {
    Files.writeString(Files.createDirectory(this.folder.resolve("video3")).resolve("name"), "Capture Card");

    final List<CaptureDevices.Device> expected = List.of(new CaptureDevices.Device(3, "Capture Card"));
    final List<CaptureDevices.Device> listed = CaptureDevices.list(Os.LINUX, this.folder, _ -> true);
    assertEquals(expected, listed);

    final List<CaptureDevices.Device> tried = CaptureDevices.list(Os.WINDOWS, this.folder, index -> index == 1);

    final List<CaptureDevices.Device> listExpected = List.of(new CaptureDevices.Device(1, "device 1"));
    assertEquals(listExpected, tried);
  }

  @Test
  void shouldListTheVideoDevicesLinuxDescribesByNumber() throws IOException {
    Files.writeString(Files.createDirectory(this.folder.resolve("video2")).resolve("name"), "OBS Virtual Camera\n");
    Files.writeString(Files.createDirectory(this.folder.resolve("video0")).resolve("name"), "Integrated Camera");
    Files.createDirectory(this.folder.resolve("video10"));
    Files.writeString(Files.createDirectory(this.folder.resolve("video11")).resolve("name"), "x".repeat(100));
    Files.writeString(Files.createDirectory(this.folder.resolve("vbi0")).resolve("name"), "not a camera");
    Files.createDirectory(this.folder.resolve("video1234"));
    final List<CaptureDevices.Device> devices = CaptureDevices.listSysfs(this.folder);

    final List<CaptureDevices.Device> expected = List.of(
      new CaptureDevices.Device(0, "Integrated Camera"),
      new CaptureDevices.Device(2, "OBS Virtual Camera"),
      new CaptureDevices.Device(10, "video10"),
      new CaptureDevices.Device(11, "x".repeat(CaptureDevices.MAXIMUM_NAME))
    );
    assertEquals(expected, devices);
  }

  @Test
  void shouldListNothingWithoutTheFolderOrWhenItCannotBeRead() throws IOException {
    final List<CaptureDevices.Device> listed = CaptureDevices.listSysfs(this.folder.resolve("missing"));
    assertEquals(List.of(), listed);

    final List<CaptureDevices.Device> captureDevicesListed = CaptureDevices.listSysfs(Files.writeString(this.folder.resolve("a file"), ""));
    assertEquals(List.of(), captureDevicesListed);

    final Path locked = Files.createDirectory(this.folder.resolve("locked"));
    Files.createDirectory(locked.resolve("video0"));
    assumeTrue(
      Files.getFileStore(locked).supportsFileAttributeView(PosixFileAttributeView.class),
      "The file system must support POSIX permissions!"
    );
    final Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(locked);
    Files.setPosixFilePermissions(locked, Set.of());
    try {
      final List<CaptureDevices.Device> secondListed = CaptureDevices.listSysfs(locked);
      assertEquals(List.of(), secondListed);
    } finally {
      Files.setPosixFilePermissions(locked, permissions);
    }
  }

  @Test
  void shouldTryTheFirstNumbersWhereTheSystemDoesNotListDevices() {
    final List<CaptureDevices.Device> devices = CaptureDevices.probe(4, index -> index % 2 == 1);

    final List<CaptureDevices.Device> expected = List.of(
      new CaptureDevices.Device(1, "device 1"),
      new CaptureDevices.Device(3, "device 3")
    );
    assertEquals(expected, devices);

    final List<CaptureDevices.Device> captureDevicesDevices = CaptureDevices.probe(0, _ -> true);
    assertEquals(List.of(), captureDevicesDevices);
    assertThrows(IllegalArgumentException.class, () -> CaptureDevices.probe(-1, _ -> true));
  }

  @Test
  void shouldOpenADeviceWhenItsGrabberStartsAndCloseItAgain() throws IOException {
    final FrameGrabber grabber = mock(FrameGrabber.class);

    final boolean opened = CaptureDevices.opens(_ -> grabber, 0);
    assertTrue(opened);

    verify(grabber).start();
    verify(grabber).close();
  }

  @Test
  void shouldNotOpenANumberWithoutADevice() {
    final boolean opened = CaptureDevices.opens(63);
    assertFalse(opened);
  }

  @Test
  void shouldTryDevicesOnlyWhereTheSystemDoesNotListThem() {
    final FrameGrabber.Exception noCamera = new FrameGrabber.Exception("no camera!");
    try (
      final MockedStatic<OsUtils> systems = Mockito.mockStatic(OsUtils.class);
      final MockedConstruction<OpenCVFrameGrabber> grabbers = Mockito.mockConstruction(OpenCVFrameGrabber.class, (grabber, _) ->
        doThrow(noCamera).when(grabber).start()
      )
    ) {
      systems.when(OsUtils::getOs).thenReturn(Os.WINDOWS);

      final List<CaptureDevices.Device> listed = CaptureDevices.list();
      assertEquals(List.of(), listed);

      final int tried = grabbers.constructed().size();
      systems.when(OsUtils::getOs).thenReturn(Os.LINUX);

      final List<CaptureDevices.Device> expected = CaptureDevices.listSysfs(CaptureDevices.SYSFS);
      final List<CaptureDevices.Device> captureDevicesListed = CaptureDevices.list();
      assertEquals(expected, captureDevicesListed);

      assertTrue(tried > 0, "the numbers are tried");

      final int size = grabbers.constructed().size();
      assertEquals(tried, size, "Linux names its devices, and listing them opens none");
    }
  }

  @Test
  void shouldListTheNumbersWhoseOpenCvGrabberStartsWhereTheSystemDoesNotListDevices() throws IOException {
    try (
      final MockedStatic<OsUtils> systems = Mockito.mockStatic(OsUtils.class);
      final MockedConstruction<OpenCVFrameGrabber> grabbers = Mockito.mockConstruction(OpenCVFrameGrabber.class)
    ) {
      systems.when(OsUtils::getOs).thenReturn(Os.WINDOWS);
      final List<CaptureDevices.Device> devices = CaptureDevices.list();

      final CaptureDevices.Device expected = new CaptureDevices.Device(0, "device 0");
      final CaptureDevices.Device device = devices.getFirst();
      assertEquals(expected, device);

      final boolean captureDevicesOpened = CaptureDevices.opens(5);
      assertTrue(captureDevicesOpened);

      final List<OpenCVFrameGrabber> opened = grabbers.constructed();

      final int secondExpected = devices.size() + 1;
      final int size = opened.size();
      assertEquals(secondExpected, size);

      for (final OpenCVFrameGrabber grabber : opened) {
        verify(grabber).close();
      }
    }
  }

  @Test
  void shouldFindAListedDeviceOnlyByItsExactNumber() {
    final List<CaptureDevices.Device> devices = List.of(new CaptureDevices.Device(0, "a"), new CaptureDevices.Device(12, "b"));

    final CaptureDevices.Device expected = devices.get(1);
    final CaptureDevices.Device found = CaptureDevices.find(devices, "12");
    assertEquals(expected, found);

    final CaptureDevices.Device captureDevicesFound = CaptureDevices.find(devices, "012");
    assertNull(captureDevicesFound);

    final CaptureDevices.Device secondFound = CaptureDevices.find(devices, "1");
    assertNull(secondFound);

    final CaptureDevices.Device thirdFound = CaptureDevices.find(devices, " 0");
    assertNull(thirdFound);

    final CaptureDevices.Device fourthFound = CaptureDevices.find(List.of(), "0");
    assertNull(fourthFound);
    assertThrows(NullPointerException.class, () -> CaptureDevices.find(null, "0"));
    assertThrows(NullPointerException.class, () -> CaptureDevices.find(devices, null));
  }
}
