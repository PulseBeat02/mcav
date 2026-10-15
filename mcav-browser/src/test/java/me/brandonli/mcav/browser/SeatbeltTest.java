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
import org.checkerframework.checker.nullness.qual.Nullable;
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
  void shouldNotInstantiateTheProbeOfMacOs() {
    UtilityClassAssertions.assertNotInstantiable(SeatbeltProbeMain.class);
  }

  @Test
  void shouldHideTheFoldersAndLetOnlyTheWritablePathsChangeInAProfile(@TempDir final Path folder) throws IOException {
    final String profile;
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
  void shouldHideNothingAndChangeNothingInAProfileWithoutPaths() {
    final String emptyProfile = Seatbelt.profile(List.of(), List.of(), List.of());
    assertEquals(PROFILE + "(deny file-write*)\n", emptyProfile);
  }

  @Test
  void shouldEscapeQuotesAndBackslashesOfAPath() {
    final String quotedPath = Seatbelt.quote("/a \"quoted\" \\ name");
    assertEquals("\"/a \\\"quoted\\\" \\\\ name\"", quotedPath);
  }

  @Test
  @DisabledOnOs(OS.MAC)
  void shouldOnlyFindSeatbeltOnMacOs() {
    final boolean available = Seatbelt.ofSystem().isAvailable();
    assertFalse(available);
  }

  @Test
  @EnabledOnOs(OS.MAC)
  void shouldFindSeatbeltOnMacOs() {
    final boolean available = Seatbelt.ofSystem().isAvailable();
    assertTrue(available);
  }

  @Test
  void shouldNotTreatALibraryWithoutEitherFunctionAsSeatbelt() throws ReflectiveOperationException {
    for (final String only : List.of(SeatbeltLibrary.INIT, SeatbeltLibrary.FREE_ERROR)) {
      try (final SeatbeltLibrary library = new SeatbeltLibrary(only)) {
        final Seatbelt seatbelt = library.seatbelt();
        final boolean available = seatbelt.isAvailable();
        assertFalse(available);
        final IOException refused = assertThrows(IOException.class, () -> seatbelt.restrictProcess(PROFILE));
        final String message = refused.getMessage();
        assertEquals("The process cannot be restricted, because this system has no Seatbelt (sandbox_init)!", message);
        final List<String> passedProfiles = library.profiles();
        assertEquals(List.of(), passedProfiles);
      }
    }
  }

  @Test
  void shouldPassTheProfileToTheLibraryAsTextOnce() throws Exception {
    try (final SeatbeltLibrary library = SeatbeltLibrary.complete()) {
      final Seatbelt seatbelt = library.seatbelt();
      final boolean available = seatbelt.isAvailable();
      assertTrue(available);
      seatbelt.restrictProcess(PROFILE + "(deny file-write*)\n");
      final List<String> passedProfiles = library.profiles();
      assertEquals(List.of(PROFILE + "(deny file-write*)\n"), passedProfiles);
      final List<Long> passedFlags = library.flags();
      assertEquals(List.of(0L), passedFlags);
      final List<Long> freedAddresses = library.freed();
      assertEquals(List.of(), freedAddresses);
    }
  }

  @Test
  void shouldGiveTheReasonOfTheLibraryAndItsTextBackForARefusedProfile() throws Exception {
    try (final SeatbeltLibrary library = SeatbeltLibrary.complete()) {
      library.refuse(-1, "unbound variable: subpat");
      final Seatbelt seatbelt = library.seatbelt();
      final IOException refused = assertThrows(IOException.class, () -> seatbelt.restrictProcess("(deny (subpat \"/\"))"));
      final String message = refused.getMessage();
      assertEquals("Seatbelt refused the profile (unbound variable: subpat)!", message);
      final List<Long> freedAddresses = library.freed();
      assertEquals(List.of(library.reasonAddress()), freedAddresses);
    }
  }

  @Test
  void shouldSaySoAndFreeNothingForARefusalWithoutAReason() throws Exception {
    try (final SeatbeltLibrary library = SeatbeltLibrary.complete()) {
      library.refuse(1);
      final Seatbelt seatbelt = library.seatbelt();
      final IOException refused = assertThrows(IOException.class, () -> seatbelt.restrictProcess(PROFILE));
      final String message = refused.getMessage();
      assertEquals("Seatbelt refused the profile without a reason!", message);
      final List<Long> freedAddresses = library.freed();
      assertEquals(List.of(), freedAddresses);
    }
  }

  @Test
  void shouldPassTheResultOfACallOn() {
    final @Nullable Object returnedConstant = Seatbelt.call(MethodHandles.constant(int.class, 7));
    assertEquals(7, returnedConstant);
    final @Nullable Object returnedNothing = Seatbelt.call(MethodHandles.empty(MethodType.methodType(void.class)));
    assertNull(returnedNothing);
  }

  @Test
  void shouldTreatACallTheJvmCannotMakeAsAnIllegalState() {
    final IllegalCallerException refused = new IllegalCallerException("native access is not enabled");
    final MethodHandle throwing = MethodHandles.throwException(int.class, IllegalCallerException.class);
    final MethodHandle call = MethodHandles.insertArguments(throwing, 0, refused);
    final IllegalStateException failure = assertThrows(IllegalStateException.class, () -> Seatbelt.call(call));
    final String message = failure.getMessage();
    assertEquals("The C library could not be called!", message);
    final Throwable cause = failure.getCause();
    assertSame(refused, cause);
  }

  @Test
  void shouldPassAVirtualMachineErrorOfACallOnAsItIs() {
    final OutOfMemoryError exhausted = new OutOfMemoryError("no memory for the call");
    final MethodHandle throwing = MethodHandles.throwException(int.class, OutOfMemoryError.class);
    final MethodHandle call = MethodHandles.insertArguments(throwing, 0, exhausted);
    final OutOfMemoryError thrownError = assertThrows(OutOfMemoryError.class, () -> Seatbelt.call(call));
    assertSame(exhausted, thrownError);
  }
}
