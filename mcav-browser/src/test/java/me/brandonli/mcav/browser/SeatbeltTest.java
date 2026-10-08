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
package me.brandonli.mcav.browser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import me.brandonli.mcav.browser.testing.UtilityClassAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests {@link Seatbelt}: the profile it writes, and its calls of the library, which here reach stand-ins written in
 * Java ({@link SeatbeltLibrary}). A real sandbox cannot be lifted, so the tests of macOS put a process of their own in
 * it ({@link ChromiumConfinementTest}).
 */
class SeatbeltTest {

  private static final String PROFILE = "(version 1)\n(allow default)\n";

  @Test
  void theProbeOfMacOsCannotBeInstantiated() {
    UtilityClassAssertions.assertNotInstantiable(SeatbeltProbeMain.class);
  }

  @Test
  void aProfileHidesTheFoldersAndLetsOnlyTheWritablePathsChange(@TempDir final Path folder) throws IOException {
    final String profile;
    // the paths as macOS writes them, also where the tests run on Windows, whose paths have backslashes
    try (final FileSystem mac = FileSystems.newFileSystem(folder.resolve("paths.zip"), Map.of("create", "true"))) {
      profile = Seatbelt.profile(
        List.of(mac.getPath("/server"), mac.getPath("/Users/owner")),
        List.of(mac.getPath("/Users/owner/.mcav/natives"), mac.getPath("/Users/owner/helper.jar")),
        List.of(mac.getPath("/private/var/folders/session"), mac.getPath("/dev"))
      );
    }
    assertEquals(
      PROFILE +
        "(deny file-read-data (subpath \"/server\") (subpath \"/Users/owner\"))\n" +
        "(allow file-read-data (subpath \"/Users/owner/.mcav/natives\") (subpath \"/Users/owner/helper.jar\"))\n" +
        "(allow file-read-data (subpath \"/private/var/folders/session\") (subpath \"/dev\"))\n" +
        "(deny file-write*)\n" +
        "(allow file-write* (subpath \"/private/var/folders/session\") (subpath \"/dev\"))\n",
      profile
    );
  }

  @Test
  void aProfileWithoutPathsHidesNothingAndChangesNothing() {
    assertEquals(PROFILE + "(deny file-write*)\n", Seatbelt.profile(List.of(), List.of(), List.of()));
  }

  @Test
  void quotesAndBackslashesOfAPathAreEscaped() {
    assertEquals("\"/a \\\"quoted\\\" \\\\ name\"", Seatbelt.quote("/a \"quoted\" \\ name"));
  }

  @Test
  @DisabledOnOs(OS.MAC)
  void onlyMacOsHasSeatbelt() {
    assertFalse(Seatbelt.ofSystem().isAvailable());
  }

  @Test
  @EnabledOnOs(OS.MAC)
  void macOsHasSeatbelt() {
    assertTrue(Seatbelt.ofSystem().isAvailable());
  }

  @Test
  void aLibraryWithoutEitherFunctionIsNoSeatbelt() throws ReflectiveOperationException {
    for (final String only : List.of(SeatbeltLibrary.INIT, SeatbeltLibrary.FREE_ERROR)) {
      try (final SeatbeltLibrary library = new SeatbeltLibrary(only)) {
        final Seatbelt seatbelt = library.seatbelt();
        assertFalse(seatbelt.isAvailable());
        final IOException refused = assertThrows(IOException.class, () -> seatbelt.restrictProcess(PROFILE));
        assertEquals("this system has no Seatbelt (sandbox_init)", refused.getMessage());
        assertEquals(List.of(), library.profiles());
      }
    }
  }

  @Test
  void theProfileReachesTheLibraryAsTextOnce() throws Exception {
    try (final SeatbeltLibrary library = SeatbeltLibrary.complete()) {
      final Seatbelt seatbelt = library.seatbelt();
      assertTrue(seatbelt.isAvailable());
      seatbelt.restrictProcess(PROFILE + "(deny file-write*)\n");
      assertEquals(List.of(PROFILE + "(deny file-write*)\n"), library.profiles());
      assertEquals(List.of(0L), library.flags());
      assertEquals(List.of(), library.freed());
    }
  }

  @Test
  void aRefusedProfileGivesTheLibrarysReasonAndItsTextBack() throws Exception {
    try (final SeatbeltLibrary library = SeatbeltLibrary.complete()) {
      library.refuse(-1, "unbound variable: subpat");
      final Seatbelt seatbelt = library.seatbelt();
      final IOException refused = assertThrows(IOException.class, () -> seatbelt.restrictProcess("(deny (subpat \"/\"))"));
      assertEquals("Seatbelt refused the profile: unbound variable: subpat", refused.getMessage());
      assertEquals(List.of(library.reasonAddress()), library.freed());
    }
  }

  @Test
  void aRefusalWithoutAReasonSaysSoAndFreesNothing() throws Exception {
    try (final SeatbeltLibrary library = SeatbeltLibrary.complete()) {
      library.refuse(1);
      final Seatbelt seatbelt = library.seatbelt();
      final IOException refused = assertThrows(IOException.class, () -> seatbelt.restrictProcess(PROFILE));
      assertEquals("Seatbelt refused the profile: no reason given", refused.getMessage());
      assertEquals(List.of(), library.freed());
    }
  }

  @Test
  void aCallPassesItsResultOn() {
    assertEquals(7, Seatbelt.call(MethodHandles.constant(int.class, 7)));
    assertNull(Seatbelt.call(MethodHandles.empty(MethodType.methodType(void.class))));
  }

  @Test
  void aCallTheJvmCannotMakeIsAnIllegalState() {
    final IllegalCallerException refused = new IllegalCallerException("native access is not enabled");
    final MethodHandle throwing = MethodHandles.throwException(int.class, IllegalCallerException.class);
    final MethodHandle call = MethodHandles.insertArguments(throwing, 0, refused);
    final IllegalStateException failure = assertThrows(IllegalStateException.class, () -> Seatbelt.call(call));
    assertEquals("The C library could not be called", failure.getMessage());
    assertSame(refused, failure.getCause());
  }

  @Test
  void aVirtualMachineErrorOfACallGoesOnAsItIs() {
    final OutOfMemoryError exhausted = new OutOfMemoryError("no memory for the call");
    final MethodHandle throwing = MethodHandles.throwException(int.class, OutOfMemoryError.class);
    final MethodHandle call = MethodHandles.insertArguments(throwing, 0, exhausted);
    assertSame(exhausted, assertThrows(OutOfMemoryError.class, () -> Seatbelt.call(call)));
  }
}
