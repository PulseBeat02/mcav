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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import me.brandonli.mcav.utils.os.Os;
import me.brandonli.mcav.vm.testing.FakeProcess;
import me.brandonli.mcav.vm.testing.FreePorts;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;

final class VmProcessTest {

  private static final Path QEMU = Path.of("qemu-system-x86_64");

  private static final String MISSING_KVM_DEVICE = "kvm";
  private static final String WRITABLE_KVM_DEVICE = "writable-kvm";
  private static final String MISSING_QEMU = "missing-qemu";

  private static final String SECRET_FOLDER = "secret";
  private static final String PASSWORD_FILE = "password";
  private static final String OTHER_FILE = "other";
  private static final String COMMA_FOLDER = "a,b";
  private static final String ESCAPED_COMMA_FOLDER = "a,,b";

  private static final String ZIP_FILE = "folders.zip";
  private static final String ZIP_ROOT = "/";

  private static final InetAddress LOOPBACK = InetAddress.ofLiteral("127.0.0.1");
  private static final int CONNECTION_BACKLOG = 50;

  private static final int WIDTH = 64;
  private static final int HEIGHT = 48;
  private static final int FRAME_RATE = 10;

  private static final int DISPLAY_ONE_PORT = 5901;
  private static final int DISPLAY_THREE_PORT = 5903;
  private static final int DISPLAY_SEVEN_PORT = 5907;

  private static final String VGA_ARGUMENT = "-vga";
  private static final String DISPLAY_ARGUMENT = "-display";
  private static final String NETWORK_ARGUMENT = "-nic";
  private static final String OBJECT_ARGUMENT = "-object";
  private static final String VNC_ARGUMENT = "-vnc";
  private static final String USB_ARGUMENT = "-usb";
  private static final String DEVICE_ARGUMENT = "-device";
  private static final String MACHINE_ARGUMENT = "-machine";
  private static final String SHORT_MACHINE_ARGUMENT = "-M";
  private static final String ACCELERATOR_ARGUMENT = "-accel";
  private static final String AUDIO_DEVICE_ARGUMENT = "-audiodev";

  private static final String MACHINE_OPTION = "machine";
  private static final String SHORT_MACHINE_OPTION = "M";
  private static final String NAME_OPTION = "name";

  private static final String STANDARD_VGA = "std";
  private static final String NO_DISPLAY = "none";
  private static final String RESTRICTED_NETWORK = "user,restrict=on";
  private static final String TABLET_DEVICE = "usb-tablet";
  private static final String SECRET_OBJECT_PREFIX = "secret,id=mcav-vnc-password,file=";
  private static final String VNC_ON_DISPLAY_ONE = "127.0.0.1:1,share=force-shared,password-secret=mcav-vnc-password";

  private static final String WINDOWS_ACCELERATOR = "whpx";
  private static final String MAC_ACCELERATOR = "hvf";
  private static final String KVM_ACCELERATOR = "kvm";
  private static final String SOFTWARE_ACCELERATOR = "tcg";

  private static final String Q35_MACHINE = "q35";
  private static final String MICROVM_MACHINE = "microvm";
  private static final String Q35_WITH_USB = "q35,usb=on";
  private static final String Q35_WITHOUT_USB = "q35,usb=off";
  private static final String USB_ON = "usb=on";
  private static final String ROUTED_PC_MACHINE = "pc,pcspk-audiodev=snd0";
  private static final String HOST_CPU = "host";

  private static final String SPEAKER_ROUTE = "pcspk-audiodev=mcav-audio";
  private static final String HDA_SOUND_CARD = "intel-hda,id=mcav-sound";
  private static final String ICH9_SOUND_CARD = "ich9-intel-hda,id=mcav-sound";

  private static final Pattern PASSWORD_PATTERN = Pattern.compile("[A-Za-z0-9]{8}");

  private static final int FAILURE_EXIT_CODE = 1;
  private static final int KILLED_EXIT_CODE = 137;

  private static final String LINE_BREAK = "\n";
  private static final String WHPX_FAILURE_OUTPUT = "WHPX: No accelerator found\n";
  private static final String DISK_FAILURE_OUTPUT = "could not open disk image alpine.qcow2\n";

  private static final String DRAIN_THREAD_NAME = "mcav-qemu-output";
  private static final String OUTPUT_JOIN_METHOD = "awaitOutput";

  private static final long SHORT_TIMEOUT_MILLISECONDS = 400L;
  private static final long TIMED_JOIN_DEADLINE_SECONDS = 1L;
  private static final long SHUTDOWN_JOIN_MILLISECONDS = 3_000L;
  private static final long POLL_PAUSE_MILLISECONDS = 1L;

  private static final int BYTE_MASK = 0xFF;

  @TempDir
  private Path directory;

  private final List<List<String>> commands = new CopyOnWriteArrayList<>();
  private final Deque<Object> outcomes = new ArrayDeque<>();
  private final List<ServerSocket> sockets = new CopyOnWriteArrayList<>();
  private int port;
  private ServerSocket display;
  private Path missingKvm;

  @BeforeEach
  void choosePort() {
    this.port = FreePorts.find();
    this.missingKvm = this.directory.resolve(MISSING_KVM_DEVICE);
  }

  @AfterEach
  void closeSockets() throws IOException {
    for (final ServerSocket socket : this.sockets) {
      socket.close();
    }
  }

  private ServerSocket listen(final int listenPort) throws IOException {
    final ServerSocket socket = new ServerSocket(listenPort, CONNECTION_BACKLOG, LOOPBACK);
    this.sockets.add(socket);
    return socket;
  }

  private void openDisplay() throws IOException {
    if (this.display == null) {
      this.display = this.listen(this.port);
    }
  }

  private Path secretFolder() throws IOException {
    final Path folder = this.directory.resolve(SECRET_FOLDER);
    return Files.createDirectories(folder);
  }

  private Path passwordFile() {
    final Path folder = this.directory.resolve(SECRET_FOLDER);
    return folder.resolve(PASSWORD_FILE);
  }

  private String secretObject() {
    final Path file = this.passwordFile();
    return SECRET_OBJECT_PREFIX + file;
  }

  private VmSettings reachableSettings() {
    return new VmSettings(this.port, WIDTH, HEIGHT, FRAME_RATE);
  }

  private VmSettings unreachableSettings() {
    final int unusedPort = FreePorts.find();
    return new VmSettings(unusedPort, WIDTH, HEIGHT, FRAME_RATE);
  }

  private VmProcess process(final VmSettings settings, final VmConfiguration configuration, final Os os, final Object... results) {
    for (final Object result : results) {
      this.outcomes.addLast(result);
    }

    final VmProcess.Launcher launcher = command -> {
      this.commands.add(command);
      final Object next = this.outcomes.removeFirst();
      if (next instanceof final IOException failure) {
        throw failure;
      }
      final Process started = (Process) next;
      final boolean alive = started.isAlive();
      if (alive) {
        this.openDisplay();
      }
      return started;
    };
    return new VmProcess(
      settings,
      VmPlayer.Architecture.AARCH64,
      QEMU,
      configuration,
      launcher,
      os,
      this.missingKvm,
      SHORT_TIMEOUT_MILLISECONDS,
      this::secretFolder
    );
  }

  private VmProcess reachable(final Os os, final Object... results) {
    final VmSettings settings = this.reachableSettings();
    final VmConfiguration configuration = VmConfiguration.builder().build();
    return this.process(settings, configuration, os, results);
  }

  private List<String> command(final String... arguments) {
    final List<String> command = new ArrayList<>();
    final String program = QEMU.toString();
    command.add(program);
    final List<String> given = List.of(arguments);
    command.addAll(given);
    final List<String> defaultOptions = this.defaults();
    command.addAll(defaultOptions);
    return command;
  }

  private List<String> defaults() {
    final int display = this.port - VmProcess.FIRST_VNC_PORT;
    return List.of(
      VGA_ARGUMENT,
      STANDARD_VGA,
      DISPLAY_ARGUMENT,
      NO_DISPLAY,
      NETWORK_ARGUMENT,
      RESTRICTED_NETWORK,
      OBJECT_ARGUMENT,
      this.secretObject(),
      VNC_ARGUMENT,
      "127.0.0.1:%s,share=force-shared,password-secret=mcav-vnc-password".formatted(display),
      USB_ARGUMENT,
      DEVICE_ARGUMENT,
      TABLET_DEVICE
    );
  }

  private List<String> commandWithoutAccelerator(final VmSettings settings, final VmConfiguration configuration) {
    final VmProcess process = this.process(settings, configuration, Os.LINUX);
    return process.buildCommand(null, this.passwordFile());
  }

  private List<String> expectedOnDisplayOne(final List<String> options, final List<String> usbOptions) {
    final String program = QEMU.toString();
    final List<String> displayDefaults = List.of(
      VGA_ARGUMENT,
      STANDARD_VGA,
      DISPLAY_ARGUMENT,
      NO_DISPLAY,
      NETWORK_ARGUMENT,
      RESTRICTED_NETWORK,
      OBJECT_ARGUMENT,
      this.secretObject(),
      VNC_ARGUMENT,
      VNC_ON_DISPLAY_ONE
    );
    final List<String> expected = new ArrayList<>();
    expected.add(program);
    expected.addAll(options);
    expected.addAll(displayDefaults);
    expected.addAll(usbOptions);
    return expected;
  }

  @Test
  void shouldStartWithTheAcceleratorOfTheMachineAndWaitForTheDisplay() {
    final FakeProcess qemu = FakeProcess.running("");
    final VmSettings settings = this.reachableSettings();
    final VmConfiguration configuration = VmConfiguration.builder().memory(64).build();
    final VmProcess process = this.process(settings, configuration, Os.WINDOWS, qemu);
    final boolean aliveBefore = process.isAlive();
    process.start();
    final boolean alive = process.isAlive();
    final List<String> expected = this.command("-m", "64M", ACCELERATOR_ARGUMENT, WINDOWS_ACCELERATOR);
    assertFalse(aliveBefore);
    assertTrue(alive);

    final List<List<String>> expectedCommands = List.of(expected);
    assertEquals(expectedCommands, this.commands);

    process.shutdown();
    final boolean aliveAfter = process.isAlive();
    final int destroyCalls = qemu.getDestroyCalls();
    final int forcibleCalls = qemu.getForcibleDestroyCalls();
    assertFalse(aliveAfter);
    assertEquals(1, destroyCalls);
    assertEquals(0, forcibleCalls);
  }

  @ParameterizedTest
  @ValueSource(
    strings = {
      "WHPX: No accelerator found, hr=80070057",
      "qemu-system-x86_64: invalid accel option",
      "failed to initialize the hypervisor",
      "Virtualization is disabled in the firmware",
    }
  )
  void shouldRetryWithSoftwareEmulationWhenTheAcceleratorFails(final String failure) {
    final FakeProcess failed = FakeProcess.exited(FAILURE_EXIT_CODE, failure + LINE_BREAK);
    final FakeProcess working = FakeProcess.running("");
    final VmProcess process = this.reachable(Os.WINDOWS, failed, working);
    process.start();
    final boolean alive = process.isAlive();
    final List<String> first = this.command(ACCELERATOR_ARGUMENT, WINDOWS_ACCELERATOR);
    final List<String> second = this.command(ACCELERATOR_ARGUMENT, SOFTWARE_ACCELERATOR);
    assertTrue(alive);

    final List<List<String>> expectedCommands = List.of(first, second);
    assertEquals(expectedCommands, this.commands);
  }

  @Test
  void shouldForgetTheOutputOfTheAttemptThatFailed() {
    final FakeProcess failed = FakeProcess.exited(FAILURE_EXIT_CODE, WHPX_FAILURE_OUTPUT);
    final FakeProcess failedAgain = FakeProcess.exited(2, DISK_FAILURE_OUTPUT);
    final VmProcess process = this.reachable(Os.WINDOWS, failed, failedAgain);
    final VmException exception = assertThrows(VmException.class, process::start);
    final String message = exception.getMessage();
    assertEquals("QEMU exited with code 2, printing \"could not open disk image alpine.qcow2\"!", message);
  }

  @Test
  void shouldFailWithoutRetryingWhenTheFailureIsUnrelatedToTheAccelerator() {
    final FakeProcess failed = FakeProcess.exited(FAILURE_EXIT_CODE, DISK_FAILURE_OUTPUT);
    final VmProcess process = this.reachable(Os.MAC, failed);
    final VmException exception = assertThrows(VmException.class, process::start);
    final String message = exception.getMessage();
    final int launches = this.commands.size();
    final boolean alive = process.isAlive();
    assertEquals("QEMU exited with code 1, printing \"could not open disk image alpine.qcow2\"!", message);
    assertEquals(1, launches);
    assertFalse(alive);
  }

  @Test
  void shouldWaitForTheOutputThatExplainsTheExit() {
    final InputStream late = new DelayedStream(WHPX_FAILURE_OUTPUT, 300L);
    final FakeProcess failed = FakeProcess.running(late);
    failed.exit(FAILURE_EXIT_CODE);
    final FakeProcess working = FakeProcess.running("");
    final VmProcess process = this.reachable(Os.WINDOWS, failed, working);
    process.start();
    final int launches = this.commands.size();
    assertEquals(2, launches);
  }

  @Test
  void shouldUseSoftwareEmulationDirectlyWhenTheMachineHasNoAccelerator() {
    final FakeProcess qemu = FakeProcess.running("");
    final VmProcess process = this.reachable(Os.FREEBSD, qemu);
    process.start();
    final List<String> expected = this.command(ACCELERATOR_ARGUMENT, SOFTWARE_ACCELERATOR);
    final List<List<String>> expectedCommands = List.of(expected);
    assertEquals(expectedCommands, this.commands);
  }

  @Test
  void shouldNotRetrySoftwareEmulation() {
    final FakeProcess failed = FakeProcess.exited(3, "tcg accel failed to start\n");
    final VmProcess process = this.reachable(Os.LINUX, failed);
    final VmException exception = assertThrows(VmException.class, process::start);
    final String message = exception.getMessage();
    final int launches = this.commands.size();
    assertEquals("QEMU exited with code 3, printing \"tcg accel failed to start\"!", message);
    assertEquals(1, launches);
  }

  @ParameterizedTest
  @ValueSource(longs = { 0L, Long.MAX_VALUE - 100L })
  void shouldRejectAReachableDisplayAtTheStartupDeadline(final long origin) {
    final FakeProcess qemu = FakeProcess.running("");
    final long duration = TimeUnit.MILLISECONDS.toNanos(SHORT_TIMEOUT_MILLISECONDS);
    final Deque<Long> times = new ArrayDeque<>(List.of(origin, origin + duration));
    final LongSupplier clock = times::removeFirst;
    final VmSettings settings = this.reachableSettings();
    final VmConfiguration configuration = VmConfiguration.builder().build();
    final VmProcess.Launcher launcher = command -> {
      this.openDisplay();
      return qemu;
    };
    final VmProcess process = new VmProcess(
      settings,
      VmPlayer.Architecture.AARCH64,
      QEMU,
      configuration,
      launcher,
      Os.FREEBSD,
      this.missingKvm,
      SHORT_TIMEOUT_MILLISECONDS,
      clock,
      this::secretFolder
    );
    assertThrows(VmException.class, process::start);

    final boolean alive = process.isAlive();
    assertFalse(alive, "an expired startup must terminate QEMU even if its display is reachable");
  }

  @Test
  void shouldAcceptAReachableDisplayBeforeTheStartupDeadline() {
    final FakeProcess qemu = FakeProcess.running("");
    final long duration = TimeUnit.MILLISECONDS.toNanos(SHORT_TIMEOUT_MILLISECONDS);
    final Deque<Long> times = new ArrayDeque<>(List.of(0L, duration - 1));
    final LongSupplier clock = times::removeFirst;
    final VmSettings settings = this.reachableSettings();
    final VmConfiguration configuration = VmConfiguration.builder().build();
    final VmProcess.Launcher launcher = command -> {
      this.openDisplay();
      return qemu;
    };
    final VmProcess process = new VmProcess(
      settings,
      VmPlayer.Architecture.AARCH64,
      QEMU,
      configuration,
      launcher,
      Os.FREEBSD,
      this.missingKvm,
      SHORT_TIMEOUT_MILLISECONDS,
      clock,
      this::secretFolder
    );
    try {
      process.start();
      final boolean alive = process.isAlive();
      assertTrue(alive);
    } finally {
      process.shutdown();
    }
  }

  @Test
  void shouldKeepTheAcceleratorOfTheConfiguration() {
    final FakeProcess failed = FakeProcess.exited(FAILURE_EXIT_CODE, "kvm accel is not available\n");
    final VmSettings settings = this.reachableSettings();
    final VmConfiguration configuration = VmConfiguration.builder().accelerator(KVM_ACCELERATOR).build();
    final VmProcess process = this.process(settings, configuration, Os.WINDOWS, failed);
    assertThrows(VmException.class, process::start);

    final List<String> expected = this.command(ACCELERATOR_ARGUMENT, KVM_ACCELERATOR);
    final List<List<String>> expectedCommands = List.of(expected);
    assertEquals(expectedCommands, this.commands);
  }

  @Test
  void shouldAddNoAcceleratorWhenKvmIsEnabledByAFlag() {
    final FakeProcess qemu = FakeProcess.running("");
    final VmSettings settings = this.reachableSettings();
    final VmConfiguration configuration = VmConfiguration.builder().flag("enable-kvm").build();
    final VmProcess process = this.process(settings, configuration, Os.MAC, qemu);
    process.start();
    final List<String> expected = this.command("-enable-kvm");
    final List<List<String>> expectedCommands = List.of(expected);
    assertEquals(expectedCommands, this.commands);
  }

  @Test
  void shouldAskForAPasswordOnlyTheServerKnowsOnTheDisplay() throws IOException {
    final List<String> secrets = new CopyOnWriteArrayList<>();
    final VmSettings settings = this.reachableSettings();
    final VmProcess.Launcher launcher = command -> {
      this.commands.add(command);
      final int object = command.indexOf(OBJECT_ARGUMENT);
      final boolean hasSecretObject = object > 0;
      assertTrue(hasSecretObject, () -> "a secret object in %s".formatted(command));
      final String secret = command.get(object + 1);
      final boolean prefixed = secret.startsWith(SECRET_OBJECT_PREFIX);
      assertTrue(prefixed, secret);
      final Path file = Path.of(secret.substring(SECRET_OBJECT_PREFIX.length()));
      secrets.add(Files.readString(file, StandardCharsets.US_ASCII));
      this.openDisplay();
      return FakeProcess.running("");
    };
    final VmProcess process = new VmProcess(
      settings,
      VmPlayer.Architecture.AARCH64,
      QEMU,
      VmConfiguration.builder().build(),
      launcher,
      Os.LINUX,
      this.missingKvm,
      SHORT_TIMEOUT_MILLISECONDS,
      this::secretFolder
    );
    process.start();
    try {
      final List<String> command = this.commands.getFirst();
      final String vnc = command.get(command.indexOf(VNC_ARGUMENT) + 1);
      final boolean protectedByPassword = vnc.endsWith(",password-secret=mcav-vnc-password");
      final int secretCount = secrets.size();
      assertTrue(protectedByPassword, vnc);
      assertEquals(1, secretCount);

      final String password = secrets.getFirst();
      final Matcher matcher = PASSWORD_PATTERN.matcher(password);
      final boolean wellFormed = matcher.matches();
      assertTrue(wellFormed, password);

      final String secret = command.get(command.indexOf(OBJECT_ARGUMENT) + 1);
      final Path file = Path.of(secret.substring(SECRET_OBJECT_PREFIX.length()));
      final Path folder = this.directory.resolve(SECRET_FOLDER);
      final boolean leftOnDisk = Files.exists(file);
      final boolean folderLeft = Files.exists(folder);
      assertFalse(leftOnDisk, "QEMU read the password, so it is not left on disk");
      assertFalse(folderLeft, "nor is the folder that held it");
    } finally {
      process.shutdown();
    }
  }

  @Test
  void shouldGiveEveryProcessAPasswordOfItsOwn() {
    final VmProcess first = this.reachable(Os.LINUX);
    final VmProcess second = this.reachable(Os.LINUX);
    final String firstPassword = first.getPassword();
    final String secondPassword = second.getPassword();
    final Matcher firstMatcher = PASSWORD_PATTERN.matcher(firstPassword);
    final Matcher secondMatcher = PASSWORD_PATTERN.matcher(secondPassword);
    final boolean firstWellFormed = firstMatcher.matches();
    final boolean secondWellFormed = secondMatcher.matches();
    final boolean samePassword = firstPassword.equals(secondPassword);
    assertTrue(firstWellFormed, firstPassword);
    assertTrue(secondWellFormed, secondPassword);
    assertFalse(samePassword, "random passwords");
  }

  @Test
  void shouldOpenThePasswordFolderOnlyToTheServersUser() throws IOException {
    final Map<String, String> posixZip = Map.of("create", "true", "enablePosixFileAttributes", "true");
    try (final FileSystem zipped = FileSystems.newFileSystem(this.directory.resolve(ZIP_FILE), posixZip)) {
      // Windows' own file system has no POSIX permissions (VmProcess asks for them only where it has), so there the
      // owner-only branch makes its folder in the zip file system, which has them
      final FileSystem fileSystem = this.directory.getFileSystem();
      final Set<String> views = fileSystem.supportedFileAttributeViews();
      final boolean posixHere = views.contains("posix");
      final Path posix = VmProcess.createSecretFolder(
        posixHere ? this.directory : zipped.getPath(ZIP_ROOT),
        VmProcess.FolderAccess.OWNER_ONLY
      );
      final String permissions = PosixFilePermissions.toString(Files.getPosixFilePermissions(posix));
      assertEquals("rwx------", permissions);

      final Path windows = VmProcess.createSecretFolder(this.directory, VmProcess.FolderAccess.INHERITED);
      final boolean windowsFolderMade = Files.isDirectory(windows);
      final boolean sameFolder = posix.equals(windows);
      assertTrue(windowsFolderMade);
      assertFalse(sameFolder, "a folder of its own for every start");

      final Path zippedFolder = VmProcess.createSecretFolder(zipped.getPath(ZIP_ROOT), VmProcess.FolderAccess.OWNER_ONLY);
      final String zippedPermissions = PosixFilePermissions.toString(Files.getPosixFilePermissions(zippedFolder));
      assertEquals("rwx------", zippedPermissions);
    }
  }

  @Test
  void shouldLetThePasswordFolderInheritItsAccessWithoutPosixPermissions() {
    final VmProcess.FolderAccess access = VmProcess.chooseSecretFolderAccess(Set.of("acl", "basic", "dos", "owner", "user"));
    assertEquals(VmProcess.FolderAccess.INHERITED, access);
  }

  @Test
  void shouldTellTheLauncherWhenTheMachineItStartedHasEnded() {
    final FakeProcess qemu = FakeProcess.running("");
    final List<Process> ended = new ArrayList<>();
    final VmProcess.Launcher launcher = new VmProcess.Launcher() {
      @Override
      public Process launch(final List<String> command) throws IOException {
        VmProcessTest.this.openDisplay();
        return qemu;
      }

      @Override
      public void ended(final Process process) {
        ended.add(process);
      }
    };
    final VmProcess process = new VmProcess(
      this.reachableSettings(),
      VmPlayer.Architecture.AARCH64,
      QEMU,
      VmConfiguration.builder().build(),
      launcher,
      Os.WINDOWS,
      this.missingKvm,
      SHORT_TIMEOUT_MILLISECONDS,
      this::secretFolder
    );
    process.start();
    final List<Process> endedWhileRunning = List.copyOf(ended);
    process.shutdown();

    final List<Process> expectedEnded = List.of(qemu);
    assertEquals(List.of(), endedWhileRunning);
    assertEquals(expectedEnded, ended, "a launcher that records its processes forgets one once it ended");
  }

  @Test
  void shouldWriteACommaOfThePasswordPathTwice() throws IOException {
    final VmSettings settings = this.reachableSettings();
    final Path folder = this.directory.resolve(COMMA_FOLDER);
    final VmProcess.Launcher launcher = command -> {
      this.commands.add(command);
      this.openDisplay();
      return FakeProcess.running("");
    };
    final VmProcess process = new VmProcess(
      settings,
      VmPlayer.Architecture.AARCH64,
      QEMU,
      VmConfiguration.builder().build(),
      launcher,
      Os.LINUX,
      this.missingKvm,
      SHORT_TIMEOUT_MILLISECONDS,
      () -> Files.createDirectories(folder)
    );
    process.start();
    process.shutdown();
    final List<String> command = this.commands.getFirst();
    final String secret = command.get(command.indexOf(OBJECT_ARGUMENT) + 1);
    final Path escapedFolder = this.directory.resolve(ESCAPED_COMMA_FOLDER);
    final Path secretFile = escapedFolder.resolve(PASSWORD_FILE);
    final String expected = SECRET_OBJECT_PREFIX + secretFile;
    assertEquals(expected, secret);
  }

  @Test
  void shouldStopTheStartBeforeQemuWhenThePasswordCannotBeWritten() {
    final VmSettings settings = this.reachableSettings();
    final VmProcess.Launcher launcher = command -> {
      throw new AssertionError("QEMU must not start without the password of its display!");
    };
    final VmProcess process = new VmProcess(
      settings,
      VmPlayer.Architecture.AARCH64,
      QEMU,
      VmConfiguration.builder().build(),
      launcher,
      Os.LINUX,
      this.missingKvm,
      SHORT_TIMEOUT_MILLISECONDS,
      () -> {
        throw new IOException("disk full!");
      }
    );
    final VmException failure = assertThrows(VmException.class, process::start);
    final String message = failure.getMessage();
    final boolean alive = process.isAlive();
    assertEquals("Password of the display could not be written because of \"disk full!\"!", message);
    assertFalse(alive);
  }

  @Test
  void shouldDeleteThePasswordOfAFailedStart() {
    final VmProcess process = this.reachable(Os.LINUX, new IOException("no QEMU!"));
    assertThrows(VmException.class, process::start);

    final Path folder = this.directory.resolve(SECRET_FOLDER);
    final boolean folderLeft = Files.exists(folder);
    assertFalse(folderLeft, "the password and its folder are gone");
  }

  @Test
  void shouldLeaveTheMachineRunningWhenThePasswordFolderCannotBeDeleted() throws IOException {
    final VmSettings settings = this.reachableSettings();
    final Path folder = this.directory.resolve(SECRET_FOLDER);
    final VmProcess.Launcher launcher = command -> {
      Files.writeString(folder.resolve(OTHER_FILE), "kept");
      this.openDisplay();
      return FakeProcess.running("");
    };
    final VmProcess process = new VmProcess(
      settings,
      VmPlayer.Architecture.AARCH64,
      QEMU,
      VmConfiguration.builder().build(),
      launcher,
      Os.LINUX,
      this.missingKvm,
      SHORT_TIMEOUT_MILLISECONDS,
      this::secretFolder
    );
    process.start();
    try {
      final boolean alive = process.isAlive();
      final boolean passwordLeft = Files.exists(folder.resolve(PASSWORD_FILE));
      final boolean otherKept = Files.exists(folder.resolve(OTHER_FILE));
      assertTrue(alive);
      assertFalse(passwordLeft, "the password itself is deleted");
      assertTrue(otherKept);
    } finally {
      process.shutdown();
    }
  }

  @Test
  void shouldGiveAMachineThatChoseNoNetworkOneThatReachesNeitherTheServerNorTheInternet() {
    final VmSettings settings = new VmSettings(DISPLAY_SEVEN_PORT, WIDTH, HEIGHT, FRAME_RATE);
    final VmConfiguration configuration = VmConfiguration.builder().build();
    final List<String> command = this.commandWithoutAccelerator(settings, configuration);
    final int network = command.indexOf(NETWORK_ARGUMENT);
    final boolean hasNetwork = network > 0;
    assertTrue(hasNetwork, () -> "a -nic option in %s".formatted(command));

    final String networkValue = command.get(network + 1);
    final int lastNetwork = command.lastIndexOf(NETWORK_ARGUMENT);
    assertEquals(RESTRICTED_NETWORK, networkValue);
    assertEquals(network, lastNetwork, "one network");
  }

  @ParameterizedTest
  @ValueSource(strings = { "nic", "netdev", "net" })
  void shouldKeepTheNetworkAMachineChose(final String option) {
    final VmSettings settings = new VmSettings(DISPLAY_SEVEN_PORT, WIDTH, HEIGHT, FRAME_RATE);
    final VmConfiguration configuration = VmConfiguration.builder().option(option, "user,id=mine").build();
    final List<String> command = this.commandWithoutAccelerator(settings, configuration);
    final boolean restricted = command.contains(RESTRICTED_NETWORK);
    assertFalse(restricted, () -> "no network added to %s".formatted(command));
  }

  @Test
  void shouldGiveAMachineWithoutDefaultDevicesNoNetwork() {
    final VmSettings settings = new VmSettings(DISPLAY_SEVEN_PORT, WIDTH, HEIGHT, FRAME_RATE);
    final VmConfiguration configuration = VmConfiguration.builder().flag("nodefaults").build();
    final List<String> command = this.commandWithoutAccelerator(settings, configuration);
    final boolean network = command.contains(NETWORK_ARGUMENT);
    assertFalse(network, () -> "no network added to %s".formatted(command));
  }

  @Test
  void shouldLeaveOutTheDefaultsTheConfigurationSets() {
    final VmSettings settings = new VmSettings(DISPLAY_SEVEN_PORT, WIDTH, HEIGHT, FRAME_RATE);
    final VmConfiguration configuration = VmConfiguration.builder()
      .vga("virtio")
      .option("display", "gtk")
      .option("usbdevice", "tablet")
      .build();
    final VmProcess process = this.process(settings, configuration, Os.LINUX);
    final List<String> command = process.buildCommand(null, this.passwordFile());
    final String program = QEMU.toString();
    final List<String> expected = List.of(
      program,
      VGA_ARGUMENT,
      "virtio",
      DISPLAY_ARGUMENT,
      "gtk",
      "-usbdevice",
      "tablet",
      NETWORK_ARGUMENT,
      RESTRICTED_NETWORK,
      OBJECT_ARGUMENT,
      this.secretObject(),
      VNC_ARGUMENT,
      "127.0.0.1:7,share=force-shared,password-secret=mcav-vnc-password"
    );
    assertEquals(expected, command);
  }

  private List<String> x86Command(final VmConfiguration configuration) {
    final VmSettings settings = new VmSettings(DISPLAY_THREE_PORT, WIDTH, HEIGHT, FRAME_RATE);
    final VmProcess.Launcher launcher = command -> {
      throw new IOException("not started!");
    };
    final VmProcess process = new VmProcess(
      settings,
      VmPlayer.Architecture.X86_64,
      QEMU,
      configuration,
      launcher,
      Os.LINUX,
      this.missingKvm,
      SHORT_TIMEOUT_MILLISECONDS,
      this::secretFolder
    );
    return process.buildCommand(null, this.passwordFile());
  }

  @Test
  void shouldGiveAPcMachineAnHdaCardThePcSpeakerAndTheSoundOnItsDisplay() {
    final List<String> command = this.x86Command(VmConfiguration.builder().build());
    final List<String> expected = List.of(
      QEMU.toString(),
      MACHINE_ARGUMENT,
      SPEAKER_ROUTE,
      VGA_ARGUMENT,
      STANDARD_VGA,
      DISPLAY_ARGUMENT,
      NO_DISPLAY,
      NETWORK_ARGUMENT,
      RESTRICTED_NETWORK,
      OBJECT_ARGUMENT,
      this.secretObject(),
      AUDIO_DEVICE_ARGUMENT,
      "none,id=mcav-audio,out.frequency=48000",
      DEVICE_ARGUMENT,
      HDA_SOUND_CARD,
      DEVICE_ARGUMENT,
      "hda-output,bus=mcav-sound.0,audiodev=mcav-audio",
      VNC_ARGUMENT,
      "127.0.0.1:3,share=force-shared,password-secret=mcav-vnc-password,audiodev=mcav-audio",
      USB_ARGUMENT,
      DEVICE_ARGUMENT,
      TABLET_DEVICE
    );
    assertEquals(expected, command);
  }

  @Test
  void shouldRouteTheSpeakerAsAMachineOptionOfItsOwnAndGiveQ35TheIch9Card() {
    final VmConfiguration q35 = VmConfiguration.builder().machine("q35,accel=tcg").build();
    final List<String> command = this.x86Command(q35);
    final boolean keepsTheMachine = command.containsAll(List.of(MACHINE_ARGUMENT, "q35,accel=tcg"));
    assertTrue(keepsTheMachine, command::toString);

    final int speaker = command.indexOf(SPEAKER_ROUTE);
    final String speakerOption = command.get(speaker - 1);
    assertEquals(MACHINE_ARGUMENT, speakerOption, command::toString);

    final boolean ich9Card = command.contains(ICH9_SOUND_CARD);
    assertTrue(ich9Card, command::toString);

    final VmConfiguration typed = VmConfiguration.builder().machine("type=pc-q35-8.2,usb=on").build();
    final List<String> typedCommand = this.x86Command(typed);
    final boolean typedIch9Card = typedCommand.contains(ICH9_SOUND_CARD);
    assertTrue(typedIch9Card);

    final VmConfiguration i440fx = VmConfiguration.builder().machine("pc-i440fx-8.2").build();
    final List<String> i440fxCommand = this.x86Command(i440fx);
    final boolean i440fxCard = i440fxCommand.contains(HDA_SOUND_CARD);
    assertTrue(i440fxCard);
  }

  @Test
  void shouldGiveMachinesWithoutAPcSpeakerOrAPciBusNoSound() {
    final VmConfiguration microvm = VmConfiguration.builder().machine(MICROVM_MACHINE).build();
    final List<String> command = this.x86Command(microvm);
    final boolean audioDevice = command.contains(AUDIO_DEVICE_ARGUMENT);
    final boolean display = command.contains("127.0.0.1:3,share=force-shared,password-secret=mcav-vnc-password");
    assertFalse(audioDevice, command::toString);
    assertTrue(display, command::toString);

    final VmConfiguration twice = VmConfiguration.builder().machine("type=pc,type=microvm,usb=off").build();
    final List<String> twiceCommand = this.x86Command(twice);
    final boolean twiceAudioDevice = twiceCommand.contains(AUDIO_DEVICE_ARGUMENT);
    assertFalse(twiceAudioDevice);

    final List<String> arm = this.commandWithoutAccelerator(this.reachableSettings(), VmConfiguration.builder().build());
    final boolean armAudioDevice = arm.contains(AUDIO_DEVICE_ARGUMENT);
    assertFalse(armAudioDevice);
  }

  @Test
  void shouldGiveAMachineNamedWithMSoundLikeOneNamedWithMachine() {
    final VmConfiguration legacy = VmConfiguration.builder().option(SHORT_MACHINE_OPTION, Q35_MACHINE).build();
    final List<String> command = this.x86Command(legacy);
    final boolean ich9Card = command.contains(ICH9_SOUND_CARD);
    assertTrue(ich9Card, command::toString);

    final boolean speakerRouted = command.contains(SPEAKER_ROUTE);
    assertTrue(speakerRouted, command::toString);

    final VmConfiguration routed = VmConfiguration.builder().option(SHORT_MACHINE_OPTION, ROUTED_PC_MACHINE).build();
    assertThrows(IllegalArgumentException.class, () -> VmProcess.checkModuleOptions(routed));

    final VmConfiguration repeated = VmConfiguration.builder().repeatable(MACHINE_OPTION, "pcspk-audiodev=snd0").build();
    assertThrows(IllegalArgumentException.class, () -> VmProcess.checkModuleOptions(repeated));
  }

  @Test
  void shouldNotConfuseTheSpeakerWithAValueThatLooksLikeTheMachineOption() {
    final VmConfiguration named = VmConfiguration.builder().option(NAME_OPTION, MACHINE_ARGUMENT).build();
    final List<String> command = this.x86Command(named);
    final boolean keepsTheName = command.containsAll(List.of("-name", MACHINE_ARGUMENT));
    final String speakerOption = command.get(command.indexOf(SPEAKER_ROUTE) - 1);
    assertTrue(keepsTheName, command::toString);
    assertEquals(MACHINE_ARGUMENT, speakerOption, command::toString);

    final VmConfiguration followed = VmConfiguration.builder()
      .machine(Q35_MACHINE)
      .option(NAME_OPTION, SHORT_MACHINE_ARGUMENT)
      .cpu(HOST_CPU)
      .build();
    final List<String> sound = this.x86Command(followed);
    final boolean ich9Card = sound.contains(ICH9_SOUND_CARD);
    final boolean speakerRouted = sound.contains(SPEAKER_ROUTE);
    assertTrue(ich9Card, sound::toString);
    assertTrue(speakerRouted, sound::toString);
  }

  @Test
  void shouldReadTheMachineTypeFromTheMachineOption() {
    final VmConfiguration none = VmConfiguration.builder().build();
    final String noneType = VmProcess.machineType(none);
    assertEquals("", noneType);

    final VmConfiguration named = VmConfiguration.builder().machine("Q35,usb=on").build();
    final String namedType = VmProcess.machineType(named);
    assertEquals(Q35_MACHINE, namedType);

    final VmConfiguration typed = VmConfiguration.builder().machine("usb=on,type=PC").build();
    final String typedType = VmProcess.machineType(typed);
    assertEquals("pc", typedType);

    final VmConfiguration properties = VmConfiguration.builder().machine("accel=kvm").build();
    final String propertiesType = VmProcess.machineType(properties);
    assertEquals("", propertiesType);

    final VmConfiguration last = VmConfiguration.builder().machine("pc,type=q35").build();
    final String lastType = VmProcess.machineType(last);
    assertEquals(Q35_MACHINE, lastType);

    final VmConfiguration both = VmConfiguration.builder()
      .machine("pc")
      .option(SHORT_MACHINE_OPTION, MICROVM_MACHINE)
      .repeatable(MACHINE_OPTION, USB_ON)
      .build();
    final String bothType = VmProcess.machineType(both);
    assertEquals(MICROVM_MACHINE, bothType);

    final VmConfiguration aliases = VmConfiguration.builder().option(SHORT_MACHINE_OPTION, Q35_MACHINE).machine(MICROVM_MACHINE).build();
    final String aliasesType = VmProcess.machineType(aliases);
    assertEquals(MICROVM_MACHINE, aliasesType);

    final VmConfiguration repeated = VmConfiguration.builder()
      .repeatable(SHORT_MACHINE_OPTION, MICROVM_MACHINE)
      .repeatable(MACHINE_OPTION, Q35_MACHINE)
      .build();
    final String repeatedType = VmProcess.machineType(repeated);
    assertEquals(Q35_MACHINE, repeatedType);

    final VmConfiguration empty = VmConfiguration.builder().machine(",usb=on").build();
    final String emptyType = VmProcess.machineType(empty);
    assertEquals("", emptyType);

    for (final String lookalike : List.of(SHORT_MACHINE_ARGUMENT, MACHINE_ARGUMENT)) {
      final VmConfiguration valued = VmConfiguration.builder()
        .machine(Q35_MACHINE)
        .option(NAME_OPTION, lookalike)
        .cpu(HOST_CPU)
        .repeatable("device", lookalike)
        .repeatable("device", "e1000")
        .build();
      final String valuedType = VmProcess.machineType(valued);
      assertEquals(Q35_MACHINE, valuedType, valued::toString);
    }

    final boolean defaultIsPc = VmProcess.isPcMachine("");
    final boolean pcIsPc = VmProcess.isPcMachine("pc");
    final boolean q35IsPc = VmProcess.isPcMachine(Q35_MACHINE);
    final boolean versionedQ35IsPc = VmProcess.isPcMachine("pc-q35-9.0");
    final boolean microvmIsPc = VmProcess.isPcMachine(MICROVM_MACHINE);
    final boolean isapcIsPc = VmProcess.isPcMachine("isapc");
    assertTrue(defaultIsPc);
    assertTrue(pcIsPc);
    assertTrue(q35IsPc);
    assertTrue(versionedQ35IsPc);
    assertFalse(microvmIsPc);
    assertFalse(isapcIsPc);
  }

  @Test
  void shouldRefuseAConfigurationThatSetsTheDisplayOrTheSoundOfTheMachine() {
    for (final String option : List.of("vnc", "audio", "audiodev")) {
      final VmConfiguration configuration = VmConfiguration.builder().option(option, "x").build();
      final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () ->
        VmProcess.checkModuleOptions(configuration)
      );
      final String expected = "Option -%s is set by MCAV itself and must be removed from the configuration!".formatted(option);
      final String message = refused.getMessage();
      assertEquals(expected, message);
    }

    final VmConfiguration speaker = VmConfiguration.builder().machine(ROUTED_PC_MACHINE).build();
    final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> VmProcess.checkModuleOptions(speaker));
    final String message = refused.getMessage();
    assertEquals("PC speaker is routed by MCAV itself, so pcspk-audiodev must be removed from -machine!", message);

    final VmConfiguration fine = VmConfiguration.builder().machine(Q35_MACHINE).build();
    VmProcess.checkModuleOptions(fine);
    final VmSettings settings = this.reachableSettings();
    final VmConfiguration display = VmConfiguration.builder().option("vnc", ":7").build();
    final VmProcess process = this.process(settings, display, Os.LINUX);
    assertThrows(IllegalArgumentException.class, process::start);
    assertEquals(List.of(), this.commands, "a refused configuration never starts QEMU");
  }

  @Test
  void shouldAddNoDisplayWhenTheMachineRunsWithoutGraphics() {
    final VmSettings settings = new VmSettings(DISPLAY_ONE_PORT, WIDTH, HEIGHT, FRAME_RATE);
    final VmConfiguration configuration = VmConfiguration.builder().flag("nographic").build();
    final VmProcess process = this.process(settings, configuration, Os.LINUX);
    final List<String> command = process.buildCommand(SOFTWARE_ACCELERATOR, this.passwordFile());
    final String program = QEMU.toString();
    final List<String> expected = List.of(
      program,
      "-nographic",
      ACCELERATOR_ARGUMENT,
      SOFTWARE_ACCELERATOR,
      VGA_ARGUMENT,
      STANDARD_VGA,
      NETWORK_ARGUMENT,
      RESTRICTED_NETWORK,
      OBJECT_ARGUMENT,
      this.secretObject(),
      VNC_ARGUMENT,
      VNC_ON_DISPLAY_ONE,
      USB_ARGUMENT,
      DEVICE_ARGUMENT,
      TABLET_DEVICE
    );
    assertEquals(expected, command);
  }

  @Test
  void shouldPickTheAcceleratorOfEveryOperatingSystem() throws IOException {
    final Path writableKvm = this.directory.resolve(WRITABLE_KVM_DEVICE);
    Files.writeString(writableKvm, "");
    final String windows = VmProcess.detectAccelerator(Os.WINDOWS, this.missingKvm);
    final String mac = VmProcess.detectAccelerator(Os.MAC, this.missingKvm);
    final String freeBsd = VmProcess.detectAccelerator(Os.FREEBSD, this.missingKvm);
    final String linuxWithoutKvm = VmProcess.detectAccelerator(Os.LINUX, this.missingKvm);
    final String linuxWithKvm = VmProcess.detectAccelerator(Os.LINUX, writableKvm);
    assertEquals(WINDOWS_ACCELERATOR, windows);
    assertEquals(MAC_ACCELERATOR, mac);
    assertEquals(SOFTWARE_ACCELERATOR, freeBsd);
    assertEquals(SOFTWARE_ACCELERATOR, linuxWithoutKvm);
    assertEquals(KVM_ACCELERATOR, linuxWithKvm);
  }

  @Test
  void shouldBlameTheAcceleratorOnlyForMessagesThatMentionIt() {
    final boolean named = VmProcess.mentionsAccelerator("HVF: error", MAC_ACCELERATOR);
    final boolean generic = VmProcess.mentionsAccelerator("no ACCEL", MAC_ACCELERATOR);
    final boolean hypervisor = VmProcess.mentionsAccelerator("Hypervisor.framework missing", MAC_ACCELERATOR);
    final boolean virtualization = VmProcess.mentionsAccelerator("enable VIRTUALIZATION", MAC_ACCELERATOR);
    final boolean unrelated = VmProcess.mentionsAccelerator("disk not found", MAC_ACCELERATOR);
    final boolean missing = VmProcess.mentionsAccelerator(null, MAC_ACCELERATOR);
    assertTrue(named);
    assertTrue(generic);
    assertTrue(hypervisor);
    assertTrue(virtualization);
    assertFalse(unrelated);
    assertFalse(missing);
  }

  @Test
  void shouldFailWhenQemuCannotBeLaunched() {
    final IOException failure = new IOException("CreateProcess error=2!");
    final VmProcess process = this.reachable(Os.FREEBSD, failure);
    final VmException exception = assertThrows(VmException.class, process::start);
    final String message = exception.getMessage();
    final Throwable cause = exception.getCause();
    final boolean alive = process.isAlive();
    assertEquals("QEMU could not be started because of \"CreateProcess error=2!\"!", message);
    assertSame(failure, cause);
    assertFalse(alive);
  }

  @Test
  void shouldFailToStartAProcessCreatedForAMissingProgram() {
    final VmSettings settings = this.reachableSettings();
    final Path missing = this.directory.resolve(MISSING_QEMU);
    final VmConfiguration configuration = VmConfiguration.builder().build();
    final VmProcess process = VmProcess.create(settings, VmPlayer.Architecture.X86_64, missing, configuration);
    final VmException exception = assertThrows(VmException.class, process::start);
    final Throwable cause = exception.getCause();
    final boolean alive = process.isAlive();
    assertInstanceOf(IOException.class, cause);
    assertFalse(alive);
  }

  @Test
  void shouldReportTheLastFortyLinesOfOutput() {
    final StringBuilder output = new StringBuilder();
    for (int line = 1; line <= 45; line++) {
      final String number = String.format(Locale.ROOT, "%02d", line);
      output.append("line-");
      output.append(number);
      output.append("\r\n");
      output.append("   \n");
    }

    final String text = output.toString();
    final FakeProcess failed = FakeProcess.exited(2, text);
    final VmProcess process = this.reachable(Os.FREEBSD, failed);
    final VmException exception = assertThrows(VmException.class, process::start);
    final String message = exception.getMessage();
    final List<String> kept = new ArrayList<>();
    for (int line = 6; line <= 45; line++) {
      final String number = String.format(Locale.ROOT, "%02d", line);
      final String keptLine = "line-%s".formatted(number);
      kept.add(keptLine);
    }

    final String separator = System.lineSeparator();
    final String lines = String.join(separator, kept);
    final String expected = "QEMU exited with code 2, printing \"%s\"!".formatted(lines);
    assertEquals(expected, message);
  }

  @ParameterizedTest
  @ValueSource(strings = { "", LINE_BREAK })
  void shouldBoundLongDiagnosticLinesAndKeepTheirSuffix(final String ending) {
    final String discarded = "x".repeat(100_000);
    final String suffix = "z".repeat(4090) + "-tail!";
    final FakeProcess failed = FakeProcess.exited(FAILURE_EXIT_CODE, discarded + suffix + ending);
    final VmProcess process = this.reachable(Os.FREEBSD, failed);
    final VmException exception = assertThrows(VmException.class, process::start);
    final String message = exception.getMessage();
    final String expected = "QEMU exited with code 1, printing \"%s\"!".formatted(suffix);
    assertEquals(expected, message);
  }

  @Test
  void shouldKeepTheLastLineWithoutALineBreak() {
    final FakeProcess failed = FakeProcess.exited(FAILURE_EXIT_CODE, "first\nlast words");
    final VmProcess process = this.reachable(Os.FREEBSD, failed);
    final VmException exception = assertThrows(VmException.class, process::start);
    final String message = exception.getMessage();
    final String separator = System.lineSeparator();
    final String expected = "QEMU exited with code 1, printing \"first%slast words\"!".formatted(separator);
    assertEquals(expected, message);
  }

  @Test
  void shouldKeepTheOutputReadBeforeTheStreamFailed() {
    final InputStream broken = new FailingStream("first\nsecond");
    final FakeProcess failed = FakeProcess.running(broken);
    failed.exit(FAILURE_EXIT_CODE);
    final VmProcess process = this.reachable(Os.FREEBSD, failed);
    final VmException exception = assertThrows(VmException.class, process::start);
    final String message = exception.getMessage();
    final String separator = System.lineSeparator();
    final String expected = "QEMU exited with code 1, printing \"first%ssecond\"!".formatted(separator);
    assertEquals(expected, message);
  }

  @Test
  void shouldDecodeCharactersWhoseBytesArriveSeparately() {
    final InputStream trickle = new OneByteStream("Gerät nicht gefunden: 磁盘\n");
    final FakeProcess failed = FakeProcess.running(trickle);
    failed.exit(FAILURE_EXIT_CODE);
    final VmProcess process = this.reachable(Os.FREEBSD, failed);
    final VmException exception = assertThrows(VmException.class, process::start);
    final String message = exception.getMessage();
    assertEquals("QEMU exited with code 1, printing \"Gerät nicht gefunden: 磁盘\"!", message);
  }

  @Test
  void shouldGiveUpWhenTheDisplayNeverOpens() throws IOException {
    final FakeProcess qemu = FakeProcess.running("VNC server running on 127.0.0.1:5999\n");
    final VmSettings settings = this.unreachableSettings();
    final VmConfiguration configuration = VmConfiguration.builder().build();
    final VmProcess process = this.process(settings, configuration, Os.FREEBSD, qemu);
    final VmException exception = assertThrows(VmException.class, process::start);
    final String message = exception.getMessage();
    final int port = settings.getPort();
    final boolean alive = process.isAlive();
    final int destroyCalls = qemu.getDestroyCalls();
    final boolean qemuAlive = qemu.isAlive();
    final String expected =
      "QEMU display on port %s did not become reachable, and QEMU printed \"VNC server running on 127.0.0.1:5999\"!".formatted(port);
    assertEquals(expected, message);
    assertFalse(alive);
    assertEquals(1, destroyCalls);
    assertFalse(qemuAlive);
  }

  @Test
  void shouldStopWaitingForTheDisplayWhenInterrupted() throws IOException {
    final FakeProcess qemu = FakeProcess.running("");
    qemu.interruptingWaits();
    final VmSettings settings = this.unreachableSettings();
    final VmConfiguration configuration = VmConfiguration.builder().build();
    final VmProcess process = this.process(settings, configuration, Os.FREEBSD, qemu);
    final VmException exception;
    try {
      exception = assertThrows(VmException.class, process::start);
    } finally {
      final boolean interrupted = Thread.interrupted();
      assertTrue(interrupted, "the interrupt must be kept for the caller");
    }

    final String message = exception.getMessage();
    final Throwable cause = exception.getCause();
    final boolean alive = qemu.isAlive();
    final int forcibleCalls = qemu.getForcibleDestroyCalls();
    assertEquals("Waiting for QEMU was interrupted!", message);
    assertInstanceOf(InterruptedException.class, cause);
    assertFalse(alive);
    assertEquals(1, forcibleCalls);
  }

  @Test
  void shouldReportTheExitWhenInterruptedWhileReadingTheLastOutput() {
    final InputStream late = new DelayedStream("too late\n", 1_000L);
    final FakeProcess failed = FakeProcess.running(late);
    failed.exit(4);
    final VmProcess process = this.reachable(Os.FREEBSD, failed);
    final Thread current = Thread.currentThread();
    current.interrupt();
    final VmException exception;
    try {
      exception = assertThrows(VmException.class, process::start);
    } finally {
      final boolean interrupted = Thread.interrupted();
      assertTrue(interrupted, "the interrupt must be kept for the caller");
    }

    final String message = exception.getMessage();
    final boolean messageStartsWith = message.startsWith("QEMU exited with code 4, printing ");
    assertTrue(messageStartsWith, message);
  }

  @Test
  void shouldForceProcessesThatIgnoreTheShutdownRequest() {
    final FakeProcess qemu = FakeProcess.running("");
    qemu.ignoringDestroy();
    final VmProcess process = this.reachable(Os.FREEBSD, qemu);
    process.start();
    process.shutdown();
    final int destroyCalls = qemu.getDestroyCalls();
    final int forcibleCalls = qemu.getForcibleDestroyCalls();
    final boolean alive = qemu.isAlive();
    assertEquals(1, destroyCalls);
    assertEquals(1, forcibleCalls);
    assertFalse(alive);
  }

  @Test
  void shouldForceTheShutdownWhenInterrupted() {
    final FakeProcess qemu = FakeProcess.running("");
    final VmProcess process = this.reachable(Os.FREEBSD, qemu);
    process.start();
    qemu.interruptingWaits();
    process.shutdown();
    final boolean interrupted = Thread.interrupted();
    final int forcibleCalls = qemu.getForcibleDestroyCalls();
    assertTrue(interrupted);
    assertEquals(1, forcibleCalls);
  }

  @ParameterizedTest
  @ValueSource(booleans = { false, true })
  void shouldWaitForDelayedForcedTermination(final boolean interruptShutdown) {
    final FakeProcess qemu = FakeProcess.running("");
    qemu.ignoringDestroy();
    qemu.delayingForcedExit(50L);
    final VmProcess process = this.reachable(Os.FREEBSD, qemu);
    process.start();
    if (interruptShutdown) {
      qemu.interruptingFirstWait();
    }

    try {
      process.shutdown();
      final boolean alive = qemu.isAlive();
      final int forced = qemu.getForcibleDestroyCalls();
      assertFalse(alive, "destroyForcibly only requests termination; shutdown must wait for the child to exit");
      assertEquals(1, forced);
    } finally {
      final boolean interrupted = Thread.interrupted();
      assertEquals(interruptShutdown, interrupted, "cleanup preserves interruption for the caller");
    }
  }

  @Test
  void shouldBoundForcedWaitsAndPreserveInterruptsDuringForcedTermination() throws InterruptedException {
    final FakeProcess original = FakeProcess.running("");
    final FakeProcess qemu = Mockito.spy(original);
    qemu.ignoringDestroy();
    qemu.delayingForcedExit(100L);
    final VmProcess process = this.reachable(Os.FREEBSD, qemu);
    process.start();
    final AtomicBoolean interruptedOnce = new AtomicBoolean();
    Mockito.doAnswer(invocation -> this.waitWithinTheForcedDeadline(qemu, interruptedOnce, invocation))
      .when(qemu)
      .waitFor(ArgumentMatchers.anyLong(), ArgumentMatchers.any(TimeUnit.class));
    try {
      process.shutdown();
      final boolean alive = qemu.isAlive();
      final boolean caught = interruptedOnce.get();
      assertFalse(alive);
      assertTrue(caught);

      final boolean interrupted = Thread.interrupted();
      assertTrue(interrupted, "the forced-exit phase must preserve its own interruption");
    } finally {
      Thread.interrupted();
      qemu.exit(KILLED_EXIT_CODE);
    }
  }

  private Object waitWithinTheForcedDeadline(final FakeProcess qemu, final AtomicBoolean interruptedOnce, final InvocationOnMock invocation)
    throws Throwable {
    final int forced = qemu.getForcibleDestroyCalls();
    if (forced == 0) {
      return invocation.callRealMethod();
    }

    final long timeout = invocation.getArgument(0);
    final TimeUnit unit = invocation.getArgument(1);
    final long nanoseconds = unit.toNanos(timeout);
    final long maximum = TimeUnit.SECONDS.toNanos(10);
    final boolean withinTheDeadline = nanoseconds > 0 && nanoseconds <= maximum;
    assertTrue(withinTheDeadline, "every forced wait must stay within the total ten-second deadline");

    final boolean firstForcedWait = interruptedOnce.compareAndSet(false, true);
    if (firstForcedWait) {
      throw new InterruptedException("interrupt while forced exit is pending!");
    }

    return invocation.callRealMethod();
  }

  @Test
  void shouldRetainAProcessThatRemainsAliveAfterTheForcedDeadline() throws InterruptedException {
    final FakeProcess original = FakeProcess.running("");
    final FakeProcess qemu = Mockito.spy(original);
    qemu.ignoringDestroy();
    Mockito.doReturn(qemu).when(qemu).destroyForcibly();
    final VmProcess process = this.reachable(Os.FREEBSD, qemu);
    process.start();
    final Thread stopping = new Thread(process::shutdown, "stubborn-process-shutdown");
    stopping.setDaemon(true);
    try {
      stopping.start();
      stopping.join(12_000L);
      final boolean stillWaiting = stopping.isAlive();
      assertFalse(stillWaiting, "forced termination has a ten-second deadline even when the child refuses to exit");

      final boolean retained = process.isAlive();
      assertTrue(retained, "a live child remains owned after the deadline so shutdown can be retried");
    } finally {
      qemu.exit(KILLED_EXIT_CODE);
      stopping.interrupt();
      stopping.join(2_000L);
      process.shutdown();
    }

    final int attempts = qemu.getDestroyCalls();
    assertEquals(2, attempts, "the second cleanup still owns the process");
  }

  @Test
  void shouldBoundTheOutputJoinAndInterruptAnUnfinishedReader() throws InterruptedException {
    final CountDownLatch finish = new CountDownLatch(1);
    final BlockingStream output = new BlockingStream(finish);
    final FakeProcess qemu = FakeProcess.running(output);
    final VmProcess process = this.reachable(Os.FREEBSD, qemu);
    process.start();
    final Thread stopping = new Thread(process::shutdown, "bounded-output-shutdown");
    stopping.setDaemon(true);
    try {
      stopping.start();
      final boolean joined = this.awaitTimedWaiting(stopping);
      assertTrue(joined, "shutdown must block in a timed join instead of spinning on reader liveness");

      stopping.join(4_000L);
      final boolean alive = stopping.isAlive();
      assertFalse(alive, "an unfinished reader cannot extend the two-second output deadline indefinitely");

      final boolean interrupted = output.interruptedRead.await(1, TimeUnit.SECONDS);
      assertTrue(interrupted, "a reader that outlives the deadline receives an interrupt");
    } finally {
      finish.countDown();
      stopping.interrupt();
      stopping.join(SHUTDOWN_JOIN_MILLISECONDS);
      process.shutdown();
    }
  }

  private boolean awaitTimedWaiting(final Thread stopping) throws InterruptedException {
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMED_JOIN_DEADLINE_SECONDS);
    while (System.nanoTime() < deadline && stopping.isAlive()) {
      final Thread.State state = stopping.getState();
      if (state == Thread.State.TIMED_WAITING) {
        return true;
      }
      Thread.sleep(POLL_PAUSE_MILLISECONDS);
    }

    return false;
  }

  @Test
  void shouldPreserveAnInterruptReceivedDuringTheOutputJoinAndStillWaitForTheReader() throws InterruptedException {
    final CountDownLatch finish = new CountDownLatch(1);
    final BlockingStream output = new BlockingStream(finish);
    final FakeProcess qemu = FakeProcess.running(output);
    final VmProcess process = this.reachable(Os.FREEBSD, qemu);
    process.start();
    final AtomicBoolean restored = new AtomicBoolean();
    final AtomicReference<Throwable> failure = new AtomicReference<>();
    final CountDownLatch returned = new CountDownLatch(1);
    final Thread stopping = new Thread(() -> {
      try {
        process.shutdown();
        final Thread current = Thread.currentThread();
        restored.set(current.isInterrupted());
      } catch (final Throwable exception) {
        failure.set(exception);
      } finally {
        returned.countDown();
      }
    }, "interrupted-output-shutdown");
    stopping.setDaemon(true);
    try {
      stopping.start();
      this.awaitOutputJoin(stopping);
      stopping.interrupt();
      this.awaitOutputJoin(stopping);
      final long pendingReturns = returned.getCount();
      assertEquals(1, pendingReturns, "interruption must not abandon the live output reader");

      finish.countDown();
      stopping.join(SHUTDOWN_JOIN_MILLISECONDS);
      final boolean stoppingAlive = stopping.isAlive();
      final Throwable readerFailure = failure.get();
      final boolean interruptRestored = restored.get();
      final boolean qemuAlive = qemu.isAlive();
      assertFalse(stoppingAlive);
      assertNull(readerFailure);
      assertTrue(interruptRestored, "the caller receives its interrupt again after the reader finishes");
      assertFalse(qemuAlive);
    } finally {
      finish.countDown();
      stopping.interrupt();
      stopping.join(SHUTDOWN_JOIN_MILLISECONDS);
      process.shutdown();
    }
  }

  private void awaitOutputJoin(final Thread stopping) throws InterruptedException {
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMED_JOIN_DEADLINE_SECONDS);
    while (System.nanoTime() < deadline && stopping.isAlive()) {
      final boolean joining = this.isInOutputJoin(stopping);
      if (joining) {
        return;
      }
      Thread.sleep(POLL_PAUSE_MILLISECONDS);
    }

    fail("Shutdown must be waiting inside the timed join of awaitOutput!");
  }

  private boolean isInOutputJoin(final Thread stopping) {
    final Thread.State state = stopping.getState();
    if (state != Thread.State.TIMED_WAITING || stopping.isInterrupted()) {
      return false;
    }

    final StackTraceElement[] stack = stopping.getStackTrace();
    for (final StackTraceElement frame : stack) {
      final String owner = frame.getClassName();
      final String method = frame.getMethodName();
      if (owner.equals(VmProcess.class.getName()) && method.equals(OUTPUT_JOIN_METHOD)) {
        return true;
      }
    }

    return false;
  }

  @Test
  void shouldDrainFinalOutputBeforeReturningFromShutdown() {
    final DelayedStream output = new DelayedStream("final diagnostic\n", 500L);
    final FakeProcess qemu = FakeProcess.running(output);
    final VmProcess process = this.reachable(Os.FREEBSD, qemu);
    process.start();
    process.shutdown();
    final boolean alive = qemu.isAlive();
    final boolean outputClosed = output.closed;
    assertFalse(alive);
    assertTrue(outputClosed, "shutdown must wait for the output reader to consume and close the stream");
    assertEquals(output.content.length, output.position, "shutdown consumes the entire final diagnostic before closing it");
  }

  @Test
  void shouldShutDownOnlyOnce() {
    final FakeProcess qemu = FakeProcess.running("");
    final VmProcess process = this.reachable(Os.FREEBSD, qemu);
    process.shutdown();
    process.start();
    process.shutdown();
    process.shutdown();
    final int destroyCalls = qemu.getDestroyCalls();
    assertEquals(1, destroyCalls);
  }

  @Test
  void shouldFailFastWhenTheVncPortIsTaken() throws IOException {
    final ServerSocket other = this.listen(this.port);
    final FakeProcess qemu = FakeProcess.running("");
    final VmProcess process = this.reachable(Os.FREEBSD, qemu);
    final VmException exception = assertThrows(VmException.class, process::start);
    final String message = exception.getMessage();
    final int launches = this.commands.size();
    final boolean namesPort = message.startsWith("VNC port %s is already in use".formatted(this.port));
    final boolean otherOpen = !other.isClosed();
    assertTrue(namesPort, message);
    assertEquals(0, launches);
    assertTrue(otherOpen);
  }

  @Test
  void shouldFailWhenQemuExitsRightAfterItsDisplayOpens() {
    final FakeProcess qemu = FakeProcess.running("Failed to start VNC server: address in use\n");
    qemu.exitingWhenWaitedOn(FAILURE_EXIT_CODE);
    final VmProcess process = this.reachable(Os.FREEBSD, qemu);
    final VmException exception = assertThrows(VmException.class, process::start);
    final String message = exception.getMessage();
    final boolean alive = process.isAlive();
    assertEquals("QEMU exited with code 1, printing \"Failed to start VNC server: address in use\"!", message);
    assertFalse(alive);
  }

  @Test
  void shouldLeaveOutTheDefaultTabletWhenTheConfigurationAddsOne() {
    final VmSettings settings = new VmSettings(DISPLAY_ONE_PORT, WIDTH, HEIGHT, FRAME_RATE);
    final VmConfiguration plain = VmConfiguration.builder().device(TABLET_DEVICE).build();
    final VmConfiguration withBus = VmConfiguration.builder().device("usb-tablet,bus=usb-bus.0").build();
    final VmConfiguration otherDevice = VmConfiguration.builder().device("virtio-net-pci").build();

    final List<String> plainCommand = this.commandWithoutAccelerator(settings, plain);
    final List<String> withBusCommand = this.commandWithoutAccelerator(settings, withBus);
    final List<String> otherCommand = this.commandWithoutAccelerator(settings, otherDevice);

    final List<String> controllerOnly = List.of(USB_ARGUMENT);
    final List<String> controllerAndTablet = List.of(USB_ARGUMENT, DEVICE_ARGUMENT, TABLET_DEVICE);
    final List<String> plainOptions = List.of(DEVICE_ARGUMENT, TABLET_DEVICE);
    final List<String> withBusOptions = List.of(DEVICE_ARGUMENT, "usb-tablet,bus=usb-bus.0");
    final List<String> otherOptions = List.of(DEVICE_ARGUMENT, "virtio-net-pci");
    final List<String> expectedPlain = this.expectedOnDisplayOne(plainOptions, controllerOnly);
    final List<String> expectedWithBus = this.expectedOnDisplayOne(withBusOptions, controllerOnly);
    final List<String> expectedOther = this.expectedOnDisplayOne(otherOptions, controllerAndTablet);
    assertEquals(expectedPlain, plainCommand);
    assertEquals(expectedWithBus, withBusCommand);
    assertEquals(expectedOther, otherCommand);
  }

  @Test
  void shouldHonorUsbOffThroughEitherMachineAliasAndItsLastOverride() {
    final VmSettings settings = new VmSettings(DISPLAY_ONE_PORT, WIDTH, HEIGHT, FRAME_RATE);
    for (final String alias : List.of(SHORT_MACHINE_OPTION, MACHINE_OPTION)) {
      final String other = alias.equals(SHORT_MACHINE_OPTION) ? MACHINE_OPTION : SHORT_MACHINE_OPTION;
      final List<VmConfiguration> disabled = List.of(
        VmConfiguration.builder().option(alias, Q35_WITHOUT_USB).build(),
        VmConfiguration.builder().option(other, Q35_WITH_USB).option(alias, "usb=off").build(),
        VmConfiguration.builder().repeatable(alias, Q35_WITH_USB).repeatable(alias, "usb=off").build(),
        VmConfiguration.builder().option(alias, "q35,usb=on,usb=off").build()
      );
      for (final VmConfiguration configuration : disabled) {
        final List<String> command = this.commandWithoutAccelerator(settings, configuration);
        final boolean usbBus = command.contains(USB_ARGUMENT);
        final boolean usbTablet = command.contains(TABLET_DEVICE);
        assertFalse(usbBus, configuration::toString);
        assertFalse(usbTablet, configuration::toString);
      }
      final VmConfiguration enabled = VmConfiguration.builder().option(other, Q35_WITHOUT_USB).option(alias, USB_ON).build();
      final List<String> enabledCommand = this.commandWithoutAccelerator(settings, enabled);
      final boolean enabledUsbBus = enabledCommand.contains(USB_ARGUMENT);
      final boolean enabledUsbTablet = enabledCommand.contains(TABLET_DEVICE);
      assertFalse(enabledUsbBus, enabled::toString);
      assertTrue(enabledUsbTablet, enabled::toString);
    }
  }

  @Test
  void shouldLetTheConfigurationChooseOrTurnOffUsb() {
    final VmSettings settings = new VmSettings(DISPLAY_ONE_PORT, WIDTH, HEIGHT, FRAME_RATE);
    final VmConfiguration usbOn = VmConfiguration.builder().machine(Q35_WITH_USB).build();
    final VmConfiguration usbOff = VmConfiguration.builder().machine(Q35_WITHOUT_USB).build();
    final VmConfiguration usbFlag = VmConfiguration.builder().flag("usb").build();

    final List<String> onCommand = this.commandWithoutAccelerator(settings, usbOn);
    final List<String> offCommand = this.commandWithoutAccelerator(settings, usbOff);
    final List<String> flagCommand = this.commandWithoutAccelerator(settings, usbFlag);

    final List<String> tabletOnly = List.of(DEVICE_ARGUMENT, TABLET_DEVICE);
    final List<String> nothing = List.of();
    final List<String> onOptions = List.of(MACHINE_ARGUMENT, Q35_WITH_USB);
    final List<String> offOptions = List.of(MACHINE_ARGUMENT, Q35_WITHOUT_USB);
    final List<String> flagOptions = List.of(USB_ARGUMENT);
    final List<String> expectedOn = this.expectedOnDisplayOne(onOptions, tabletOnly);
    final List<String> expectedOff = this.expectedOnDisplayOne(offOptions, nothing);
    final List<String> expectedFlag = this.expectedOnDisplayOne(flagOptions, tabletOnly);
    assertEquals(expectedOn, onCommand);
    assertEquals(expectedOff, offCommand);
    assertEquals(expectedFlag, flagCommand);
  }

  @Test
  void shouldAddTheUsbControllerWhenTheMachineOptionLeavesUsbUnset() {
    final VmSettings settings = new VmSettings(DISPLAY_ONE_PORT, WIDTH, HEIGHT, FRAME_RATE);
    final VmConfiguration configuration = VmConfiguration.builder().machine(Q35_MACHINE).build();

    final List<String> command = this.commandWithoutAccelerator(settings, configuration);

    final List<String> machineOptions = List.of(MACHINE_ARGUMENT, Q35_MACHINE);
    final List<String> controllerAndTablet = List.of(USB_ARGUMENT, DEVICE_ARGUMENT, TABLET_DEVICE);
    final List<String> expected = this.expectedOnDisplayOne(machineOptions, controllerAndTablet);
    assertEquals(expected, command);
  }

  @Test
  void shouldNoticeWhenQemuExitsOnItsOwn() {
    final FakeProcess qemu = FakeProcess.running("");
    final VmProcess process = this.reachable(Os.FREEBSD, qemu);
    process.start();
    qemu.exit(0);
    final boolean alive = process.isAlive();
    assertFalse(alive);
  }

  @Test
  void shouldDrainTheOutputOnADaemonThreadSoItNeverKeepsTheJvmAlive() {
    final CountDownLatch finish = new CountDownLatch(1);
    final InputStream blocking = new BlockingStream(finish);
    final FakeProcess qemu = FakeProcess.running(blocking);
    final VmProcess process = this.reachable(Os.FREEBSD, qemu);
    try {
      process.start();
      final List<Thread> drains = this.awaitDrainThreads();
      for (final Thread thread : drains) {
        final boolean isDaemon = thread.isDaemon();
        final String name = thread.getName();
        assertTrue(isDaemon, name);
      }
    } finally {
      finish.countDown();
      process.shutdown();
    }
  }

  private List<Thread> awaitDrainThreads() {
    final long timeoutNanoseconds = TimeUnit.SECONDS.toNanos(10);
    final long pauseNanoseconds = TimeUnit.MILLISECONDS.toNanos(5);
    final long deadline = System.nanoTime() + timeoutNanoseconds;
    while (true) {
      final List<Thread> found = this.drainThreads();
      final boolean empty = found.isEmpty();
      if (!empty) {
        return found;
      }
      final long now = System.nanoTime();
      if (now - deadline > 0) {
        fail("Output of QEMU is never drained!");
      }
      LockSupport.parkNanos(pauseNanoseconds);
    }
  }

  private List<Thread> drainThreads() {
    final Map<Thread, StackTraceElement[]> stackTraces = Thread.getAllStackTraces();
    final Set<Thread> threads = stackTraces.keySet();
    final List<Thread> running = new ArrayList<>();
    for (final Thread thread : threads) {
      final String name = thread.getName();
      final boolean drain = name.equals(DRAIN_THREAD_NAME);
      final boolean alive = thread.isAlive();
      if (drain && alive) {
        running.add(thread);
      }
    }

    return running;
  }

  @Test
  void shouldKeepTheInterruptOfTheThreadThatWaitedForTheDisplay() throws IOException {
    final FakeProcess qemu = FakeProcess.running("");
    qemu.interruptingFirstWait();
    final VmSettings settings = this.unreachableSettings();
    final VmConfiguration configuration = VmConfiguration.builder().build();
    final VmProcess process = this.process(settings, configuration, Os.FREEBSD, qemu);
    final VmException exception;
    try {
      exception = assertThrows(VmException.class, process::start);
    } finally {
      final boolean interrupted = Thread.interrupted();
      assertTrue(interrupted, "the interrupt of the waiting thread must reach the caller");
    }

    final String message = exception.getMessage();
    assertEquals("Waiting for QEMU was interrupted!", message);
  }

  private static final class BlockingStream extends InputStream {

    private final CountDownLatch finish;
    private final CountDownLatch interruptedRead = new CountDownLatch(1);

    BlockingStream(final CountDownLatch finish) {
      this.finish = finish;
    }

    @Override
    public int read() {
      this.awaitFinish();
      return -1;
    }

    @Override
    public int read(final byte @NonNull [] buffer, final int offset, final int length) {
      this.awaitFinish();
      return -1;
    }

    private void awaitFinish() {
      try {
        this.finish.await();
      } catch (final InterruptedException exception) {
        this.interruptedRead.countDown();
        final Thread current = Thread.currentThread();
        current.interrupt();
      }
    }
  }

  private static final class DelayedStream extends InputStream {

    private final byte[] content;
    private final long delayMilliseconds;
    private int position;
    private volatile boolean closed;

    DelayedStream(final String text, final long delayMilliseconds) {
      this.content = text.getBytes(StandardCharsets.UTF_8);
      this.delayMilliseconds = delayMilliseconds;
    }

    @Override
    public void close() {
      this.closed = true;
    }

    @Override
    public int read() {
      final byte[] single = new byte[1];
      final int count = this.read(single, 0, 1);
      return count < 0 ? -1 : single[0] & BYTE_MASK;
    }

    @Override
    public int read(final byte @NonNull [] buffer, final int offset, final int length) {
      if (this.position == 0) {
        try {
          Thread.sleep(this.delayMilliseconds);
        } catch (final InterruptedException exception) {
          final Thread current = Thread.currentThread();
          current.interrupt();
        }
      }

      if (this.position >= this.content.length) {
        return -1;
      }

      final int count = Math.min(length, this.content.length - this.position);
      System.arraycopy(this.content, this.position, buffer, offset, count);
      this.position += count;
      return count;
    }
  }

  private static final class OneByteStream extends InputStream {

    private final byte[] content;
    private int position;

    OneByteStream(final String text) {
      this.content = text.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public int read() {
      if (this.position >= this.content.length) {
        return -1;
      }

      final int value = this.content[this.position] & BYTE_MASK;
      this.position++;
      return value;
    }

    @Override
    public int read(final byte @NonNull [] buffer, final int offset, final int length) {
      final int value = this.read();
      if (value < 0) {
        return -1;
      }

      buffer[offset] = (byte) value;
      return 1;
    }
  }

  private static final class FailingStream extends InputStream {

    private final byte[] content;
    private boolean delivered;

    FailingStream(final String text) {
      this.content = text.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public int read() throws IOException {
      throw new IOException("read single bytes is not supported!");
    }

    @Override
    public int read(final byte @NonNull [] buffer, final int offset, final int length) throws IOException {
      if (this.delivered) {
        throw new IOException("Stream closed!");
      }

      this.delivered = true;
      System.arraycopy(this.content, 0, buffer, offset, this.content.length);
      return this.content.length;
    }
  }
}
