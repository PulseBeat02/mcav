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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import me.brandonli.mcav.plugin.testing.EqualityAssertions;
import me.brandonli.mcav.plugin.testing.UtilityClassAssertions;
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

  private static final String FIRST_PROBED_DEVICE_NAME = "device 1";
  private static final String DEVICE_ZERO_NAME = "device 0";

  private static final int CAPTURE_CARD_INDEX = 3;
  private static final String CAPTURE_CARD_NAME = "Capture Card";

  private static final String DEVICE_NAME_FILE_NAME = "name";
  private static final String FIRST_VIDEO_FOLDER_NAME = "video0";
  private static final String CAPTURE_CARD_FOLDER_NAME = "video3";
  private static final String VIRTUAL_CAMERA_FOLDER_NAME = "video2";
  private static final String UNNAMED_VIDEO_FOLDER_NAME = "video10";
  private static final String LONG_NAME_VIDEO_FOLDER_NAME = "video11";
  private static final String VBI_FOLDER_NAME = "vbi0";
  private static final String LONG_NUMBER_FOLDER_NAME = "video1234";
  private static final String MISSING_FOLDER_NAME = "missing";
  private static final String PLAIN_FILE_NAME = "a file";
  private static final String LOCKED_FOLDER_NAME = "locked";

  private static final int DEVICE_INDEX = 4;
  private static final String DEVICE_NAME = "Webcam";

  @TempDir
  Path folder;

  @Test
  void shouldNotBeInstantiable() {
    UtilityClassAssertions.assertNotInstantiable(CaptureDevices.class);
  }

  @Test
  void shouldListWhatLinuxNamesOnLinuxAndTryNumbersElsewhere() throws IOException {
    Files.writeString(
      Files.createDirectory(this.folder.resolve(CAPTURE_CARD_FOLDER_NAME)).resolve(DEVICE_NAME_FILE_NAME),
      CAPTURE_CARD_NAME
    );

    final List<CaptureDevices.Device> expected = List.of(CaptureDevices.Device.of(CAPTURE_CARD_INDEX, CAPTURE_CARD_NAME));
    final List<CaptureDevices.Device> listed = CaptureDevices.list(Os.LINUX, this.folder, _ -> true);
    assertEquals(expected, listed);

    final List<CaptureDevices.Device> tried = CaptureDevices.list(Os.WINDOWS, this.folder, index -> index == 1);

    final List<CaptureDevices.Device> listExpected = List.of(CaptureDevices.Device.of(1, FIRST_PROBED_DEVICE_NAME));
    assertEquals(listExpected, tried);
  }

  @Test
  void shouldListTheVideoDevicesLinuxDescribesByNumber() throws IOException {
    Files.writeString(
      Files.createDirectory(this.folder.resolve(VIRTUAL_CAMERA_FOLDER_NAME)).resolve(DEVICE_NAME_FILE_NAME),
      "OBS Virtual Camera\n"
    );
    Files.writeString(
      Files.createDirectory(this.folder.resolve(FIRST_VIDEO_FOLDER_NAME)).resolve(DEVICE_NAME_FILE_NAME),
      "Integrated Camera"
    );
    Files.createDirectory(this.folder.resolve(UNNAMED_VIDEO_FOLDER_NAME));
    Files.writeString(
      Files.createDirectory(this.folder.resolve(LONG_NAME_VIDEO_FOLDER_NAME)).resolve(DEVICE_NAME_FILE_NAME),
      "x".repeat(100)
    );
    Files.writeString(Files.createDirectory(this.folder.resolve(VBI_FOLDER_NAME)).resolve(DEVICE_NAME_FILE_NAME), "not a camera");
    Files.createDirectory(this.folder.resolve(LONG_NUMBER_FOLDER_NAME));
    final List<CaptureDevices.Device> devices = CaptureDevices.listSysfs(this.folder);

    final List<CaptureDevices.Device> expected = List.of(
      CaptureDevices.Device.of(0, "Integrated Camera"),
      CaptureDevices.Device.of(2, "OBS Virtual Camera"),
      CaptureDevices.Device.of(10, UNNAMED_VIDEO_FOLDER_NAME),
      CaptureDevices.Device.of(11, "x".repeat(CaptureDevices.MAXIMUM_NAME_LENGTH))
    );
    assertEquals(expected, devices);
  }

  @Test
  void shouldListNothingWithoutTheFolderOrWhenItCannotBeRead() throws IOException {
    final List<CaptureDevices.Device> listed = CaptureDevices.listSysfs(this.folder.resolve(MISSING_FOLDER_NAME));
    assertEquals(List.of(), listed);

    final List<CaptureDevices.Device> captureDevicesListed = CaptureDevices.listSysfs(
      Files.writeString(this.folder.resolve(PLAIN_FILE_NAME), "")
    );
    assertEquals(List.of(), captureDevicesListed);

    final Path locked = Files.createDirectory(this.folder.resolve(LOCKED_FOLDER_NAME));
    Files.createDirectory(locked.resolve(FIRST_VIDEO_FOLDER_NAME));
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
      CaptureDevices.Device.of(1, FIRST_PROBED_DEVICE_NAME),
      CaptureDevices.Device.of(3, "device 3")
    );
    assertEquals(expected, devices);

    final List<CaptureDevices.Device> captureDevicesDevices = CaptureDevices.probe(0, _ -> true);
    assertEquals(List.of(), captureDevicesDevices);
    assertThrows(IllegalArgumentException.class, () -> CaptureDevices.probe(-1, _ -> true));
  }

  @Test
  void shouldOpenADeviceWhenItsGrabberStartsAndCloseItAgain() throws IOException {
    final FrameGrabber grabber = mock(FrameGrabber.class);

    final boolean opened = CaptureDevices.canOpen(_ -> grabber, 0);
    assertTrue(opened);

    verify(grabber).start();
    verify(grabber).close();
  }

  @Test
  void shouldNotOpenANumberWithoutADevice() {
    final boolean opened = CaptureDevices.canOpen(63);
    assertFalse(opened);
  }

  @Test
  void shouldTryDevicesOnlyWhereTheSystemDoesNotListThem() {
    final FrameGrabber.Exception noCamera = new FrameGrabber.Exception("no camera!");
    try (
      final MockedStatic<OsUtils> systems = Mockito.mockStatic(OsUtils.class);
      final MockedConstruction<OpenCVFrameGrabber> grabbers = Mockito.mockConstruction(OpenCVFrameGrabber.class, (grabber, _) ->
        this.failStart(grabber, noCamera)
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

  private void failStart(final OpenCVFrameGrabber grabber, final FrameGrabber.Exception noCamera) throws FrameGrabber.Exception {
    doThrow(noCamera).when(grabber).start();
  }

  @Test
  void shouldListTheNumbersWhoseOpenCvGrabberStartsWhereTheSystemDoesNotListDevices() throws IOException {
    try (
      final MockedStatic<OsUtils> systems = Mockito.mockStatic(OsUtils.class);
      final MockedConstruction<OpenCVFrameGrabber> grabbers = Mockito.mockConstruction(OpenCVFrameGrabber.class)
    ) {
      systems.when(OsUtils::getOs).thenReturn(Os.WINDOWS);
      final List<CaptureDevices.Device> devices = CaptureDevices.list();

      final CaptureDevices.Device expected = CaptureDevices.Device.of(0, DEVICE_ZERO_NAME);
      final CaptureDevices.Device device = devices.getFirst();
      assertEquals(expected, device);

      final boolean captureDevicesOpened = CaptureDevices.canOpen(5);
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
    final List<CaptureDevices.Device> devices = List.of(CaptureDevices.Device.of(0, "a"), CaptureDevices.Device.of(12, "b"));

    final CaptureDevices.Device expected = devices.get(1);
    final Optional<CaptureDevices.Device> found = CaptureDevices.find(devices, "12");
    final Optional<CaptureDevices.Device> expectedDevice = Optional.of(expected);
    assertEquals(expectedDevice, found);

    final Optional<CaptureDevices.Device> captureDevicesFound = CaptureDevices.find(devices, "012");
    assertEquals(Optional.empty(), captureDevicesFound);

    final Optional<CaptureDevices.Device> secondFound = CaptureDevices.find(devices, "1");
    assertEquals(Optional.empty(), secondFound);

    final Optional<CaptureDevices.Device> thirdFound = CaptureDevices.find(devices, " 0");
    assertEquals(Optional.empty(), thirdFound);

    final Optional<CaptureDevices.Device> fourthFound = CaptureDevices.find(List.of(), "0");
    assertEquals(Optional.empty(), fourthFound);
    assertThrows(NullPointerException.class, () -> CaptureDevices.find(null, "0"));
    assertThrows(NullPointerException.class, () -> CaptureDevices.find(devices, null));
  }

  @Test
  void shouldFollowTheEqualityContract() {
    final CaptureDevices.Device device = CaptureDevices.Device.of(DEVICE_INDEX, DEVICE_NAME);
    final CaptureDevices.Device equalDevice = CaptureDevices.Device.of(DEVICE_INDEX, DEVICE_NAME);
    final CaptureDevices.Device otherIndex = CaptureDevices.Device.of(DEVICE_INDEX + 1, DEVICE_NAME);
    final CaptureDevices.Device otherName = CaptureDevices.Device.of(DEVICE_INDEX, "Webcam 2");
    EqualityAssertions.assertEqualityContract(device, equalDevice, otherIndex, otherName);
  }

  @Test
  void shouldHashTheIndexThenTheName() {
    final CaptureDevices.Device device = CaptureDevices.Device.of(DEVICE_INDEX, DEVICE_NAME);

    final int hash = device.hashCode();
    final int expected = Objects.hash(DEVICE_INDEX, DEVICE_NAME);
    assertEquals(expected, hash);
  }

  @Test
  void shouldRenderTheIndexAndTheName() {
    final CaptureDevices.Device device = CaptureDevices.Device.of(0, DEVICE_ZERO_NAME);
    final String text = device.toString();
    assertEquals("Device{index=0, name=device 0}", text);
  }

  @Test
  void shouldKeepTheIndexAndTheNameOfADevice() {
    final CaptureDevices.Device device = CaptureDevices.Device.of(CAPTURE_CARD_INDEX, CAPTURE_CARD_NAME);
    final int index = device.getIndex();
    final String name = device.getName();
    assertEquals(CAPTURE_CARD_INDEX, index);
    assertEquals(CAPTURE_CARD_NAME, name);
  }

  @Test
  void shouldRefuseADeviceWithoutAName() {
    final NullPointerException thrown = assertThrows(NullPointerException.class, () -> CaptureDevices.Device.of(0, null));

    final String message = thrown.getMessage();
    assertEquals("Name must not be null!", message);
  }
}
