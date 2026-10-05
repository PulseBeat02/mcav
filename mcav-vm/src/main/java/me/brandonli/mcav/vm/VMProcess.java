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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Splitter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import me.brandonli.mcav.media.player.PlayerException;
import me.brandonli.mcav.media.player.pipeline.filter.audio.AudioFilter;
import me.brandonli.mcav.utils.os.OS;
import me.brandonli.mcav.utils.os.OSUtils;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A QEMU process with a VNC display on the local machine.
 *
 * <p>The process is started with the accelerator the machine supports; if QEMU exits right away, which is what
 * happens when the accelerator cannot be used, it is started again with software emulation. Output is drained
 * on a background thread and its tail is kept for error messages.
 *
 * <p>The display and the sound of the machine belong to mcav: a configuration that sets {@code -vnc},
 * {@code -audio} or {@code -audiodev}, or routes the PC speaker itself, is refused. The display is shared by every
 * client, and on x86 PC and Q35 machines an Intel HD Audio card and the PC speaker play into an audio backend without
 * a host device, whose sound QEMU's VNC server hands to the audio connection of the player
 * ({@link VMAudioClient}). Other machines have no sound.
 *
 * <p>The VNC port must be free before QEMU starts, and QEMU must still run a moment after the port accepts
 * connections, so the player never connects to another server that listens on the port. The display asks for a random
 * password, which QEMU reads from a file of a folder only the server's user can open; the file is deleted once QEMU
 * started, and the password reaches the players of the display through {@link #getPassword()}.
 */
final class VMProcess {

  static final int FIRST_VNC_PORT = 5900;

  /**
   * The id of the audio backend of the machine, which the sound cards and the VNC display name.
   */
  private static final String AUDIO_ID = "mcav-audio";

  /**
   * The id of the secret object that holds the password of the display.
   */
  private static final String PASSWORD_ID = "mcav-vnc-password";

  // VNC's password authentication reads at most eight characters
  private static final int PASSWORD_LENGTH = 8;
  private static final String PASSWORD_CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
  private static final String PASSWORD_FILE = "password";
  private static final String SECRET_FOLDER_PREFIX = "mcav-vm-";
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Path TEMPORARY_FOLDER = Path.of(System.getProperty("java.io.tmpdir"));
  private static final boolean POSIX = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
  private static final FileAttribute<Set<PosixFilePermission>> OWNER_ONLY = PosixFilePermissions.asFileAttribute(
    PosixFilePermissions.fromString("rwx------")
  );

  private static final Logger LOGGER = LoggerFactory.getLogger(VMProcess.class);
  private static final String ACCELERATOR_FAILED = "QEMU failed with the {} accelerator, retrying with software emulation: {}";
  private static final String STARTING = "Starting QEMU: {}";
  private static final String QEMU_OUTPUT = "qemu: {}";
  private static final String READER_NOT_STOPPED = "QEMU output reader did not stop within {} ms";
  private static final String STILL_ALIVE = "QEMU is still alive after forced termination";
  private static final String SECRET_NOT_DELETED = "The password file of the virtual machine could not be deleted: {}";
  private static final String LOCALHOST = "127.0.0.1";
  private static final Set<String> MACHINE_OPTIONS = Set.of("machine", "M");
  // the options with which a configuration chooses the network of the guest; -nodefaults leaves out QEMU's own
  private static final List<String> NETWORK_OPTIONS = List.of("nic", "netdev", "net", "nodefaults");
  private static final String RESTRICTED_NETWORK = "user,restrict=on";
  // QEMU listens on the IPv4 loopback address, which is parsed from the literal rather than looked up
  static final InetAddress LOOPBACK = InetAddress.ofLiteral(LOCALHOST);
  private static final long START_TIMEOUT_MILLIS = 60_000L;
  private static final long POLL_MILLIS = 100L;
  private static final long STOP_TIMEOUT_SECONDS = 10L;
  private static final long OUTPUT_TIMEOUT_MILLIS = 2_000L;
  private static final int OUTPUT_TAIL_LINES = 40;
  private static final int OUTPUT_LINE_CHARACTERS = 4096;
  private static final String SOFTWARE_ACCELERATOR = "tcg";
  private static final Path KVM_DEVICE = Path.of("/dev/kvm");

  private final VMSettings settings;
  private final VMPlayer.Architecture architecture;
  private final Path executable;
  private final VMConfiguration configuration;
  private final Launcher launcher;
  private final OS os;
  private final Path kvmDevice;
  private final long startTimeoutNanos;
  private final LongSupplier nanoClock;
  private final SecretFolder secretFolder;
  private final String password;
  private final Deque<String> outputTail;

  // written while starting and stopping, but read from other threads through isRunning()/liveness checks
  private volatile @Nullable Process process;
  private volatile @Nullable Thread drainThread;

  /**
   * Creates a QEMU process for the current operating system that has not started yet.
   *
   * @param settings      the display settings
   * @param architecture  the guest architecture, which decides whether the machine has sound
   * @param executable    the QEMU program
   * @param configuration the QEMU options
   * @return the process
   */
  static VMProcess create(
    final VMSettings settings,
    final VMPlayer.Architecture architecture,
    final Path executable,
    final VMConfiguration configuration
  ) {
    final OS currentOs = OSUtils.getOS();
    return new VMProcess(
      settings,
      architecture,
      executable,
      configuration,
      new RecordingLauncher(QemuProcessRecords.ofUser()),
      currentOs,
      KVM_DEVICE,
      START_TIMEOUT_MILLIS,
      () -> createSecretFolder(TEMPORARY_FOLDER, POSIX)
    );
  }

  /**
   * Constructs a process with its environment replaced, so tests can run without QEMU.
   *
   * @param settings           the display settings
   * @param architecture       the guest architecture
   * @param executable         the QEMU program
   * @param configuration      the QEMU options
   * @param launcher           starts the process from its command line
   * @param os                 the operating system, which decides the accelerator
   * @param kvmDevice          the KVM device, used on Linux
   * @param startTimeoutMillis how long the display may take to accept connections
   * @param secretFolder       creates the folder of the password file of a start
   */
  @VisibleForTesting
  VMProcess(
    final VMSettings settings,
    final VMPlayer.Architecture architecture,
    final Path executable,
    final VMConfiguration configuration,
    final Launcher launcher,
    final OS os,
    final Path kvmDevice,
    final long startTimeoutMillis,
    final SecretFolder secretFolder
  ) {
    this(settings, architecture, executable, configuration, launcher, os, kvmDevice, startTimeoutMillis, System::nanoTime, secretFolder);
  }

  /** Constructs a process with a monotonic clock for deterministic startup deadline checks. */
  @VisibleForTesting
  VMProcess(
    final VMSettings settings,
    final VMPlayer.Architecture architecture,
    final Path executable,
    final VMConfiguration configuration,
    final Launcher launcher,
    final OS os,
    final Path kvmDevice,
    final long startTimeoutMillis,
    final LongSupplier nanoClock,
    final SecretFolder secretFolder
  ) {
    this.settings = settings;
    this.architecture = architecture;
    this.executable = executable;
    this.configuration = configuration;
    this.launcher = launcher;
    this.os = os;
    this.kvmDevice = kvmDevice;
    this.startTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(startTimeoutMillis);
    this.nanoClock = nanoClock;
    this.secretFolder = secretFolder;
    this.password = createPassword();
    this.outputTail = new ArrayDeque<>();
  }

  private static String createPassword() {
    final StringBuilder password = new StringBuilder(PASSWORD_LENGTH);
    for (int index = 0; index < PASSWORD_LENGTH; index++) {
      final int character = RANDOM.nextInt(PASSWORD_CHARACTERS.length());
      password.append(PASSWORD_CHARACTERS.charAt(character));
    }
    return password.toString();
  }

  /**
   * Creates an empty folder that only the server's user can open. A file system without POSIX permissions is Windows,
   * whose temporary folder of a user is private to that user already.
   *
   * @param parent the folder to create it in
   * @param posix  whether the file system has POSIX permissions
   * @return the folder
   * @throws IOException if it cannot be created
   */
  @VisibleForTesting
  static Path createSecretFolder(final Path parent, final boolean posix) throws IOException {
    if (posix) {
      return Files.createTempDirectory(parent, SECRET_FOLDER_PREFIX, OWNER_ONLY);
    }
    return Files.createTempDirectory(parent, SECRET_FOLDER_PREFIX);
  }

  /**
   * Gets the password of the display, which a VNC client of the machine must send.
   *
   * @return the password, eight letters and digits
   */
  String getPassword() {
    return this.password;
  }

  private static Process startProcess(final List<String> command) throws IOException {
    final ProcessBuilder builder = new ProcessBuilder(command);
    builder.redirectErrorStream(true);
    return builder.start();
  }

  /**
   * Starts QEMU and waits until its VNC display accepts connections.
   *
   * @throws PlayerException if the VNC port is taken, or QEMU cannot be started, exits, or never opens its display
   */
  void start() {
    checkModuleOptions(this.configuration);
    this.ensurePortIsFree();
    final Path folder = this.writePassword();
    try {
      this.launchMachine(folder.resolve(PASSWORD_FILE));
    } finally {
      // QEMU has read the password once its display accepts connections, or it failed to start
      deleteSecret(folder);
    }
  }

  private void launchMachine(final Path passwordFile) {
    final boolean acceleratorConfigured = this.configuration.has("accel") || this.configuration.has("enable-kvm");
    if (acceleratorConfigured) {
      this.launch(null, passwordFile);
      return;
    }

    final String accelerator = detectAccelerator(this.os, this.kvmDevice);
    if (accelerator.equals(SOFTWARE_ACCELERATOR)) {
      this.launch(SOFTWARE_ACCELERATOR, passwordFile);
      return;
    }

    this.launchWithSoftwareFallback(accelerator, passwordFile);
  }

  /**
   * Writes the password of the display into a new private folder.
   *
   * @return the folder
   * @throws PlayerException if the folder or the file cannot be written
   */
  private Path writePassword() {
    try {
      final Path folder = this.secretFolder.create();
      final Path file = folder.resolve(PASSWORD_FILE);
      // QEMU reads the whole file as the password, so it has no line break
      Files.writeString(file, this.password, StandardCharsets.US_ASCII, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
      return folder;
    } catch (final IOException exception) {
      final String message = exception.getMessage();
      throw new PlayerException("Failed to write the password of the display: " + message, exception);
    }
  }

  private static void deleteSecret(final Path folder) {
    try {
      Files.deleteIfExists(folder.resolve(PASSWORD_FILE));
      Files.deleteIfExists(folder);
    } catch (final IOException exception) {
      LOGGER.warn(SECRET_NOT_DELETED, folder, exception);
    }
  }

  /**
   * Starts QEMU with a hardware accelerator, and again with software emulation if QEMU blames the accelerator.
   *
   * @param accelerator  the hardware accelerator to try first
   * @param passwordFile the file that holds the password of the display
   */
  private void launchWithSoftwareFallback(final String accelerator, final Path passwordFile) {
    try {
      this.launch(accelerator, passwordFile);
    } catch (final PlayerException exception) {
      final String reason = exception.getMessage();
      final boolean acceleratorProblem = mentionsAccelerator(reason, accelerator);
      if (!acceleratorProblem) {
        throw exception;
      }

      LOGGER.warn(ACCELERATOR_FAILED, accelerator, reason);
      this.launch(SOFTWARE_ACCELERATOR, passwordFile);
    }
  }

  /**
   * Refuses a configuration that sets the display or the sound of the machine, which mcav owns.
   *
   * @param configuration the QEMU options
   * @throws PlayerException if the configuration sets {@code -vnc}, {@code -audio}, {@code -audiodev}, or routes the
   *                         PC speaker
   */
  @VisibleForTesting
  static void checkModuleOptions(final VMConfiguration configuration) {
    for (final String option : List.of("vnc", "audio", "audiodev")) {
      if (configuration.has(option)) {
        throw new PlayerException("mcav sets -" + option + " of the machine itself; remove it from the configuration");
      }
    }
    for (final String machine : machineValues(configuration)) {
      if (machine.contains("pcspk-audiodev")) {
        throw new PlayerException("mcav routes the PC speaker of the machine itself; remove pcspk-audiodev from -machine");
      }
    }
  }

  /**
   * Checks whether the machine gets sound: x86 PC and Q35 machines do, others have no sound card mcav can add.
   *
   * @return true if the machine plays into the audio backend of mcav
   */
  boolean hasAudio() {
    return this.architecture == VMPlayer.Architecture.X86_64 && isPcMachine(machineType(this.configuration));
  }

  /**
   * Gets the machine type of a configuration as QEMU does: it merges every {@code -machine} and {@code -M} option, a
   * value names the type in its first part or in {@code type=}, and the type named last wins.
   *
   * @param configuration the QEMU options
   * @return the type in lower case, or empty for the default machine of QEMU
   */
  @VisibleForTesting
  static String machineType(final VMConfiguration configuration) {
    String type = "";
    for (final String machine : machineValues(configuration)) {
      boolean first = true;
      for (final String part : Splitter.on(',').split(machine)) {
        if (part.startsWith("type=")) {
          type = part.substring("type=".length());
        } else if (first && !part.isEmpty() && !part.contains("=")) {
          type = part;
        }
        first = false;
      }
    }
    return type.toLowerCase(Locale.ROOT);
  }

  /**
   * Gets the values of every {@code -machine} and {@code -M} option of a configuration, in the order QEMU reads them.
   *
   * @param configuration the QEMU options
   * @return the values
   */
  private static List<String> machineValues(final VMConfiguration configuration) {
    // in the order of the arguments QEMU gets: grouped by spelling, -M q35 -machine microvm read as q35 where QEMU runs
    // microvm; and by option name, as a scan of every argument read -name -M -cpu host as the machine -cpu
    return configuration.valuesOf(MACHINE_OPTIONS);
  }

  /**
   * Checks whether a machine type is an x86 PC or Q35 machine, which has a PC speaker and a PCI bus for a sound card.
   *
   * @param type the machine type in lower case, empty for the default machine, which is a PC
   * @return true for the default machine, {@code pc}, {@code q35} and their versions
   */
  @VisibleForTesting
  static boolean isPcMachine(final String type) {
    return type.isEmpty() || type.equals("pc") || type.equals("q35") || type.startsWith("pc-");
  }

  private static boolean isQ35(final String type) {
    return type.equals("q35") || type.startsWith("pc-q35");
  }

  private void ensurePortIsFree() {
    final int port = this.settings.getPort();
    final InetSocketAddress address = new InetSocketAddress(LOOPBACK, port);
    try (final ServerSocket probe = new ServerSocket()) {
      probe.setReuseAddress(false);
      probe.bind(address);
    } catch (final IOException exception) {
      final String reason = exception.getMessage();
      throw new PlayerException("The VNC port " + port + " is already in use: " + reason, exception);
    }
  }

  /**
   * Checks whether a failure message of QEMU blames the accelerator.
   *
   * @param reason      the failure message
   * @param accelerator the accelerator that was used
   * @return true if the message mentions the accelerator or hardware virtualization
   */
  @VisibleForTesting
  static boolean mentionsAccelerator(final @Nullable String reason, final String accelerator) {
    // the failures of launch always carry a message
    final String message = Objects.requireNonNullElse(reason, "");
    final String lower = message.toLowerCase(Locale.ROOT);
    return lower.contains(accelerator) || lower.contains("accel") || lower.contains("hypervisor") || lower.contains("virtualization");
  }

  private void launch(final @Nullable String accelerator, final Path passwordFile) {
    final List<String> command = this.buildCommand(accelerator, passwordFile);
    final String rendered = String.join(" ", command);
    LOGGER.info(STARTING, rendered);

    final Process started;
    try {
      started = this.launcher.launch(command);
    } catch (final IOException exception) {
      final String message = exception.getMessage();
      throw new PlayerException("Failed to start QEMU: " + message, exception);
    }
    this.process = started;

    this.startDraining(started);
    this.waitForDisplay(started);
  }

  /**
   * Starts the background thread that reads the output of a started QEMU process, forgetting the output of an
   * earlier attempt.
   *
   * @param started the started process
   */
  private void startDraining(final Process started) {
    synchronized (this.outputTail) {
      this.outputTail.clear();
    }

    final InputStream output = started.getInputStream();
    final Thread drain = new Thread(() -> this.drain(output), "mcav-qemu-output");
    drain.setDaemon(true);
    this.drainThread = drain;
    drain.start();
  }

  /**
   * Builds the command line of QEMU.
   *
   * @param accelerator  the accelerator to add, or null to add none
   * @param passwordFile the file QEMU reads the password of the display from
   * @return the program followed by its arguments
   */
  @VisibleForTesting
  List<String> buildCommand(final @Nullable String accelerator, final Path passwordFile) {
    final List<String> command = new ArrayList<>();
    final String program = this.executable.toString();
    command.add(program);
    final List<String> arguments = this.configuration.getArguments();
    command.addAll(arguments);
    if (this.hasAudio()) {
      routeSpeaker(command);
    }
    if (accelerator != null) {
      command.add("-accel");
      command.add(accelerator);
    }
    this.addDefaultOptions(command, passwordFile);
    return command;
  }

  /**
   * Routes the PC speaker into the audio backend of mcav with a {@code -machine} option of its own, which QEMU merges
   * with those of the configuration.
   *
   * @param command the command line so far, with the options of the configuration
   */
  private static void routeSpeaker(final List<String> command) {
    command.add("-machine");
    command.add("pcspk-audiodev=" + AUDIO_ID);
  }

  /**
   * Adds the options MCAV relies on: a standard VGA card, no window on the host and a network that reaches neither
   * the host nor the internet unless the configuration sets them itself, the sound of the machine, a VNC display on
   * the configured port that every client shares and that asks for the password, and a USB tablet. The tablet reports absolute coordinates, so VNC
   * pointer positions map straight onto the screen.
   */
  private void addDefaultOptions(final List<String> command, final Path passwordFile) {
    this.addUnlessConfigured(command, "vga", "-vga", "std");
    final boolean hasDisplay = this.configuration.has("display") || this.configuration.has("nographic");
    if (!hasDisplay) {
      command.add("-display");
      command.add("none");
    }
    // QEMU's own default network lets the guest reach every service of the host's loopback address (10.0.2.2 inside
    // the guest), so a machine that chose no network gets one that reaches neither the host nor the internet
    final boolean hasNetwork = NETWORK_OPTIONS.stream().anyMatch(this.configuration::has);
    if (!hasNetwork) {
      command.add("-nic");
      command.add(RESTRICTED_NETWORK);
    }
    final int port = this.settings.getPort();
    final int display = port - FIRST_VNC_PORT;
    // QEMU splits its option values at commas, so a comma of the path is written twice
    final String secretFile = passwordFile.toString().replace(",", ",,");
    command.add("-object");
    command.add("secret,id=" + PASSWORD_ID + ",file=" + secretFile);
    final String vnc = LOCALHOST + ":" + display + ",share=force-shared,password-secret=" + PASSWORD_ID;
    if (this.hasAudio()) {
      this.addAudio(command);
      command.add("-vnc");
      command.add(vnc + ",audiodev=" + AUDIO_ID);
    } else {
      command.add("-vnc");
      command.add(vnc);
    }
    this.addUsbTablet(command);
  }

  /**
   * Adds the audio backend, which plays into no host device at the rate of the audio pipeline, and an Intel HD Audio
   * card with an output, the ICH9 variant on Q35 machines.
   *
   * @param command the command line so far
   */
  private void addAudio(final List<String> command) {
    command.add("-audiodev");
    command.add("none,id=" + AUDIO_ID + ",out.frequency=" + AudioFilter.SAMPLE_RATE);
    final String card = isQ35(machineType(this.configuration)) ? "ich9-intel-hda" : "intel-hda";
    command.add("-device");
    command.add(card + ",id=mcav-sound");
    command.add("-device");
    command.add("hda-output,bus=mcav-sound.0,audiodev=" + AUDIO_ID);
  }

  /**
   * Adds the USB controller and the tablet. The older {@code -usbdevice} option brings both; {@code usb=} in the
   * machine type or the {@code usb} flag chooses the controller; {@code usb=off} means no USB, so no tablet either;
   * and a tablet the configuration adds itself replaces the default one.
   */
  private void addUsbTablet(final List<String> command) {
    final boolean legacyTablet = this.configuration.has("usbdevice");
    final String machineOrNull = this.configuration.get("machine");
    final String machine = Objects.requireNonNullElse(machineOrNull, "");
    final boolean controllerChosen = this.configuration.has("usb") || machine.contains("usb=");
    final boolean usbOff = machine.contains("usb=off");
    final List<String> devices = this.configuration.getAll("device");
    final boolean tabletAdded = hasTablet(devices);

    if (!legacyTablet && !controllerChosen) {
      command.add("-usb");
    }
    if (!legacyTablet && !usbOff && !tabletAdded) {
      command.add("-device");
      command.add("usb-tablet");
    }
  }

  private static boolean hasTablet(final List<String> devices) {
    for (final String device : devices) {
      final boolean tablet = device.equals("usb-tablet") || device.startsWith("usb-tablet,");
      if (tablet) {
        return true;
      }
    }
    return false;
  }

  private void addUnlessConfigured(final List<String> command, final String option, final String... defaults) {
    final boolean configured = this.configuration.has(option);
    if (!configured) {
      final List<String> defaultArguments = List.of(defaults);
      command.addAll(defaultArguments);
    }
  }

  /**
   * Picks the fastest accelerator of an operating system.
   *
   * @param os        the operating system
   * @param kvmDevice the KVM device, which must be writable to use KVM on Linux
   * @return the accelerator name
   */
  @VisibleForTesting
  static String detectAccelerator(final OS os, final Path kvmDevice) {
    return switch (os) {
      case LINUX -> Files.isWritable(kvmDevice) ? "kvm" : SOFTWARE_ACCELERATOR;
      case WINDOWS -> "whpx";
      case MAC -> "hvf";
      case FREEBSD, OTHER -> SOFTWARE_ACCELERATOR;
    };
  }

  private void drain(final InputStream stream) {
    final StringBuilder line = new StringBuilder();
    // a reader decodes characters whose bytes arrive in different reads
    try (final Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
      final char[] characters = new char[4096];
      int count;
      while ((count = reader.read(characters)) >= 0) {
        this.collectLines(characters, count, line);
      }
    } catch (final IOException exception) {
      // the process is gone, nothing more to read
    }

    // the last line may lack a line break, or be cut off when the stream fails
    final String last = line.toString();
    this.remember(last);
  }

  /**
   * Adds characters to the line being read, remembering every line a line break completes.
   *
   * @param characters the characters that were read
   * @param count      how many of the characters are valid
   * @param line       the incomplete line, which keeps what follows the last line break
   */
  private void collectLines(final char[] characters, final int count, final StringBuilder line) {
    for (int index = 0; index < count; index++) {
      final char character = characters[index];
      if (character == '\n') {
        trimLine(line);
        final String complete = line.toString();
        this.remember(complete);
        line.setLength(0);
      } else if (character != '\r') {
        line.append(character);
      }
    }
    trimLine(line);
  }

  /**
   * Retains only the end of a long diagnostic line, including when QEMU never prints a line break.
   * The reader supplies at most 4096 characters at once, so incomplete lines also have bounded storage.
   *
   * @param line the line being collected
   */
  private static void trimLine(final StringBuilder line) {
    final int excess = line.length() - OUTPUT_LINE_CHARACTERS;
    if (excess > 0) {
      line.delete(0, excess);
    }
  }

  private void remember(final String line) {
    if (line.isBlank()) {
      return;
    }
    LOGGER.debug(QEMU_OUTPUT, line);
    synchronized (this.outputTail) {
      this.outputTail.addLast(line);
      while (this.outputTail.size() > OUTPUT_TAIL_LINES) {
        this.outputTail.removeFirst();
      }
    }
  }

  private String getOutputTail() {
    final String separator = System.lineSeparator();
    synchronized (this.outputTail) {
      return String.join(separator, this.outputTail);
    }
  }

  private void waitForDisplay(final Process started) {
    final int port = this.settings.getPort();
    final InetSocketAddress address = new InetSocketAddress(LOOPBACK, port);
    final long start = this.nanoClock.getAsLong();

    while (this.nanoClock.getAsLong() - start < this.startTimeoutNanos) {
      this.checkAlive(started);
      final boolean reachable = isReachable(address);
      if (reachable) {
        // QEMU exits at once when it cannot open its display, so a moment later it must still run, or the
        // listener belongs to another program
        this.waitBeforeNextAttempt(started);
        this.checkAlive(started);
        return;
      }
      this.waitBeforeNextAttempt(started);
    }

    final String output = this.getOutputTail();
    this.shutdown();
    throw new PlayerException("The QEMU display on port " + port + " did not become reachable: " + output);
  }

  private void checkAlive(final Process started) {
    final boolean alive = started.isAlive();
    if (alive) {
      return;
    }

    final int exitCode = started.exitValue();
    // the reason QEMU exited is in its last output, which the drain thread may still be reading
    this.awaitOutput();
    final String output = this.getOutputTail();
    this.process = null;
    throw new PlayerException("QEMU exited with code " + exitCode + ": " + output);
  }

  private void awaitOutput() {
    final Thread drain = Objects.requireNonNull(this.drainThread, "The output is drained while QEMU runs");
    final long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(OUTPUT_TIMEOUT_MILLIS);
    final long deadline = System.nanoTime() + timeoutNanos;
    boolean interrupted = Thread.interrupted();
    try {
      while (drain.isAlive()) {
        final long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
          drain.interrupt();
          LOGGER.warn(READER_NOT_STOPPED, OUTPUT_TIMEOUT_MILLIS);
          return;
        }
        try {
          TimeUnit.NANOSECONDS.timedJoin(drain, remaining);
        } catch (final InterruptedException exception) {
          interrupted = true;
        }
      }
    } finally {
      if (interrupted) {
        final Thread current = Thread.currentThread();
        current.interrupt();
      }
    }
  }

  /**
   * Waits a moment before the display is tried again, returning early if QEMU exits in the meantime.
   */
  private void waitBeforeNextAttempt(final Process started) {
    try {
      started.waitFor(POLL_MILLIS, TimeUnit.MILLISECONDS);
    } catch (final InterruptedException exception) {
      final Thread current = Thread.currentThread();
      current.interrupt();
      this.shutdown();
      throw new PlayerException("Interrupted while waiting for QEMU", exception);
    }
  }

  private static boolean isReachable(final InetSocketAddress address) {
    try (final Socket socket = new Socket()) {
      socket.connect(address, (int) POLL_MILLIS * 5);
      return true;
    } catch (final IOException exception) {
      return false;
    }
  }

  /**
   * Checks whether QEMU is still running.
   *
   * @return true if the process is alive
   */
  boolean isAlive() {
    final Process current = this.process;
    return current != null && current.isAlive();
  }

  /**
   * Stops QEMU, forcibly if it does not exit in time, and waits for its final output.
   * Graceful and forced termination each have a bounded wait. Interrupts request forced termination immediately
   * and are restored after cleanup. A process that survives the forced wait remains visible to liveness checks.
   */
  void shutdown() {
    final Process current = this.process;
    if (current == null) {
      return;
    }

    boolean interrupted = Thread.interrupted();
    try {
      current.destroy();
      boolean exited = false;
      if (!interrupted) {
        try {
          exited = current.waitFor(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (final InterruptedException exception) {
          interrupted = true;
        }
      }
      if (!exited) {
        current.destroyForcibly();
        awaitForcedExit(current);
      }
      this.awaitOutput();
      final boolean alive = current.isAlive();
      if (!alive) {
        this.process = null;
        this.launcher.ended(current);
      } else {
        LOGGER.warn(STILL_ALIVE);
      }
    } finally {
      if (interrupted) {
        final Thread thread = Thread.currentThread();
        thread.interrupt();
      }
    }
  }

  /**
   * Waits for asynchronous forced termination without letting interrupts abandon the child process.
   *
   * @param current the process whose termination was requested
   */
  private static void awaitForcedExit(final Process current) {
    final long timeoutNanos = TimeUnit.SECONDS.toNanos(STOP_TIMEOUT_SECONDS);
    final long deadline = System.nanoTime() + timeoutNanos;
    boolean interrupted = Thread.interrupted();
    try {
      while (current.isAlive()) {
        final long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
          return;
        }
        try {
          current.waitFor(remaining, TimeUnit.NANOSECONDS);
        } catch (final InterruptedException exception) {
          interrupted = true;
        }
      }
    } finally {
      if (interrupted) {
        final Thread thread = Thread.currentThread();
        thread.interrupt();
      }
    }
  }

  /**
   * Creates the folder of the password file of one start.
   */
  @FunctionalInterface
  interface SecretFolder {
    /**
     * Creates an empty folder that only the server's user can open.
     *
     * @return the folder
     * @throws IOException if it cannot be created
     */
    Path create() throws IOException;
  }

  /**
   * Starts QEMU and records it, so that a later start of the module stops it if this JVM is killed before it could.
   */
  @VisibleForTesting
  static final class RecordingLauncher implements Launcher {

    private final QemuProcessRecords records;

    RecordingLauncher(final QemuProcessRecords records) {
      this.records = records;
    }

    @Override
    public Process launch(final List<String> command) throws IOException {
      final Process started = startProcess(command);
      final ProcessHandle handle = started.toHandle();
      this.records.add(handle);
      return started;
    }

    @Override
    public void ended(final Process process) {
      final long pid = process.pid();
      this.records.remove(pid);
    }
  }

  /**
   * Starts a process from its command line.
   */
  @FunctionalInterface
  interface Launcher {
    /**
     * Starts a process with its error output merged into its standard output.
     *
     * @param command the program followed by its arguments
     * @return the started process
     * @throws IOException if the process cannot be started
     */
    Process launch(List<String> command) throws IOException;

    /**
     * Learns that a process this launcher started has ended.
     *
     * @param process the process
     */
    default void ended(final Process process) {
      // a launcher that keeps nothing about its processes has nothing to forget
    }
  }
}
