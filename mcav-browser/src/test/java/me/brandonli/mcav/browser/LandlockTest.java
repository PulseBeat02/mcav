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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests {@link Landlock}: the rights of each version and rule, and, where the kernel has Landlock, a restricted thread
 * and the threads and processes it starts. A restriction cannot be lifted, so each one runs on a thread of its own.
 */
class LandlockTest {

  // the rights by their bits: execute 0, write a file 1, read a file 2, read a folder 3, ... refer 13, truncate 14,
  // control a device 15
  private static final long READ_FOLDER = 0b1101;
  private static final long READ_FILE = 0b101;

  @TempDir
  private Path folder;

  @Test
  void readsTheAnswerOfTheKernel() {
    assertEquals(4, Landlock.versionOf(4, 0));
    assertEquals(-38, Landlock.versionOf(-1, 38));
  }

  @Test
  void saysWhyThereIsNoLandlock() {
    assertTrue(Landlock.describeMissing(-38).contains("5.13"));
    assertTrue(Landlock.describeMissing(-95).contains("turned off"));
    assertEquals("Landlock is not available here (error 1)", Landlock.describeMissing(-1));
  }

  @Test
  void eachVersionHandlesTheRightsItKnows() {
    assertEquals(0x1FFF, Landlock.handledRights(1));
    assertEquals(0x3FFF, Landlock.handledRights(2));
    assertEquals(0x7FFF, Landlock.handledRights(3));
    assertEquals(0x7FFF, Landlock.handledRights(4));
    assertEquals(0xFFFF, Landlock.handledRights(5));
  }

  @Test
  void aRuleGrantsReadingAndRunningAndAWritableOneChangesToo() {
    final Path path = this.folder;
    final long all = Landlock.handledRights(5);
    assertEquals(READ_FOLDER, Landlock.rightsOf(new Landlock.Rule(path, false), true, all));
    assertEquals(READ_FILE, Landlock.rightsOf(new Landlock.Rule(path, false), false, all));
    // every right but making devices
    assertEquals(0xFFFF & ~(1L << 6) & ~(1L << 11), Landlock.rightsOf(new Landlock.Rule(path, true), true, all));
    // a file: execute, write, read, truncate, control a device
    assertEquals(0b1100_0000_0000_0111, Landlock.rightsOf(new Landlock.Rule(path, true), false, all));
    // the first version knows neither refer, truncate nor device control
    assertEquals(0x1FFF & ~(1L << 6) & ~(1L << 11), Landlock.rightsOf(new Landlock.Rule(path, true), true, Landlock.handledRights(1)));
  }

  @Test
  void aFailedCallOfTheKernelIsAnIOException() throws IOException {
    assertEquals(7, Landlock.check(7, 0, "count"));
    final IOException failure = assertThrows(IOException.class, () -> Landlock.check(-1, 22, "create a ruleset"));
    assertEquals("Landlock could not create a ruleset (error 22)", failure.getMessage());
  }

  @Test
  void aCallTheJvmCannotMakeIsAnIllegalState() throws ReflectiveOperationException {
    final IllegalCallerException refused = new IllegalCallerException("native access refused");
    final MethodHandle throwing = MethodHandles.throwException(long.class, IllegalCallerException.class);
    final MethodHandle call = MethodHandles.insertArguments(throwing, 0, refused);
    final IllegalStateException failure = assertThrows(IllegalStateException.class, () -> Landlock.invoke(call));
    assertSame(refused, failure.getCause());
    final MethodHandle answer = MethodHandles.constant(int.class, 3);
    assertEquals(3, Landlock.invoke(answer));
    final MethodHandle length = MethodHandles.lookup().findVirtual(String.class, "length", MethodType.methodType(int.class));
    assertEquals(4, Landlock.invoke(length, "four"));
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
      } catch (final Throwable thrown) {
        failure.set(thrown);
      }
    }, "landlock-test");
    thread.start();
    thread.join(TimeUnit.SECONDS.toMillis(60));
    assertTrue(!thread.isAlive(), "the restricted thread ended");
    return failure.get();
  }

  private static int availableVersion() {
    final int version = Landlock.version();
    assumeTrue(version >= 1, () -> "this kernel cannot restrict: " + Landlock.describeMissing(version));
    return version;
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void aRestrictedThreadReachesOnlyWhatItsRulesGrantAndSoDoesItsChildThread() throws Exception {
    final int version = availableVersion();
    final Path hidden = Files.createDirectories(this.folder.resolve("hidden"));
    final Path secret = Files.writeString(hidden.resolve("secret.txt"), "secret");
    final Path granted = Files.writeString(hidden.resolve("granted.txt"), "granted");
    final Path open = Files.createDirectories(this.folder.resolve("open"));
    final Path readable = Files.writeString(open.resolve("readable.txt"), "readable");
    final Path writable = Files.createDirectories(this.folder.resolve("writable"));
    final List<Landlock.Rule> rules = new ArrayList<>(
      ChromiumConfinement.rules(Path.of("/"), List.of(hidden), List.of(granted), List.of(writable))
    );
    // a path deleted before the restriction is left out
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
      assertInstanceOf(AccessDeniedException.class, childFailure.get(), "a thread started afterwards is restricted too");
    });
    assertNull(failure, () -> "the restricted thread failed: " + failure);
    assertEquals(List.of("readable", "granted", "new.txt"), seen);
    assertEquals("secret", Files.readString(secret, StandardCharsets.UTF_8), "the other threads are free");
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void aProcessStartedByARestrictedThreadIsRestrictedToo() throws Exception {
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
    assertTrue(probe.waitFor(60, TimeUnit.SECONDS));
    assertEquals(0, probe.exitValue(), output);
    assertTrue(output.contains("cat exited 1"), output);
    assertTrue(output.contains("Permission denied"), output);
    assertTrue(output.contains("NoNewPrivs:\t1"), output);
  }

  /**
   * A task that may throw anything.
   */
  @FunctionalInterface
  private interface ThrowingTask {
    void run() throws Throwable;
  }
}
