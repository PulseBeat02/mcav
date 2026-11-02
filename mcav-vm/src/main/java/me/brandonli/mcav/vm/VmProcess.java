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
import com.google.common.util.concurrent.Uninterruptibles;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
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
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import me.brandonli.mcav.media.player.pipeline.filter.audio.AudioFilter;
import me.brandonli.mcav.utils.os.Os;
import me.brandonli.mcav.utils.os.OsUtils;
import org.checkerframework.checker.initialization.qual.UnderInitialization;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// A QEMU process with a VNC display on the local machine. The port must be free before QEMU starts, and QEMU must
// still run a moment after the port accepts connections, so the player never connects to another server on it.
final class VmProcess {

  static final int FIRST_VNC_PORT = 5900;

  private static final String AUDIO_ID = "mcav-audio";

  private static final String PASSWORD_ID = "mcav-vnc-password";

  private static final String SOUND_CARD_ID = "mcav-sound";

  // VNC's password authentication reads at most eight characters
  private static final int PASSWORD_LENGTH = 8;
  private static final String PASSWORD_CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
  private static final SecureRandom RANDOM = new SecureRandom();

  private static final String PASSWORD_FILE = "password";
  private static final String SECRET_FOLDER_PREFIX = "mcav-vm-";
  private static final Path TEMPORARY_FOLDER = Path.of(System.getProperty("java.io.tmpdir"));
  private static final String POSIX_ATTRIBUTE_VIEW = "posix";
  private static final FolderAccess SECRET_FOLDER_ACCESS;
  private static final FileAttribute<Set<PosixFilePermission>> OWNER_ONLY_PERMISSIONS = PosixFilePermissions.asFileAttribute(
    PosixFilePermissions.fromString("rwx------")
  );

  private static final Logger LOGGER = LoggerFactory.getLogger(VmProcess.class);

  static final String LOCALHOST = "127.0.0.1";
  static final InetAddress LOOPBACK = InetAddress.ofLiteral(LOCALHOST);

  private static final String ARGUMENT_SEPARATOR = " ";

  private static final String PROPERTY_SEPARATOR = ",";
  private static final String ESCAPED_PROPERTY_SEPARATOR = ",,";
  private static final String PROPERTY_ASSIGNMENT = "=";
  private static final Splitter PROPERTY_SPLITTER = Splitter.on(PROPERTY_SEPARATOR);

  private static final List<String> MODULE_OPTIONS = List.of("vnc", "audio", "audiodev");
  private static final List<String> ACCELERATOR_OPTIONS = List.of("accel", "enable-kvm");
  private static final List<String> DISPLAY_OPTIONS = List.of("display", "nographic");
  private static final List<String> NETWORK_OPTIONS = List.of("nic", "netdev", "net", "nodefaults");
  private static final Set<String> MACHINE_OPTIONS = Set.of("machine", "M");

  private static final String DEVICE_OPTION = "-device";
  private static final String VNC_OPTION = "-vnc";

  private static final String RESTRICTED_NETWORK = "user,restrict=on";

  private static final String SPEAKER_PROPERTY = "pcspk-audiodev";
  private static final String TYPE_PROPERTY = "type=";

  private static final String PC_MACHINE = "pc";
  private static final String Q35_MACHINE = "q35";
  private static final String PC_VERSION_PREFIX = "pc-";
  private static final String Q35_VERSION_PREFIX = "pc-q35";

  private static final String ICH9_SOUND_CARD = "ich9-intel-hda";
  private static final String SOUND_CARD = "intel-hda";

  private static final String TABLET_DEVICE = "usb-tablet";

  private static final String SOFTWARE_ACCELERATOR = "tcg";
  private static final String KVM_ACCELERATOR = "kvm";
  private static final String WHPX_ACCELERATOR = "whpx";
  private static final String HVF_ACCELERATOR = "hvf";
  private static final Path KVM_DEVICE = Path.of("/dev/kvm");

  private static final List<String> ACCELERATOR_WORDS = List.of("accel", "hypervisor", "virtualization");

  private static final long START_TIMEOUT_MILLISECONDS = 60_000L;
  private static final long POLL_MILLISECONDS = 100L;
  private static final int CONNECT_TIMEOUT_MILLISECONDS = (int) POLL_MILLISECONDS * 5;
  private static final long STOP_TIMEOUT_SECONDS = 10L;
  private static final long OUTPUT_TIMEOUT_MILLISECONDS = 2_000L;

  private static final String OUTPUT_THREAD_NAME = "mcav-qemu-output";
  private static final int READ_BUFFER_CHARACTERS = 4096;
  private static final char LINE_FEED = '\n';
  private static final char CARRIAGE_RETURN = '\r';
  private static final int OUTPUT_TAIL_LINES = 40;
  private static final int OUTPUT_LINE_CHARACTERS = 4096;

  static {
    final FileSystem fileSystem = FileSystems.getDefault();
    final Set<String> views = fileSystem.supportedFileAttributeViews();
    SECRET_FOLDER_ACCESS = chooseSecretFolderAccess(views);
  }

  private final VmSettings settings;
  private final VmPlayer.Architecture architecture;
  private final QemuProgram program;
  private final VmConfiguration configuration;
  private final Launcher launcher;
  private final Os os;
  private final Path kvmDevice;
  private final long startTimeoutNanoseconds;
  private final LongSupplier nanosecondClock;
  private final SecretFolder secretFolder;
  private final String password;
  private final Deque<String> outputTail;

  private volatile @Nullable Process process;
  private volatile @Nullable Thread drainThread;

  @VisibleForTesting
  VmProcess(
    final VmSettings settings,
    final VmPlayer.Architecture architecture,
    final QemuProgram program,
    final VmConfiguration configuration,
    final Launcher launcher,
    final Os os,
    final Path kvmDevice,
    final long startTimeoutMilliseconds,
    final SecretFolder secretFolder
  ) {
    this(settings, architecture, program, configuration, launcher, os, kvmDevice, startTimeoutMilliseconds, System::nanoTime, secretFolder);
  }

  @VisibleForTesting
  VmProcess(
    final VmSettings settings,
    final VmPlayer.Architecture architecture,
    final QemuProgram program,
    final VmConfiguration configuration,
    final Launcher launcher,
    final Os os,
    final Path kvmDevice,
    final long startTimeoutMilliseconds,
    final LongSupplier nanosecondClock,
    final SecretFolder secretFolder
  ) {
    this.settings = settings;
    this.architecture = architecture;
    this.program = program;
    this.configuration = configuration;
    this.launcher = launcher;
    this.os = os;
    this.kvmDevice = kvmDevice;
    this.startTimeoutNanoseconds = TimeUnit.MILLISECONDS.toNanos(startTimeoutMilliseconds);
    this.nanosecondClock = nanosecondClock;
    this.secretFolder = secretFolder;
    this.password = this.createPassword();
    this.outputTail = new ArrayDeque<>();
  }

  static VmProcess create(
    final VmSettings settings,
    final VmPlayer.Architecture architecture,
    final QemuProgram program,
    final VmConfiguration configuration
  ) {
    final Os currentOs = OsUtils.getOs();
    final QemuProcessRecords records = QemuProcessRecords.ofUser();
    final Map<String, String> environment = program.getEnvironment();
    final Launcher launcher = new RecordingLauncher(records, environment);
    return new VmProcess(settings, architecture, program, configuration, launcher, currentOs, KVM_DEVICE, START_TIMEOUT_MILLISECONDS, () ->
      createSecretFolder(TEMPORARY_FOLDER, SECRET_FOLDER_ACCESS)
    );
  }

  private String createPassword(@UnderInitialization VmProcess this) {
    final int characterCount = PASSWORD_CHARACTERS.length();
    final StringBuilder password = new StringBuilder(PASSWORD_LENGTH);
    for (int index = 0; index < PASSWORD_LENGTH; index++) {
      final int position = RANDOM.nextInt(characterCount);
      final char character = PASSWORD_CHARACTERS.charAt(position);
      password.append(character);
    }

    return password.toString();
  }

  @VisibleForTesting
  static FolderAccess chooseSecretFolderAccess(final Set<String> attributeViews) {
    final boolean posix = attributeViews.contains(POSIX_ATTRIBUTE_VIEW);
    return posix ? FolderAccess.OWNER_ONLY : FolderAccess.INHERITED;
  }

  // INHERITED is for Windows, which has no POSIX permissions and whose per-user temporary folder is private already
  @VisibleForTesting
  static Path createSecretFolder(final Path parent, final FolderAccess access) throws IOException {
    return switch (access) {
      case OWNER_ONLY -> Files.createTempDirectory(parent, SECRET_FOLDER_PREFIX, OWNER_ONLY_PERMISSIONS);
      case INHERITED -> Files.createTempDirectory(parent, SECRET_FOLDER_PREFIX);
    };
  }

  void start() {
    checkModuleOptions(this.configuration);
    this.ensurePortIsFree();

    final Path folder = this.writePassword();
    final Path passwordFile = folder.resolve(PASSWORD_FILE);
    try {
      this.launchMachine(passwordFile);
    } finally {
      this.deleteSecret(folder);
    }
  }

  private void launchMachine(final Path passwordFile) {
    final boolean acceleratorConfigured = ACCELERATOR_OPTIONS.stream().anyMatch(this.configuration::has);
    if (acceleratorConfigured) {
      this.launch(null, passwordFile);
      return;
    }

    final String accelerator = detectAccelerator(this.os, this.kvmDevice);
    final boolean software = accelerator.equals(SOFTWARE_ACCELERATOR);
    if (software) {
      this.launch(SOFTWARE_ACCELERATOR, passwordFile);
      return;
    }

    this.launchWithSoftwareFallback(accelerator, passwordFile);
  }

  private Path writePassword() {
    try {
      final Path folder = this.secretFolder.create();
      final Path file = folder.resolve(PASSWORD_FILE);
      // QEMU reads the whole file as the password, so it has no line break
      Files.writeString(file, this.password, StandardCharsets.US_ASCII, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);

      return folder;
    } catch (final IOException exception) {
      final String reason = exception.getMessage();
      final String message = "Password of the display could not be written because of \"%s\"!".formatted(reason);
      throw new VmException(message, exception);
    }
  }

  private void deleteSecret(final Path folder) {
    final Path file = folder.resolve(PASSWORD_FILE);
    try {
      Files.deleteIfExists(file);
      Files.deleteIfExists(folder);
    } catch (final IOException exception) {
      LOGGER.warn("The password folder {} of the virtual machine could not be deleted.", folder, exception);
    }
  }

  private void launchWithSoftwareFallback(final String accelerator, final Path passwordFile) {
    try {
      this.launch(accelerator, passwordFile);
    } catch (final VmException exception) {
      final String reason = exception.getMessage();
      final boolean acceleratorProblem = mentionsAccelerator(reason, accelerator);
      if (!acceleratorProblem) {
        throw exception;
      }

      LOGGER.warn("QEMU failed on the {} accelerator with \"{}\", retrying with software emulation.", accelerator, reason);
      this.launch(SOFTWARE_ACCELERATOR, passwordFile);
    }
  }

  @VisibleForTesting
  static void checkModuleOptions(final VmConfiguration configuration) {
    for (final String option : MODULE_OPTIONS) {
      if (configuration.has(option)) {
        final String message = "Option -%s is set by MCAV itself and must be removed from the configuration!".formatted(option);
        throw new IllegalArgumentException(message);
      }
    }

    for (final String machine : machineValues(configuration)) {
      if (machine.contains(SPEAKER_PROPERTY)) {
        final String message = "PC speaker is routed by MCAV itself, so pcspk-audiodev must be removed from -machine!";
        throw new IllegalArgumentException(message);
      }
    }
  }

  boolean hasAudio() {
    final String type = machineType(this.configuration);
    final boolean pcMachine = isPcMachine(type);
    return this.architecture == VmPlayer.Architecture.X86_64 && pcMachine;
  }

  // QEMU merges every -machine and -M option: a value names the type first or in type=, and the last type wins
  @VisibleForTesting
  static String machineType(final VmConfiguration configuration) {
    String type = "";
    for (final String machine : machineValues(configuration)) {
      type = mergeMachineType(type, machine);
    }

    return type.toLowerCase(Locale.ROOT);
  }

  private static String mergeMachineType(final String earlierType, final String machine) {
    String type = earlierType;
    boolean first = true;
    for (final String part : PROPERTY_SPLITTER.split(machine)) {
      if (part.startsWith(TYPE_PROPERTY)) {
        type = part.substring(TYPE_PROPERTY.length());
      } else if (first && !part.isEmpty() && !part.contains(PROPERTY_ASSIGNMENT)) {
        type = part;
      }
      first = false;
    }

    return type;
  }

  private static List<String> machineValues(final VmConfiguration configuration) {
    // in the order of the arguments QEMU gets: grouped by spelling, -M q35 -machine microvm would read as q35 where QEMU
    // runs microvm; and by option name, as a scan of every argument would read -name -M -cpu host as the machine -cpu
    return configuration.valuesOf(MACHINE_OPTIONS);
  }

  // an empty type is the default machine of QEMU, a PC
  @VisibleForTesting
  static boolean isPcMachine(final String type) {
    return type.isEmpty() || type.equals(PC_MACHINE) || type.equals(Q35_MACHINE) || type.startsWith(PC_VERSION_PREFIX);
  }

  private void ensurePortIsFree() {
    final int port = this.settings.getPort();
    final InetSocketAddress address = new InetSocketAddress(LOOPBACK, port);
    try (final ServerSocket probe = new ServerSocket()) {
      probe.setReuseAddress(false);
      probe.bind(address);
    } catch (final IOException exception) {
      final String reason = exception.getMessage();
      final String message = "VNC port %s is already in use because of \"%s\"!".formatted(port, reason);
      throw new VmException(message, exception);
    }
  }

  @VisibleForTesting
  static boolean mentionsAccelerator(final @Nullable String reason, final String accelerator) {
    final String message = Objects.requireNonNullElse(reason, "");
    final String lower = message.toLowerCase(Locale.ROOT);
    final boolean namesAccelerator = lower.contains(accelerator);
    final boolean blamesVirtualization = ACCELERATOR_WORDS.stream().anyMatch(lower::contains);
    return namesAccelerator || blamesVirtualization;
  }

  private void launch(final @Nullable String accelerator, final Path passwordFile) {
    final List<String> command = this.buildCommand(accelerator, passwordFile);
    final String rendered = String.join(ARGUMENT_SEPARATOR, command);
    LOGGER.debug("QEMU is started with the command line {}.", rendered);

    final Process started = this.startQemu(command);
    this.process = started;

    this.startDraining(started);
    this.waitForDisplay(started);
  }

  private Process startQemu(final List<String> command) {
    try {
      return this.launcher.launch(command);
    } catch (final IOException exception) {
      final String reason = exception.getMessage();
      final String message = "QEMU could not be started because of \"%s\"!".formatted(reason);
      throw new VmException(message, exception);
    }
  }

  private void startDraining(final Process started) {
    synchronized (this.outputTail) {
      this.outputTail.clear();
    }

    final InputStream output = started.getInputStream();
    final Thread drain = new Thread(() -> this.drain(output), OUTPUT_THREAD_NAME);
    drain.setDaemon(true);
    this.drainThread = drain;
    drain.start();
  }

  @VisibleForTesting
  List<String> buildCommand(final @Nullable String accelerator, final Path passwordFile) {
    final List<String> command = new ArrayList<>();
    final Path executable = this.program.getExecutable();
    final String path = executable.toString();
    command.add(path);
    final List<String> dataArguments = this.program.getArguments();
    command.addAll(dataArguments);
    final List<String> arguments = this.configuration.getArguments();
    command.addAll(arguments);

    final boolean audio = this.hasAudio();
    if (audio) {
      this.routeSpeaker(command);
    }

    if (accelerator != null) {
      command.add("-accel");
      command.add(accelerator);
    }

    this.addDefaultOptions(command, passwordFile);
    return command;
  }

  // QEMU merges this -machine option with those of the configuration
  private void routeSpeaker(final List<String> command) {
    command.add("-machine");
    command.add(SPEAKER_PROPERTY + PROPERTY_ASSIGNMENT + AUDIO_ID);
  }

  private void addDefaultOptions(final List<String> command, final Path passwordFile) {
    this.addUnlessConfigured(command, "vga", "-vga", "std");
    this.addNoHostWindow(command);
    this.addRestrictedNetwork(command);
    this.addPasswordSecret(command, passwordFile);
    this.addVncDisplay(command);
    this.addUsbTablet(command);
  }

  private void addNoHostWindow(final List<String> command) {
    final boolean hasDisplay = DISPLAY_OPTIONS.stream().anyMatch(this.configuration::has);
    if (!hasDisplay) {
      command.add("-display");
      command.add("none");
    }
  }

  private void addRestrictedNetwork(final List<String> command) {
    // QEMU's own default network lets the guest reach every service of the host's loopback address (10.0.2.2 inside
    // the guest), so a machine that chose no network gets one that reaches neither the host nor the internet
    final boolean hasNetwork = NETWORK_OPTIONS.stream().anyMatch(this.configuration::has);
    if (!hasNetwork) {
      command.add("-nic");
      command.add(RESTRICTED_NETWORK);
    }
  }

  private void addPasswordSecret(final List<String> command, final Path passwordFile) {
    // QEMU splits its option values at commas, so a comma of the path is written twice
    final String path = passwordFile.toString();
    final String secretFile = path.replace(PROPERTY_SEPARATOR, ESCAPED_PROPERTY_SEPARATOR);
    final String secret = "secret,id=%s,file=%s".formatted(PASSWORD_ID, secretFile);
    command.add("-object");
    command.add(secret);
  }

  private void addVncDisplay(final List<String> command) {
    final int port = this.settings.getPort();
    final int display = port - FIRST_VNC_PORT;
    final String vnc = "%s:%s,share=force-shared,password-secret=%s".formatted(LOCALHOST, display, PASSWORD_ID);
    final boolean audio = this.hasAudio();
    if (!audio) {
      command.add(VNC_OPTION);
      command.add(vnc);
      return;
    }

    this.addAudio(command);
    final String vncWithAudio = "%s,audiodev=%s".formatted(vnc, AUDIO_ID);
    command.add(VNC_OPTION);
    command.add(vncWithAudio);
  }

  private void addAudio(final List<String> command) {
    final String backend = "none,id=%s,out.frequency=%s".formatted(AUDIO_ID, AudioFilter.SAMPLE_RATE);
    command.add("-audiodev");
    command.add(backend);

    final String type = machineType(this.configuration);
    final boolean q35 = this.isQ35(type);
    final String card = q35 ? ICH9_SOUND_CARD : SOUND_CARD;
    final String soundCard = "%s,id=%s".formatted(card, SOUND_CARD_ID);
    command.add(DEVICE_OPTION);
    command.add(soundCard);

    final String output = "hda-output,bus=%s.0,audiodev=%s".formatted(SOUND_CARD_ID, AUDIO_ID);
    command.add(DEVICE_OPTION);
    command.add(output);
  }

  private boolean isQ35(final String type) {
    return type.equals(Q35_MACHINE) || type.startsWith(Q35_VERSION_PREFIX);
  }

  // -usbdevice brings its own controller and tablet, and usb=off means no USB at all
  private void addUsbTablet(final List<String> command) {
    final boolean legacyTablet = this.configuration.has("usbdevice");
    final Optional<String> usbProperty = this.findLastUsbProperty();
    final boolean controllerChosen = this.configuration.has("usb") || usbProperty.isPresent();
    final boolean usbOff = usbProperty.equals(Optional.of("usb=off"));
    final List<String> devices = this.configuration.getAll("device");
    final boolean tabletAdded = this.hasTablet(devices);

    if (!legacyTablet && !controllerChosen) {
      command.add("-usb");
    }

    if (!legacyTablet && !usbOff && !tabletAdded) {
      command.add(DEVICE_OPTION);
      command.add(TABLET_DEVICE);
    }
  }

  private Optional<String> findLastUsbProperty() {
    final List<String> machines = machineValues(this.configuration);
    return machines
      .stream()
      .flatMap(PROPERTY_SPLITTER::splitToStream)
      .filter(part -> part.startsWith("usb="))
      .reduce((earlier, later) -> later);
  }

  private boolean hasTablet(final List<String> devices) {
    for (final String device : devices) {
      final boolean tablet = device.equals(TABLET_DEVICE) || device.startsWith(TABLET_DEVICE + PROPERTY_SEPARATOR);
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

  @VisibleForTesting
  static String detectAccelerator(final Os os, final Path kvmDevice) {
    return switch (os) {
      case LINUX -> Files.isWritable(kvmDevice) ? KVM_ACCELERATOR : SOFTWARE_ACCELERATOR;
      case WINDOWS -> WHPX_ACCELERATOR;
      case MAC -> HVF_ACCELERATOR;
      case FREEBSD, OTHER -> SOFTWARE_ACCELERATOR;
    };
  }

  private void drain(final InputStream stream) {
    final StringBuilder line = new StringBuilder();
    try (final Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
      final char[] characters = new char[READ_BUFFER_CHARACTERS];
      int count;
      while ((count = reader.read(characters)) >= 0) {
        this.collectLines(characters, count, line);
      }
    } catch (final IOException exception) {
      // the process is gone, nothing more to read
    }

    final String last = line.toString();
    this.remember(last);
  }

  private void collectLines(final char[] characters, final int count, final StringBuilder line) {
    for (int index = 0; index < count; index++) {
      final char character = characters[index];
      if (character == LINE_FEED) {
        this.trimLine(line);
        final String complete = line.toString();
        this.remember(complete);
        line.setLength(0);
      } else if (character != CARRIAGE_RETURN) {
        line.append(character);
      }
    }

    this.trimLine(line);
  }

  private void trimLine(final StringBuilder line) {
    final int excess = line.length() - OUTPUT_LINE_CHARACTERS;
    if (excess > 0) {
      line.delete(0, excess);
    }
  }

  private void remember(final String line) {
    final boolean blank = line.isBlank();
    if (blank) {
      return;
    }

    LOGGER.debug("QEMU printed \"{}\".", line);
    synchronized (this.outputTail) {
      this.outputTail.addLast(line);
      while (this.outputTail.size() > OUTPUT_TAIL_LINES) {
        this.outputTail.removeFirst();
      }
    }
  }

  private void waitForDisplay(final Process started) {
    final int port = this.settings.getPort();
    final InetSocketAddress address = new InetSocketAddress(LOOPBACK, port);
    final long start = this.nanosecondClock.getAsLong();

    while (this.nanosecondClock.getAsLong() - start < this.startTimeoutNanoseconds) {
      this.checkAlive(started);
      final boolean reachable = this.isReachable(address);
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

    final String message = "QEMU display on port %s did not become reachable, and QEMU printed \"%s\"!".formatted(port, output);
    throw new VmException(message);
  }

  private void checkAlive(final Process started) {
    final boolean alive = started.isAlive();
    if (alive) {
      return;
    }

    final int exitCode = started.exitValue();
    this.awaitOutput();
    final String output = this.getOutputTail();
    this.process = null;

    final String message = "QEMU exited with code %s, printing \"%s\"!".formatted(exitCode, output);
    throw new VmException(message);
  }

  private void awaitOutput() {
    final Thread drain = Objects.requireNonNull(this.drainThread, "Output of QEMU must be drained while QEMU runs!");
    Uninterruptibles.joinUninterruptibly(drain, OUTPUT_TIMEOUT_MILLISECONDS, TimeUnit.MILLISECONDS);

    final boolean alive = drain.isAlive();
    if (alive) {
      drain.interrupt();
      LOGGER.warn("QEMU output reader did not stop within {} ms.", OUTPUT_TIMEOUT_MILLISECONDS);
    }
  }

  private void waitBeforeNextAttempt(final Process started) {
    try {
      started.waitFor(POLL_MILLISECONDS, TimeUnit.MILLISECONDS);
    } catch (final InterruptedException exception) {
      final Thread current = Thread.currentThread();
      current.interrupt();
      this.shutdown();

      final String message = "Waiting for QEMU was interrupted!";
      throw new VmException(message, exception);
    }
  }

  private boolean isReachable(final InetSocketAddress address) {
    try (final Socket socket = new Socket()) {
      socket.connect(address, CONNECT_TIMEOUT_MILLISECONDS);

      return true;
    } catch (final IOException exception) {
      return false;
    }
  }

  private String getOutputTail() {
    final String separator = System.lineSeparator();
    synchronized (this.outputTail) {
      return String.join(separator, this.outputTail);
    }
  }

  boolean isAlive() {
    final Process current = this.process;
    return current != null && current.isAlive();
  }

  void shutdown() {
    final Process current = this.process;
    if (current == null) {
      return;
    }

    current.destroy();

    final boolean exited = this.awaitGracefulExit(current);
    if (!exited) {
      current.destroyForcibly();
      this.awaitForcedExit(current);
    }

    this.awaitOutput();

    final boolean alive = current.isAlive();
    if (alive) {
      LOGGER.warn("QEMU is still alive after its forced termination.");
      return;
    }

    this.process = null;
    this.launcher.ended(current);
  }

  private boolean awaitGracefulExit(final Process current) {
    final Thread thread = Thread.currentThread();
    final boolean interrupted = thread.isInterrupted();
    if (interrupted) {
      return false;
    }

    try {
      return current.waitFor(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (final InterruptedException exception) {
      thread.interrupt();

      return false;
    }
  }

  private void awaitForcedExit(final Process current) {
    final long timeoutNanoseconds = TimeUnit.SECONDS.toNanos(STOP_TIMEOUT_SECONDS);
    final long deadline = System.nanoTime() + timeoutNanoseconds;
    boolean interrupted = Thread.interrupted();
    long remaining = deadline - System.nanoTime();
    while (current.isAlive() && remaining > 0) {
      try {
        current.waitFor(remaining, TimeUnit.NANOSECONDS);
      } catch (final InterruptedException exception) {
        interrupted = true;
      }
      remaining = deadline - System.nanoTime();
    }

    if (interrupted) {
      final Thread thread = Thread.currentThread();
      thread.interrupt();
    }
  }

  String getPassword() {
    return this.password;
  }

  @FunctionalInterface
  interface SecretFolder {
    Path create() throws IOException;
  }

  enum FolderAccess {
    OWNER_ONLY,
    INHERITED,
  }

  @VisibleForTesting
  static final class RecordingLauncher implements Launcher {

    private final QemuProcessRecords records;
    private final Map<String, String> environment;

    RecordingLauncher(final QemuProcessRecords records, final Map<String, String> environment) {
      this.records = records;
      this.environment = environment;
    }

    @Override
    public Process launch(final List<String> command) throws IOException {
      final Process started = this.startProcess(command);
      final ProcessHandle handle = started.toHandle();
      this.records.add(handle);

      return started;
    }

    private Process startProcess(final List<String> command) throws IOException {
      final ProcessBuilder builder = this.createBuilder(command);
      return builder.start();
    }

    @VisibleForTesting
    ProcessBuilder createBuilder(final List<String> command) {
      final ProcessBuilder builder = new ProcessBuilder(command);
      builder.redirectErrorStream(true);
      final Map<String, String> processEnvironment = builder.environment();
      processEnvironment.putAll(this.environment);
      return builder;
    }

    @Override
    public void ended(final Process process) {
      final long processId = process.pid();
      this.records.remove(processId);
    }
  }

  @FunctionalInterface
  interface Launcher {
    // the error output must be merged into the standard output, the only stream that is drained
    Process launch(List<String> command) throws IOException;

    default void ended(final Process process) {}
  }
}
