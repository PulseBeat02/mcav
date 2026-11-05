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

// A restriction cannot be lifted, so each one runs on a thread of its own.
class LandlockTest {

  private static final String RULESET_FAILURE_MESSAGE = "Landlock could not create a ruleset because of error 22 from the kernel!";

  private static final int MISSING_SYSTEM_CALL_ERROR = -38;

  private static final String HIDDEN_FOLDER = "hidden";
  private static final String SECRET_FILE = "secret.txt";
  private static final String GRANTED_FILE = "granted.txt";
  private static final String OPEN_FOLDER = "open";
  private static final String READABLE_FILE = "readable.txt";
  private static final String WRITABLE_FOLDER = "writable";
  private static final String DELETED_FOLDER = "deleted";
  private static final String NEW_FILE = "new.txt";
  private static final String JAVA_BIN_FOLDER = "bin";
  private static final String JAVA_EXECUTABLE = "java";

  private static final String SECRET_TEXT = "secret";

  private static final int VERSION_ONE_RIGHTS = 0x1FFF;

  private static final int VERSION_FIVE_RIGHTS = 0xFFFF;

  private static final int JOIN_TIMEOUT_SECONDS = 60;

  private static final String JAVA_HOME_PROPERTY = "java.home";
  private static final String CLASS_PATH_PROPERTY = "java.class.path";

  private static final long READ_FOLDER = 0b1101;
  private static final long READ_FILE = 0b101;

  private static final Path ROOT = Path.of("/");

  // the kernel stacks at most 16 rulesets on a thread, so a later restriction fails with E2BIG
  private static final int MORE_THAN_THE_STACKED_RULESETS = 64;

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
    assertEquals(MISSING_SYSTEM_CALL_ERROR, negatedErrorNumber);

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
    final boolean namesTheKernelRelease = Landlock.describeMissing(MISSING_SYSTEM_CALL_ERROR).contains("5.13");
    assertTrue(namesTheKernelRelease);

    final boolean saysTurnedOff = Landlock.describeMissing(-95).contains("turned off");
    assertTrue(saysTurnedOff);

    final String otherErrorDescription = Landlock.describeMissing(-1);
    assertEquals("error 1 from the kernel keeps Landlock off", otherErrorDescription);
  }

  @Test
  void shouldHandleTheRightsEachVersionKnows() {
    final long versionOneRights = Landlock.handledRights(1);
    assertEquals(VERSION_ONE_RIGHTS, versionOneRights);

    final long versionTwoRights = Landlock.handledRights(2);
    assertEquals(0x3FFF, versionTwoRights);

    final long versionThreeRights = Landlock.handledRights(3);
    assertEquals(0x7FFF, versionThreeRights);

    final long versionFourRights = Landlock.handledRights(4);
    assertEquals(0x7FFF, versionFourRights);

    final long versionFiveRights = Landlock.handledRights(5);
    assertEquals(VERSION_FIVE_RIGHTS, versionFiveRights);
  }

  @Test
  void shouldGrantReadingAndRunningInARuleAndChangesInAWritableOne() {
    final Path path = this.folder;
    final long all = Landlock.handledRights(5);
    final long readOnlyFolderRights = Landlock.rightsOf(new Landlock.Rule(path, Landlock.Access.READ_ONLY), Landlock.PathKind.FOLDER, all);
    assertEquals(READ_FOLDER, readOnlyFolderRights);

    final long readOnlyFileRights = Landlock.rightsOf(new Landlock.Rule(path, Landlock.Access.READ_ONLY), Landlock.PathKind.FILE, all);
    assertEquals(READ_FILE, readOnlyFileRights);

    final long writableFolderRights = Landlock.rightsOf(new Landlock.Rule(path, Landlock.Access.READ_WRITE), Landlock.PathKind.FOLDER, all);
    final long expectedWritableFolderRights = VERSION_FIVE_RIGHTS & ~(1L << 6) & ~(1L << 11);
    assertEquals(expectedWritableFolderRights, writableFolderRights);

    final long writableFileRights = Landlock.rightsOf(new Landlock.Rule(path, Landlock.Access.READ_WRITE), Landlock.PathKind.FILE, all);
    assertEquals(0b1100_0000_0000_0111, writableFileRights);

    final long versionOneWritableFolderRights = Landlock.rightsOf(
      new Landlock.Rule(path, Landlock.Access.READ_WRITE),
      Landlock.PathKind.FOLDER,
      Landlock.handledRights(1)
    );
    final long expectedVersionOneWritableFolderRights = VERSION_ONE_RIGHTS & ~(1L << 6) & ~(1L << 11);
    assertEquals(expectedVersionOneWritableFolderRights, versionOneWritableFolderRights);
  }

  @Test
  void shouldTreatAFailedCallOfTheKernelAsAnIoException() throws IOException {
    final long checkedAnswer = Landlock.check(7, 0, "count");
    assertEquals(7, checkedAnswer);

    final IOException failure = assertThrows(IOException.class, () -> Landlock.check(-1, 22, "create a ruleset"));
    final String message = failure.getMessage();
    assertEquals(RULESET_FAILURE_MESSAGE, message);
  }

  @Test
  void shouldTreatACallTheJvmCannotMakeAsAnIllegalState() throws ReflectiveOperationException {
    final IllegalCallerException refused = new IllegalCallerException("Native access was refused!");
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
    final OutOfMemoryError exhausted = new OutOfMemoryError("The call has no memory available!");
    final MethodHandle throwing = MethodHandles.throwException(long.class, OutOfMemoryError.class);
    final MethodHandle call = MethodHandles.insertArguments(throwing, 0, exhausted);
    final OutOfMemoryError thrown = assertThrows(OutOfMemoryError.class, () -> Landlock.invoke(call));
    assertSame(exhausted, thrown);
  }

  private Throwable onThread(final ThrowingTask task) throws InterruptedException {
    final AtomicReference<Throwable> failure = new AtomicReference<>();
    final Thread thread = new Thread(() -> this.runAndRecordFailure(task, failure), "landlock-test");
    thread.start();
    thread.join(TimeUnit.SECONDS.toMillis(JOIN_TIMEOUT_SECONDS));
    final boolean ended = !thread.isAlive();
    assertTrue(ended, "the restricted thread ended");

    return failure.get();
  }

  private void runAndRecordFailure(final ThrowingTask task, final AtomicReference<Throwable> failure) {
    try {
      task.run();
    } catch (final Throwable exception) {
      failure.set(exception);
    }
  }

  private int availableVersion() {
    final int version = Landlock.version();
    assumeTrue(version >= 1, () -> "%s, so this kernel cannot restrict!".formatted(Landlock.describeMissing(version)));

    return version;
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void shouldRefuseRightsTheKernelDoesNotKnowWithItsErrorNumber() throws InterruptedException {
    final int version = this.availableVersion();
    assumeTrue(version < 5, () -> "Landlock %s of this kernel already knows every right of the fifth version!".formatted(version));
    final Throwable failure = this.onThread(() -> Landlock.restrictThread(5, List.of()));
    assertInstanceOf(IOException.class, failure);

    final String message = failure.getMessage();
    assertEquals(RULESET_FAILURE_MESSAGE, message);
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void shouldLetARestrictedThreadAndItsChildThreadReachOnlyWhatItsRulesGrant() throws Exception {
    final int version = this.availableVersion();
    final Path hidden = Files.createDirectories(this.folder.resolve(HIDDEN_FOLDER));
    final Path secret = Files.writeString(hidden.resolve(SECRET_FILE), SECRET_TEXT);
    final Path granted = Files.writeString(hidden.resolve(GRANTED_FILE), "granted");
    final Path open = Files.createDirectories(this.folder.resolve(OPEN_FOLDER));
    final Path readable = Files.writeString(open.resolve(READABLE_FILE), "readable");
    final Path writable = Files.createDirectories(this.folder.resolve(WRITABLE_FOLDER));
    final List<Landlock.Rule> rules = new ArrayList<>(
      ChromiumConfinement.rules(ROOT, List.of(hidden), List.of(granted), List.of(writable))
    );
    rules.add(new Landlock.Rule(this.folder.resolve(DELETED_FOLDER), Landlock.Access.READ_ONLY));
    final List<String> seen = new ArrayList<>();
    final Throwable failure = this.onThread(() ->
      this.restrictAndCheckAccess(version, rules, seen, readable, granted, secret, open, writable)
    );
    assertNull(failure, () -> "the restricted thread failed: " + failure);
    final List<String> expectedSeen = List.of("readable", "granted", NEW_FILE);
    assertEquals(expectedSeen, seen);

    final String secretText = Files.readString(secret, StandardCharsets.UTF_8);
    assertEquals(SECRET_TEXT, secretText, "the other threads are free");
  }

  private void restrictAndCheckAccess(
    final int version,
    final List<Landlock.Rule> rules,
    final List<String> seen,
    final Path readable,
    final Path granted,
    final Path secret,
    final Path open,
    final Path writable
  ) throws IOException, InterruptedException {
    Landlock.restrictThread(version, rules);
    seen.add(Files.readString(readable, StandardCharsets.UTF_8));
    seen.add(Files.readString(granted, StandardCharsets.UTF_8));
    assertThrows(AccessDeniedException.class, () -> Files.readString(secret, StandardCharsets.UTF_8));
    assertThrows(AccessDeniedException.class, () -> Files.writeString(open.resolve(NEW_FILE), "new"));

    seen.add(Files.writeString(writable.resolve(NEW_FILE), "written").getFileName().toString());
    final AtomicReference<Throwable> childFailure = new AtomicReference<>();
    final Thread child = new Thread(() -> this.recordSecretRefusal(childFailure, secret));
    child.start();
    child.join();
    final Throwable childThrown = childFailure.get();
    assertInstanceOf(AccessDeniedException.class, childThrown, "a thread started afterwards is restricted too");
  }

  private void recordSecretRefusal(final AtomicReference<Throwable> childFailure, final Path secret) {
    childFailure.set(assertThrows(AccessDeniedException.class, () -> Files.readString(secret, StandardCharsets.UTF_8)));
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void shouldRestrictAProcessStartedByARestrictedThreadToo() throws Exception {
    this.availableVersion();
    final Path hidden = Files.createDirectories(this.folder.resolve(HIDDEN_FOLDER));
    final Path secret = Files.writeString(hidden.resolve(SECRET_FILE), SECRET_TEXT);
    final String java = Path.of(System.getProperty(JAVA_HOME_PROPERTY), JAVA_BIN_FOLDER, JAVA_EXECUTABLE).toString();
    final Process probe = new ProcessBuilder(
      java,
      "--enable-native-access=ALL-UNNAMED",
      "-cp",
      System.getProperty(CLASS_PATH_PROPERTY),
      ConfinedProbeMain.class.getName(),
      hidden.toString(),
      secret.toString()
    )
      .redirectErrorStream(true)
      .start();
    final String output = new String(probe.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    final boolean exited = probe.waitFor(JOIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
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

  @Test
  @EnabledOnOs(OS.LINUX)
  void shouldRefuseMoreStackedRulesetsThanTheKernelAllowsWithItsErrorNumber() throws InterruptedException {
    final int version = this.availableVersion();
    final Throwable failure = this.onThread(() -> this.restrictUntilRefused(version));
    assertInstanceOf(IOException.class, failure);

    final String message = failure.getMessage();
    assertEquals("Landlock could not restrict the thread because of error 7 from the kernel!", message);
  }

  private void restrictUntilRefused(final int version) throws IOException {
    for (int ruleset = 0; ruleset < MORE_THAN_THE_STACKED_RULESETS; ruleset++) {
      Landlock.restrictThread(version, List.of());
    }
  }

  @FunctionalInterface
  private interface ThrowingTask {
    void run() throws Throwable;
  }
}
