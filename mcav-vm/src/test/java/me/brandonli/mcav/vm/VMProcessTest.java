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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import me.brandonli.mcav.media.player.PlayerException;
import me.brandonli.mcav.utils.os.OS;
import me.brandonli.mcav.vm.testing.FakeProcess;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests {@link VMProcess} with {@link FakeProcess} instances in place of QEMU and a local socket in place of its
 * VNC display.
 */
final class VMProcessTest {

  private static final Path QEMU = Path.of("qemu-system-x86_64");
  private static final long SHORT_TIMEOUT_MILLIS = 400L;

  @TempDir
  private Path directory;

  private final List<List<String>> commands = new CopyOnWriteArrayList<>();
  private final Deque<Object> outcomes = new ArrayDeque<>();
  private final List<ServerSocket> sockets = new CopyOnWriteArrayList<>();
  private int port;
  private ServerSocket display;
  private Path missingKvm;

  @BeforeEach
  void choosePort() throws IOException {
    this.port = freePort();
    this.missingKvm = this.directory.resolve("kvm");
  }

  @AfterEach
  void closeSockets() throws IOException {
    for (final ServerSocket socket : this.sockets) {
      socket.close();
    }
  }

  private static int freePort() throws IOException {
    final InetAddress loopback = InetAddress.ofLiteral("127.0.0.1");
    try (final ServerSocket socket = new ServerSocket(0, 1, loopback)) {
      return socket.getLocalPort();
    }
  }

  private ServerSocket listen(final int listenPort) throws IOException {
    final InetAddress loopback = InetAddress.ofLiteral("127.0.0.1");
    final ServerSocket socket = new ServerSocket(listenPort, 50, loopback);
    this.sockets.add(socket);
    return socket;
  }

  // a running QEMU opens its VNC display, which a fake process does through the launcher
  private void openDisplay() throws IOException {
    if (this.display == null) {
      this.display = this.listen(this.port);
    }
  }

  private VMSettings reachableSettings() {
    return new VMSettings(this.port, 64, 48, 10);
  }

  private static VMSettings unreachableSettings() throws IOException {
    final int unusedPort = freePort();
    return new VMSettings(unusedPort, 64, 48, 10);
  }

  private VMProcess process(final VMSettings settings, final VMConfiguration configuration, final OS os, final Object... results) {
    for (final Object result : results) {
      this.outcomes.addLast(result);
    }
    final VMProcess.Launcher launcher = command -> {
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
    return new VMProcess(settings, QEMU, configuration, launcher, os, this.missingKvm, SHORT_TIMEOUT_MILLIS);
  }

  private VMProcess reachable(final OS os, final Object... results) {
    final VMSettings settings = this.reachableSettings();
    final VMConfiguration configuration = VMConfiguration.builder();
    return this.process(settings, configuration, os, results);
  }

  private List<String> defaults() {
    final int display = this.port - VMProcess.FIRST_VNC_PORT;
    return List.of("-vga", "std", "-display", "none", "-vnc", "127.0.0.1:" + display, "-usb", "-device", "usb-tablet");
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

  private List<String> commandWithoutAccelerator(final VMSettings settings, final VMConfiguration configuration) {
    final VMProcess process = this.process(settings, configuration, OS.LINUX);
    return process.buildCommand(null);
  }

  /**
   * Builds the command expected on VNC display 1: the program, the configured options, the display defaults, and
   * the USB options the player adds.
   */
  private static List<String> expectedOnDisplayOne(final List<String> options, final List<String> usbOptions) {
    final String program = QEMU.toString();
    final List<String> displayDefaults = List.of("-vga", "std", "-display", "none", "-vnc", "127.0.0.1:1");
    final List<String> expected = new ArrayList<>();
    expected.add(program);
    expected.addAll(options);
    expected.addAll(displayDefaults);
    expected.addAll(usbOptions);
    return expected;
  }

  @Test
  void startsWithTheAcceleratorOfTheMachineAndWaitsForTheDisplay() {
    final FakeProcess qemu = FakeProcess.running("");
    final VMSettings settings = this.reachableSettings();
    final VMConfiguration configuration = VMConfiguration.builder();
    configuration.memory(64);
    final VMProcess process = this.process(settings, configuration, OS.WINDOWS, qemu);
    final boolean aliveBefore = process.isAlive();
    process.start();
    final boolean alive = process.isAlive();
    final List<String> expected = this.command("-m", "64M", "-accel", "whpx");
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
  void retriesWithSoftwareEmulationWhenTheAcceleratorFails(final String failure) {
    final FakeProcess failed = FakeProcess.exited(1, failure + "\n");
    final FakeProcess working = FakeProcess.running("");
    final VMProcess process = this.reachable(OS.WINDOWS, failed, working);
    process.start();
    final boolean alive = process.isAlive();
    final List<String> first = this.command("-accel", "whpx");
    final List<String> second = this.command("-accel", "tcg");
    assertTrue(alive);
    final List<List<String>> expectedCommands = List.of(first, second);
    assertEquals(expectedCommands, this.commands);
  }

  @Test
  void forgetsTheOutputOfTheAttemptThatFailed() {
    final FakeProcess failed = FakeProcess.exited(1, "WHPX: No accelerator found\n");
    final FakeProcess failedAgain = FakeProcess.exited(2, "could not open disk image alpine.qcow2\n");
    final VMProcess process = this.reachable(OS.WINDOWS, failed, failedAgain);
    final PlayerException exception = assertThrows(PlayerException.class, process::start);
    final String message = exception.getMessage();
    // only the output of the attempt that failed last explains the failure
    assertEquals("QEMU exited with code 2: could not open disk image alpine.qcow2", message);
  }

  @Test
  void failsWithoutRetryingWhenTheFailureIsUnrelatedToTheAccelerator() {
    final FakeProcess failed = FakeProcess.exited(1, "could not open disk image alpine.qcow2\n");
    final VMProcess process = this.reachable(OS.MAC, failed);
    final PlayerException exception = assertThrows(PlayerException.class, process::start);
    final String message = exception.getMessage();
    final int launches = this.commands.size();
    final boolean alive = process.isAlive();
    assertEquals("QEMU exited with code 1: could not open disk image alpine.qcow2", message);
    assertEquals(1, launches);
    assertFalse(alive);
  }

  @Test
  void waitsForTheOutputThatExplainsTheExit() {
    // the output arrives after QEMU exited, as it does when the pipe is read more slowly than QEMU dies
    final InputStream late = new DelayedStream("WHPX: No accelerator found\n", 300L);
    final FakeProcess failed = FakeProcess.running(late);
    failed.exit(1);
    final FakeProcess working = FakeProcess.running("");
    final VMProcess process = this.reachable(OS.WINDOWS, failed, working);
    process.start();
    final int launches = this.commands.size();
    assertEquals(2, launches);
  }

  @Test
  void usesSoftwareEmulationDirectlyWhenTheMachineHasNoAccelerator() {
    final FakeProcess qemu = FakeProcess.running("");
    final VMProcess process = this.reachable(OS.FREEBSD, qemu);
    process.start();
    final List<String> expected = this.command("-accel", "tcg");
    final List<List<String>> expectedCommands = List.of(expected);
    assertEquals(expectedCommands, this.commands);
  }

  @Test
  void doesNotRetrySoftwareEmulation() {
    final FakeProcess failed = FakeProcess.exited(3, "tcg accel failed to start\n");
    final VMProcess process = this.reachable(OS.LINUX, failed);
    final PlayerException exception = assertThrows(PlayerException.class, process::start);
    final String message = exception.getMessage();
    final int launches = this.commands.size();
    assertEquals("QEMU exited with code 3: tcg accel failed to start", message);
    assertEquals(1, launches);
  }

  @Test
  void keepsTheAcceleratorOfTheConfiguration() {
    final FakeProcess failed = FakeProcess.exited(1, "kvm accel is not available\n");
    final VMSettings settings = this.reachableSettings();
    final VMConfiguration configuration = VMConfiguration.builder();
    configuration.accelerator("kvm");
    final VMProcess process = this.process(settings, configuration, OS.WINDOWS, failed);
    assertThrows(PlayerException.class, process::start);
    final List<String> expected = this.command("-accel", "kvm");
    final List<List<String>> expectedCommands = List.of(expected);
    assertEquals(expectedCommands, this.commands);
  }

  @Test
  void addsNoAcceleratorWhenKvmIsEnabledByAFlag() {
    final FakeProcess qemu = FakeProcess.running("");
    final VMSettings settings = this.reachableSettings();
    final VMConfiguration configuration = VMConfiguration.builder();
    configuration.flag("enable-kvm");
    final VMProcess process = this.process(settings, configuration, OS.MAC, qemu);
    process.start();
    final List<String> expected = this.command("-enable-kvm");
    final List<List<String>> expectedCommands = List.of(expected);
    assertEquals(expectedCommands, this.commands);
  }

  @Test
  void leavesOutTheDefaultsTheConfigurationSets() {
    final VMSettings settings = this.reachableSettings();
    final VMConfiguration configuration = VMConfiguration.builder();
    configuration.vga("virtio");
    configuration.option("display", "gtk");
    configuration.option("vnc", ":7");
    configuration.option("usbdevice", "tablet");
    final VMProcess process = this.process(settings, configuration, OS.LINUX);
    final List<String> command = process.buildCommand(null);
    final String program = QEMU.toString();
    final List<String> expected = List.of(program, "-vga", "virtio", "-display", "gtk", "-vnc", ":7", "-usbdevice", "tablet");
    assertEquals(expected, command);
  }

  @Test
  void addsNoDisplayWhenTheMachineRunsWithoutGraphics() {
    final VMSettings settings = new VMSettings(5901, 64, 48, 10);
    final VMConfiguration configuration = VMConfiguration.builder();
    configuration.flag("nographic");
    final VMProcess process = this.process(settings, configuration, OS.LINUX);
    final List<String> command = process.buildCommand("tcg");
    final String program = QEMU.toString();
    final List<String> expected = List.of(
      program,
      "-nographic",
      "-accel",
      "tcg",
      "-vga",
      "std",
      "-vnc",
      "127.0.0.1:1",
      "-usb",
      "-device",
      "usb-tablet"
    );
    assertEquals(expected, command);
  }

  @Test
  void picksTheAcceleratorOfEveryOperatingSystem() throws IOException {
    final Path writableKvm = this.directory.resolve("writable-kvm");
    Files.writeString(writableKvm, "");
    final String windows = VMProcess.detectAccelerator(OS.WINDOWS, this.missingKvm);
    final String mac = VMProcess.detectAccelerator(OS.MAC, this.missingKvm);
    final String freeBsd = VMProcess.detectAccelerator(OS.FREEBSD, this.missingKvm);
    final String linuxWithoutKvm = VMProcess.detectAccelerator(OS.LINUX, this.missingKvm);
    final String linuxWithKvm = VMProcess.detectAccelerator(OS.LINUX, writableKvm);
    assertEquals("whpx", windows);
    assertEquals("hvf", mac);
    assertEquals("tcg", freeBsd);
    assertEquals("tcg", linuxWithoutKvm);
    assertEquals("kvm", linuxWithKvm);
  }

  @Test
  void blamesTheAcceleratorOnlyForMessagesThatMentionIt() {
    final boolean named = VMProcess.mentionsAccelerator("HVF: error", "hvf");
    final boolean generic = VMProcess.mentionsAccelerator("no ACCEL", "hvf");
    final boolean hypervisor = VMProcess.mentionsAccelerator("Hypervisor.framework missing", "hvf");
    final boolean virtualization = VMProcess.mentionsAccelerator("enable VIRTUALIZATION", "hvf");
    final boolean unrelated = VMProcess.mentionsAccelerator("disk not found", "hvf");
    final boolean missing = VMProcess.mentionsAccelerator(null, "hvf");
    assertTrue(named);
    assertTrue(generic);
    assertTrue(hypervisor);
    assertTrue(virtualization);
    assertFalse(unrelated);
    assertFalse(missing);
  }

  @Test
  void failsWhenQemuCannotBeLaunched() {
    final IOException failure = new IOException("CreateProcess error=2");
    final VMProcess process = this.reachable(OS.FREEBSD, failure);
    final PlayerException exception = assertThrows(PlayerException.class, process::start);
    final String message = exception.getMessage();
    final Throwable cause = exception.getCause();
    final boolean alive = process.isAlive();
    assertEquals("Failed to start QEMU: CreateProcess error=2", message);
    assertSame(failure, cause);
    assertFalse(alive);
  }

  @Test
  void reportsTheLastFortyLinesOfOutput() {
    final StringBuilder output = new StringBuilder();
    for (int line = 1; line <= 45; line++) {
      final String number = String.format("%02d", line);
      output.append("line-");
      output.append(number);
      output.append("\r\n");
      output.append("   \n");
    }
    final String text = output.toString();
    final FakeProcess failed = FakeProcess.exited(2, text);
    final VMProcess process = this.reachable(OS.FREEBSD, failed);
    final PlayerException exception = assertThrows(PlayerException.class, process::start);
    final String message = exception.getMessage();
    final List<String> kept = new ArrayList<>();
    for (int line = 6; line <= 45; line++) {
      final String number = String.format("%02d", line);
      kept.add("line-" + number);
    }
    final String separator = System.lineSeparator();
    final String expected = "QEMU exited with code 2: " + String.join(separator, kept);
    assertEquals(expected, message);
  }

  @Test
  void keepsTheLastLineWithoutALineBreak() {
    final FakeProcess failed = FakeProcess.exited(1, "first\nlast words");
    final VMProcess process = this.reachable(OS.FREEBSD, failed);
    final PlayerException exception = assertThrows(PlayerException.class, process::start);
    final String message = exception.getMessage();
    final String separator = System.lineSeparator();
    assertEquals("QEMU exited with code 1: first" + separator + "last words", message);
  }

  @Test
  void keepsTheOutputReadBeforeTheStreamFailed() {
    final InputStream broken = new FailingStream("first\nsecond");
    final FakeProcess failed = FakeProcess.running(broken);
    failed.exit(1);
    final VMProcess process = this.reachable(OS.FREEBSD, failed);
    final PlayerException exception = assertThrows(PlayerException.class, process::start);
    final String message = exception.getMessage();
    final String separator = System.lineSeparator();
    assertEquals("QEMU exited with code 1: first" + separator + "second", message);
  }

  @Test
  void decodesCharactersWhoseBytesArriveSeparately() {
    final InputStream trickle = new OneByteStream("Gerät nicht gefunden: 磁盘\n");
    final FakeProcess failed = FakeProcess.running(trickle);
    failed.exit(1);
    final VMProcess process = this.reachable(OS.FREEBSD, failed);
    final PlayerException exception = assertThrows(PlayerException.class, process::start);
    final String message = exception.getMessage();
    assertEquals("QEMU exited with code 1: Gerät nicht gefunden: 磁盘", message);
  }

  @Test
  void givesUpWhenTheDisplayNeverOpens() throws IOException {
    final FakeProcess qemu = FakeProcess.running("VNC server running on 127.0.0.1:5999\n");
    final VMSettings settings = unreachableSettings();
    final VMConfiguration configuration = VMConfiguration.builder();
    final VMProcess process = this.process(settings, configuration, OS.FREEBSD, qemu);
    final PlayerException exception = assertThrows(PlayerException.class, process::start);
    final String message = exception.getMessage();
    final int port = settings.getPort();
    final boolean alive = process.isAlive();
    final int destroyCalls = qemu.getDestroyCalls();
    final boolean qemuAlive = qemu.isAlive();
    assertEquals("The QEMU display on port " + port + " did not become reachable: VNC server running on 127.0.0.1:5999", message);
    assertFalse(alive);
    assertEquals(1, destroyCalls);
    assertFalse(qemuAlive);
  }

  @Test
  void stopsWaitingForTheDisplayWhenInterrupted() throws IOException {
    final FakeProcess qemu = FakeProcess.running("");
    qemu.interruptingWaits();
    final VMSettings settings = unreachableSettings();
    final VMConfiguration configuration = VMConfiguration.builder();
    final VMProcess process = this.process(settings, configuration, OS.FREEBSD, qemu);
    final PlayerException exception;
    try {
      exception = assertThrows(PlayerException.class, process::start);
    } finally {
      final boolean interrupted = Thread.interrupted();
      assertTrue(interrupted, "the interrupt must be kept for the caller");
    }
    final String message = exception.getMessage();
    final Throwable cause = exception.getCause();
    final boolean alive = process.isAlive();
    final int forcibleCalls = qemu.getForcibleDestroyCalls();
    assertEquals("Interrupted while waiting for QEMU", message);
    assertInstanceOf(InterruptedException.class, cause);
    assertFalse(alive);
    assertEquals(1, forcibleCalls);
  }

  @Test
  void reportsTheExitWhenInterruptedWhileReadingTheLastOutput() {
    final InputStream late = new DelayedStream("too late\n", 1_000L);
    final FakeProcess failed = FakeProcess.running(late);
    failed.exit(4);
    final VMProcess process = this.reachable(OS.FREEBSD, failed);
    final Thread current = Thread.currentThread();
    current.interrupt();
    final PlayerException exception;
    try {
      exception = assertThrows(PlayerException.class, process::start);
    } finally {
      final boolean interrupted = Thread.interrupted();
      assertTrue(interrupted, "the interrupt must be kept for the caller");
    }
    final String message = exception.getMessage();
    final boolean messageStartsWith = message.startsWith("QEMU exited with code 4: ");
    assertTrue(messageStartsWith, message);
  }

  @Test
  void forcesProcessesThatIgnoreTheShutdownRequest() {
    final FakeProcess qemu = FakeProcess.running("");
    qemu.ignoringDestroy();
    final VMProcess process = this.reachable(OS.FREEBSD, qemu);
    process.start();
    process.shutdown();
    final int destroyCalls = qemu.getDestroyCalls();
    final int forcibleCalls = qemu.getForcibleDestroyCalls();
    final boolean alive = process.isAlive();
    assertEquals(1, destroyCalls);
    assertEquals(1, forcibleCalls);
    assertFalse(alive);
  }

  @Test
  void forcesTheShutdownWhenInterrupted() {
    final FakeProcess qemu = FakeProcess.running("");
    final VMProcess process = this.reachable(OS.FREEBSD, qemu);
    process.start();
    qemu.interruptingWaits();
    process.shutdown();
    final boolean interrupted = Thread.interrupted();
    final int forcibleCalls = qemu.getForcibleDestroyCalls();
    assertTrue(interrupted);
    assertEquals(1, forcibleCalls);
  }

  @Test
  void shutsDownOnlyOnce() {
    final FakeProcess qemu = FakeProcess.running("");
    final VMProcess process = this.reachable(OS.FREEBSD, qemu);
    process.shutdown();
    process.start();
    process.shutdown();
    process.shutdown();
    final int destroyCalls = qemu.getDestroyCalls();
    assertEquals(1, destroyCalls);
  }

  @Test
  void failsFastWhenTheVncPortIsTaken() throws IOException {
    final ServerSocket other = this.listen(this.port);
    final FakeProcess qemu = FakeProcess.running("");
    final VMProcess process = this.reachable(OS.FREEBSD, qemu);
    final PlayerException exception = assertThrows(PlayerException.class, process::start);
    final String message = exception.getMessage();
    final int launches = this.commands.size();
    final boolean namesPort = message.startsWith("The VNC port " + this.port + " is already in use");
    final boolean otherOpen = !other.isClosed();
    assertTrue(namesPort, message);
    assertEquals(0, launches);
    assertTrue(otherOpen);
  }

  @Test
  void failsWhenQemuExitsRightAfterItsDisplayOpens() {
    // another program answers on the port, and QEMU, which could not open its display there, exits
    final FakeProcess qemu = FakeProcess.running("Failed to start VNC server: address in use\n");
    qemu.exitingWhenWaitedOn(1);
    final VMProcess process = this.reachable(OS.FREEBSD, qemu);
    final PlayerException exception = assertThrows(PlayerException.class, process::start);
    final String message = exception.getMessage();
    final boolean alive = process.isAlive();
    assertEquals("QEMU exited with code 1: Failed to start VNC server: address in use", message);
    assertFalse(alive);
  }

  @Test
  void leavesOutTheDefaultTabletWhenTheConfigurationAddsOne() {
    final VMSettings settings = new VMSettings(5901, 64, 48, 10);
    final VMConfiguration plain = VMConfiguration.builder();
    plain.device("usb-tablet");
    final VMConfiguration withBus = VMConfiguration.builder();
    withBus.device("usb-tablet,bus=usb-bus.0");
    final VMConfiguration otherDevice = VMConfiguration.builder();
    otherDevice.device("virtio-net-pci");

    final List<String> plainCommand = this.commandWithoutAccelerator(settings, plain);
    final List<String> withBusCommand = this.commandWithoutAccelerator(settings, withBus);
    final List<String> otherCommand = this.commandWithoutAccelerator(settings, otherDevice);

    final List<String> controllerOnly = List.of("-usb");
    final List<String> controllerAndTablet = List.of("-usb", "-device", "usb-tablet");
    final List<String> plainOptions = List.of("-device", "usb-tablet");
    final List<String> withBusOptions = List.of("-device", "usb-tablet,bus=usb-bus.0");
    final List<String> otherOptions = List.of("-device", "virtio-net-pci");
    final List<String> expectedPlain = expectedOnDisplayOne(plainOptions, controllerOnly);
    final List<String> expectedWithBus = expectedOnDisplayOne(withBusOptions, controllerOnly);
    final List<String> expectedOther = expectedOnDisplayOne(otherOptions, controllerAndTablet);
    assertEquals(expectedPlain, plainCommand);
    assertEquals(expectedWithBus, withBusCommand);
    assertEquals(expectedOther, otherCommand);
  }

  @Test
  void letsTheConfigurationChooseOrTurnOffUsb() {
    final VMSettings settings = new VMSettings(5901, 64, 48, 10);
    final VMConfiguration usbOn = VMConfiguration.builder();
    usbOn.machine("q35,usb=on");
    final VMConfiguration usbOff = VMConfiguration.builder();
    usbOff.machine("q35,usb=off");
    final VMConfiguration usbFlag = VMConfiguration.builder();
    usbFlag.flag("usb");

    final List<String> onCommand = this.commandWithoutAccelerator(settings, usbOn);
    final List<String> offCommand = this.commandWithoutAccelerator(settings, usbOff);
    final List<String> flagCommand = this.commandWithoutAccelerator(settings, usbFlag);

    final List<String> tabletOnly = List.of("-device", "usb-tablet");
    final List<String> nothing = List.of();
    final List<String> onOptions = List.of("-machine", "q35,usb=on");
    final List<String> offOptions = List.of("-machine", "q35,usb=off");
    final List<String> flagOptions = List.of("-usb");
    final List<String> expectedOn = expectedOnDisplayOne(onOptions, tabletOnly);
    final List<String> expectedOff = expectedOnDisplayOne(offOptions, nothing);
    final List<String> expectedFlag = expectedOnDisplayOne(flagOptions, tabletOnly);
    assertEquals(expectedOn, onCommand);
    assertEquals(expectedOff, offCommand);
    assertEquals(expectedFlag, flagCommand);
  }

  @Test
  void noticesWhenQemuExitsOnItsOwn() {
    final FakeProcess qemu = FakeProcess.running("");
    final VMProcess process = this.reachable(OS.FREEBSD, qemu);
    process.start();
    qemu.exit(0);
    final boolean alive = process.isAlive();
    assertFalse(alive);
  }

  @Test
  void drainsTheOutputOnADaemonThreadSoItNeverKeepsTheJvmAlive() {
    final CountDownLatch finish = new CountDownLatch(1);
    final InputStream blocking = new BlockingStream(finish);
    final FakeProcess qemu = FakeProcess.running(blocking);
    final VMProcess process = this.reachable(OS.FREEBSD, qemu);
    try {
      process.start();
      final List<Thread> drains = awaitDrainThreads();
      for (final Thread thread : drains) {
        final boolean daemon = thread.isDaemon();
        final String name = thread.getName();
        assertTrue(daemon, name);
      }
    } finally {
      finish.countDown();
      process.shutdown();
    }
  }

  /**
   * Waits until the thread that reads the output of QEMU runs.
   *
   * @return the live drain threads
   */
  private static List<Thread> awaitDrainThreads() {
    final long timeoutNanos = TimeUnit.SECONDS.toNanos(10);
    final long pauseNanos = TimeUnit.MILLISECONDS.toNanos(5);
    final long deadline = System.nanoTime() + timeoutNanos;
    while (true) {
      final List<Thread> found = drainThreads();
      final boolean empty = found.isEmpty();
      if (!empty) {
        return found;
      }
      final long now = System.nanoTime();
      if (now - deadline > 0) {
        fail("The output of QEMU is never drained");
      }
      LockSupport.parkNanos(pauseNanos);
    }
  }

  private static List<Thread> drainThreads() {
    final Map<Thread, StackTraceElement[]> stackTraces = Thread.getAllStackTraces();
    final Set<Thread> threads = stackTraces.keySet();
    final List<Thread> running = new ArrayList<>();
    for (final Thread thread : threads) {
      final String name = thread.getName();
      final boolean drain = name.equals("mcav-qemu-output");
      final boolean alive = thread.isAlive();
      if (drain && alive) {
        running.add(thread);
      }
    }
    return running;
  }

  @Test
  void keepsTheInterruptOfTheThreadThatWaitedForTheDisplay() throws IOException {
    final FakeProcess qemu = FakeProcess.running("");
    qemu.interruptingFirstWait();
    final VMSettings settings = unreachableSettings();
    final VMConfiguration configuration = VMConfiguration.builder();
    final VMProcess process = this.process(settings, configuration, OS.FREEBSD, qemu);
    final PlayerException exception;
    try {
      exception = assertThrows(PlayerException.class, process::start);
    } finally {
      final boolean interrupted = Thread.interrupted();
      assertTrue(interrupted, "the interrupt of the waiting thread must reach the caller");
    }
    final String message = exception.getMessage();
    assertEquals("Interrupted while waiting for QEMU", message);
  }

  /**
   * A stream that blocks until it is released, and then reports the end of the output.
   */
  private static final class BlockingStream extends InputStream {

    private final CountDownLatch finish;

    BlockingStream(final CountDownLatch finish) {
      this.finish = finish;
    }

    @Override
    public int read() {
      this.awaitFinish();
      return -1;
    }

    @Override
    public int read(final byte@NonNull[] buffer, final int offset, final int length) {
      this.awaitFinish();
      return -1;
    }

    private void awaitFinish() {
      try {
        this.finish.await();
      } catch (final InterruptedException exception) {
        final Thread current = Thread.currentThread();
        current.interrupt();
      }
    }
  }

  /**
   * A stream that holds its content back for a while, then returns it.
   */
  private static final class DelayedStream extends InputStream {

    private final byte[] content;
    private final long delayMillis;
    private int position;

    DelayedStream(final String text, final long delayMillis) {
      this.content = text.getBytes(StandardCharsets.UTF_8);
      this.delayMillis = delayMillis;
    }

    @Override
    public int read() {
      final byte[] single = new byte[1];
      final int count = this.read(single, 0, 1);
      return count < 0 ? -1 : single[0] & 0xFF;
    }

    @Override
    public int read(final byte@NonNull[] buffer, final int offset, final int length) {
      if (this.position == 0) {
        try {
          Thread.sleep(this.delayMillis);
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

  /**
   * A stream that returns its content one byte per read.
   */
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
      final int value = this.content[this.position] & 0xFF;
      this.position++;
      return value;
    }

    @Override
    public int read(final byte@NonNull[] buffer, final int offset, final int length) {
      final int value = this.read();
      if (value < 0) {
        return -1;
      }
      buffer[offset] = (byte) value;
      return 1;
    }
  }

  /**
   * A stream that returns its content and then fails.
   */
  private static final class FailingStream extends InputStream {

    private final byte[] content;
    private boolean delivered;

    FailingStream(final String text) {
      this.content = text.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public int read() throws IOException {
      throw new IOException("read single bytes is not supported");
    }

    @Override
    public int read(final byte@NonNull[] buffer, final int offset, final int length) throws IOException {
      if (this.delivered) {
        throw new IOException("Stream closed");
      }
      this.delivered = true;
      System.arraycopy(this.content, 0, buffer, offset, this.content.length);
      return this.content.length;
    }
  }
}
