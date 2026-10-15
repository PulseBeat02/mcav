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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import me.brandonli.mcav.browser.testing.UtilityClassAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests {@link Landlock}: the rights of each version and rule, and, where the kernel has Landlock, a restricted thread
 * and the threads and processes it starts. A restriction cannot be lifted, so each one runs on a thread of its own.
 */
class LandlockTest {

  private static final long READ_FOLDER = 0b1101;
  private static final long READ_FILE = 0b101;

  private static final Path ROOT = Path.of("/");

  @TempDir
  private Path folder;

  @Test
  void shouldNotBeInstantiated() {
    UtilityClassAssertions.assertNotInstantiable(Landlock.class);
    UtilityClassAssertions.assertNotInstantiable(ChromiumConfinement.class);
  }

  @Test
  void shouldReadTheAnswerOfTheKernel() {
    final int answeredVersion = Landlock.versionOf(4, 0);
    assertEquals(4, answeredVersion);
    final int negatedErrorNumber = Landlock.versionOf(-1, 38);
    assertEquals(-38, negatedErrorNumber);
    final int zeroAnswer = Landlock.versionOf(0, 38);
    assertEquals(0, zeroAnswer);
    final int versionDespiteErrorNumber = Landlock.versionOf(3, 38);
    assertEquals(3, versionDespiteErrorNumber);
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void shouldAnswerAVersionOrWhyThereIsNoneButNeverNothing() {
    final int version = Landlock.version();
    assertNotEquals(0, version, "1 or more, or the negated error number of a kernel without Landlock");
  }

  @Test
  void shouldSayWhyThereIsNoLandlock() {
    final boolean namesTheKernelRelease = Landlock.describeMissing(-38).contains("5.13");
    assertTrue(namesTheKernelRelease);
    final boolean saysTurnedOff = Landlock.describeMissing(-95).contains("turned off");
    assertTrue(saysTurnedOff);
    final String otherErrorDescription = Landlock.describeMissing(-1);
    assertEquals("Landlock is not available here (error 1)", otherErrorDescription);
  }

  @Test
  void shouldHandleTheRightsEachVersionKnows() {
    final long versionOneRights = Landlock.handledRights(1);
    assertEquals(0x1FFF, versionOneRights);
    final long versionTwoRights = Landlock.handledRights(2);
    assertEquals(0x3FFF, versionTwoRights);
    final long versionThreeRights = Landlock.handledRights(3);
    assertEquals(0x7FFF, versionThreeRights);
    final long versionFourRights = Landlock.handledRights(4);
    assertEquals(0x7FFF, versionFourRights);
    final long versionFiveRights = Landlock.handledRights(5);
    assertEquals(0xFFFF, versionFiveRights);
  }

  @Test
  void shouldGrantReadingAndRunningInARuleAndChangesInAWritableOne() {
    final Path path = this.folder;
    final long all = Landlock.handledRights(5);
    final long readOnlyFolderRights = Landlock.rightsOf(new Landlock.Rule(path, false), true, all);
    assertEquals(READ_FOLDER, readOnlyFolderRights);
    final long readOnlyFileRights = Landlock.rightsOf(new Landlock.Rule(path, false), false, all);
    assertEquals(READ_FILE, readOnlyFileRights);
    final long writableFolderRights = Landlock.rightsOf(new Landlock.Rule(path, true), true, all);
    assertEquals(0xFFFF & ~(1L << 6) & ~(1L << 11), writableFolderRights);
    final long writableFileRights = Landlock.rightsOf(new Landlock.Rule(path, true), false, all);
    assertEquals(0b1100_0000_0000_0111, writableFileRights);
    final long versionOneWritableFolderRights = Landlock.rightsOf(new Landlock.Rule(path, true), true, Landlock.handledRights(1));
    assertEquals(0x1FFF & ~(1L << 6) & ~(1L << 11), versionOneWritableFolderRights);
  }

  @Test
  void shouldTreatAFailedCallOfTheKernelAsAnIOException() throws IOException {
    final long checkedAnswer = Landlock.check(7, 0, "count");
    assertEquals(7, checkedAnswer);
    final IOException failure = assertThrows(IOException.class, () -> Landlock.check(-1, 22, "create a ruleset"));
    final String message = failure.getMessage();
    assertEquals("Landlock could not create a ruleset (error 22)!", message);
  }

  @Test
  void shouldTreatACallTheJvmCannotMakeAsAnIllegalState() throws ReflectiveOperationException {
    final IllegalCallerException refused = new IllegalCallerException("native access refused");
    final MethodHandle throwing = MethodHandles.throwException(long.class, IllegalCallerException.class);
    final MethodHandle call = MethodHandles.insertArguments(throwing, 0, refused);
    final IllegalStateException failure = assertThrows(IllegalStateException.class, () -> Landlock.invoke(call));
    final Throwable cause = failure.getCause();
    assertSame(refused, cause);
    final MethodHandle answer = MethodHandles.constant(int.class, 3);
    final long constantAnswer = Landlock.invoke(answer);
    assertEquals(3, constantAnswer);
    final MethodHandle length = MethodHandles.lookup().findVirtual(String.class, "length", MethodType.methodType(int.class));
    final long lengthAnswer = Landlock.invoke(length, "four");
    assertEquals(4, lengthAnswer);
  }

  @Test
  void shouldNotTurnAVirtualMachineErrorIntoAnAnswerOfTheCall() {
    final OutOfMemoryError exhausted = new OutOfMemoryError("no memory for the call");
    final MethodHandle throwing = MethodHandles.throwException(long.class, OutOfMemoryError.class);
    final MethodHandle call = MethodHandles.insertArguments(throwing, 0, exhausted);
    final OutOfMemoryError thrown = assertThrows(OutOfMemoryError.class, () -> Landlock.invoke(call));
    assertSame(exhausted, thrown);
  }

  /**
   * Runs a task on a thread of its own and waits for it.
   *
   * @return the failure of the task, or null
   */
  private static Throwable onThread(final ThrowingTask task) throws InterruptedException {
    final AtomicReference<Throwable> failure = new AtomicReference<>();
    final Thread thread = new Thread(() -> {
      try {
        task.run();
      } catch (final Throwable exception) {
        failure.set(exception);
      }
    }, "landlock-test");
    thread.start();
    thread.join(TimeUnit.SECONDS.toMillis(60));
    final boolean ended = !thread.isAlive();
    assertTrue(ended, "the restricted thread ended");
    return failure.get();
  }

  private static int availableVersion() {
    final int version = Landlock.version();
    assumeTrue(version >= 1, () -> "this kernel cannot restrict: " + Landlock.describeMissing(version));
    return version;
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void shouldRefuseRightsTheKernelDoesNotKnowWithItsErrorNumber() throws InterruptedException {
    final int version = availableVersion();
    assumeTrue(version < 5, () -> "this kernel knows every right of the fifth version: " + version);
    final Throwable failure = onThread(() -> Landlock.restrictThread(5, List.of()));
    assertInstanceOf(IOException.class, failure);
    final String message = failure.getMessage();
    assertEquals("Landlock could not create a ruleset (error 22)!", message);
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void shouldLetARestrictedThreadAndItsChildThreadReachOnlyWhatItsRulesGrant() throws Exception {
    final int version = availableVersion();
    final Path hidden = Files.createDirectories(this.folder.resolve("hidden"));
    final Path secret = Files.writeString(hidden.resolve("secret.txt"), "secret");
    final Path granted = Files.writeString(hidden.resolve("granted.txt"), "granted");
    final Path open = Files.createDirectories(this.folder.resolve("open"));
    final Path readable = Files.writeString(open.resolve("readable.txt"), "readable");
    final Path writable = Files.createDirectories(this.folder.resolve("writable"));
    final List<Landlock.Rule> rules = new ArrayList<>(
      ChromiumConfinement.rules(ROOT, List.of(hidden), List.of(granted), List.of(writable))
    );
    rules.add(new Landlock.Rule(this.folder.resolve("deleted"), false));
    final List<String> seen = new ArrayList<>();
    final Throwable failure = onThread(() -> {
      Landlock.restrictThread(version, rules);
      seen.add(Files.readString(readable, StandardCharsets.UTF_8));
      seen.add(Files.readString(granted, StandardCharsets.UTF_8));
      assertThrows(AccessDeniedException.class, () -> Files.readString(secret, StandardCharsets.UTF_8));
      assertThrows(AccessDeniedException.class, () -> Files.writeString(open.resolve("new.txt"), "new"));
      seen.add(Files.writeString(writable.resolve("new.txt"), "written").getFileName().toString());
      final AtomicReference<Throwable> childFailure = new AtomicReference<>();
      final Thread child = new Thread(() ->
        childFailure.set(assertThrows(AccessDeniedException.class, () -> Files.readString(secret, StandardCharsets.UTF_8)))
      );
      child.start();
      child.join();
      final Throwable childThrown = childFailure.get();
      assertInstanceOf(AccessDeniedException.class, childThrown, "a thread started afterwards is restricted too");
    });
    assertNull(failure, () -> "the restricted thread failed: " + failure);
    assertEquals(List.of("readable", "granted", "new.txt"), seen);
    final String secretText = Files.readString(secret, StandardCharsets.UTF_8);
    assertEquals("secret", secretText, "the other threads are free");
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void shouldRestrictAProcessStartedByARestrictedThreadToo() throws Exception {
    availableVersion();
    final Path hidden = Files.createDirectories(this.folder.resolve("hidden"));
    final Path secret = Files.writeString(hidden.resolve("secret.txt"), "secret");
    final String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    final Process probe = new ProcessBuilder(
      java,
      "--enable-native-access=ALL-UNNAMED",
      "-cp",
      System.getProperty("java.class.path"),
      ConfinedProbeMain.class.getName(),
      hidden.toString(),
      secret.toString()
    )
      .redirectErrorStream(true)
      .start();
    final String output = new String(probe.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    final boolean exited = probe.waitFor(60, TimeUnit.SECONDS);
    assertTrue(exited);
    final int exitCode = probe.exitValue();
    assertEquals(0, exitCode, output);
    final boolean catFailed = output.contains("cat exited 1");
    assertTrue(catFailed, output);
    final boolean permissionDenied = output.contains("Permission denied");
    assertTrue(permissionDenied, output);
    final boolean noNewPrivileges = output.contains("NoNewPrivs:\t1");
    assertTrue(noNewPrivileges, output);
  }

  /**
   * A task that may throw anything.
   */
  @FunctionalInterface
  private interface ThrowingTask {
    void run() throws Throwable;
  }
}
