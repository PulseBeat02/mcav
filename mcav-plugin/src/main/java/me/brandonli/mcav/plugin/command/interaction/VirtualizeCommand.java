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
package me.brandonli.mcav.plugin.command.interaction;

import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.sun.management.OperatingSystemMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import me.brandonli.mcav.media.player.attachable.AudioAttachableCallback;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.plugin.MCAVSandbox;
import me.brandonli.mcav.plugin.audio.AudioOutputs;
import me.brandonli.mcav.plugin.audio.AudioProvider;
import me.brandonli.mcav.plugin.command.SuggestionProvider;
import me.brandonli.mcav.plugin.data.PluginDataConfigurationMapper;
import me.brandonli.mcav.plugin.locale.Message;
import me.brandonli.mcav.plugin.utils.AudioArgument;
import me.brandonli.mcav.plugin.utils.CleanupUtils;
import me.brandonli.mcav.plugin.utils.DiskImages;
import me.brandonli.mcav.plugin.utils.DitheringArgument;
import me.brandonli.mcav.plugin.utils.MapCodec;
import me.brandonli.mcav.utils.immutable.Pair;
import me.brandonli.mcav.utils.interaction.MouseClick;
import me.brandonli.mcav.vm.ExecutableNotInPathException;
import me.brandonli.mcav.vm.VmConfiguration;
import me.brandonli.mcav.vm.VmConfigurationBuilder;
import me.brandonli.mcav.vm.VmPlayer;
import me.brandonli.mcav.vm.VmSettings;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.incendo.cloud.annotation.specifier.Greedy;
import org.incendo.cloud.annotation.specifier.Quoted;
import org.incendo.cloud.annotation.specifier.Range;
import org.incendo.cloud.annotations.Argument;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.CommandDescription;
import org.incendo.cloud.annotations.Permission;
import org.incendo.cloud.bukkit.data.MultiplePlayerSelector;

/**
 * {@code /mcav vm create|interact|release}: runs a QEMU virtual machine on a map screen.
 *
 * <p>Only the QEMU options of {@link #supportedOptions()} may be given to a machine, and a disk image must be a file
 * of the {@value DiskImages#FOLDER_NAME} folder of the plugin. QEMU can otherwise read and write any file of the
 * server, load a plugin library of its own, share a folder of the host with the guest and publish its display and its
 * monitor on the network, none of which belongs in a chat command.
 *
 * <p>This class keeps no state of its own; {@link AbstractInteractiveCommand} names the threads that call its methods.
 *
 * @since 1.0.0
 */
public final class VirtualizeCommand extends AbstractInteractiveCommand<VmPlayer> {

  private static final String VIRTUAL_MACHINE_NULL_MESSAGE = "Virtual machine must not be null!";
  private static final String SENDER_NULL_MESSAGE = "Sender must not be null!";

  private static final String INTERACT_PERMISSION = "mcav.command.vm.interact";

  private static final Set<String> REPEATABLE_OPTIONS = Set.of(QemuHardwareValues.DRIVE_OPTION);

  private static final Set<String> FLAG_OPTIONS = Set.of("snapshot", "no-reboot", "no-hpet", "no-fd-bootchk", "enable-kvm", "usb");

  private static final Set<String> HARDWARE_OPTIONS = QemuHardwareValues.OPTIONS;

  private static final Set<String> IMAGE_OPTIONS = Set.of("cdrom", "hda", "hdb", "hdc", "hdd", "fda", "fdb");

  private static final String FILE_KEY = "file=";

  // QEMU's own user-mode network, which vm.allow-network gives a guest
  private static final String QEMU_NETWORK = "user";

  // the least memory a machine may always have, whatever the memory of the server
  private static final long MINIMUM_MACHINE_MEMORY_BYTES = 512L << QemuHardwareValues.MEBIBYTE_SHIFT;
  private static final int MACHINE_MEMORY_DIVISOR = 2;

  private static final String VALUE_SEPARATOR = ",";
  private static final String ESCAPED_VALUE_SEPARATOR = ",,";

  private static final Splitter DRIVE_SPLITTER = Splitter.on(VALUE_SEPARATOR);

  private static final Pattern TRAILING_CODEC = Pattern.compile("(?:^|\\s)--codec\\s+(\\S+)\\s*$");

  private static final String OPTION_PREFIX = "-";

  private static final char UNIX_SEPARATOR = '/';
  private static final char WINDOWS_SEPARATOR = '\\';

  private static final String NAME_SEPARATOR = ", ";

  private static final char QUOTE = '"';

  /**
   * Creates the command.
   *
   * @param plugin the plugin
   * @throws NullPointerException if {@code plugin} is null
   * @since 1.0.0
   */
  public VirtualizeCommand(final MCAVSandbox plugin) {
    super(plugin);
  }

  /**
   * Clicks the display of the virtual machine with the left mouse button.
   *
   * @param current the running virtual machine
   * @param screenX the x coordinate on the display, in pixels
   * @param screenY the y coordinate on the display, in pixels
   * @throws NullPointerException if {@code current} is null
   * @since 1.0.0
   */
  @Override
  protected void forwardLeftClick(final VmPlayer current, final int screenX, final int screenY) {
    Preconditions.checkNotNull(current, VIRTUAL_MACHINE_NULL_MESSAGE);

    current.sendMouseEvent(MouseClick.LEFT, screenX, screenY);
  }

  /**
   * Clicks the display of the virtual machine with the right mouse button.
   *
   * @param current the running virtual machine
   * @param screenX the x coordinate on the display, in pixels
   * @param screenY the y coordinate on the display, in pixels
   * @throws NullPointerException if {@code current} is null
   * @since 1.0.0
   */
  @Override
  protected void forwardRightClick(final VmPlayer current, final int screenX, final int screenY) {
    Preconditions.checkNotNull(current, VIRTUAL_MACHINE_NULL_MESSAGE);

    current.sendMouseEvent(MouseClick.RIGHT, screenX, screenY);
  }

  /**
   * Types text on the keyboard of the virtual machine.
   *
   * @param current the running virtual machine
   * @param text    the text to type
   * @throws NullPointerException if an argument is null
   * @since 1.0.0
   */
  @Override
  protected void forwardTextInput(final VmPlayer current, final String text) {
    Preconditions.checkNotNull(current, VIRTUAL_MACHINE_NULL_MESSAGE);
    Preconditions.checkNotNull(text, "Text must not be null!");

    current.sendKeyEvent(text);
  }

  /**
   * Stops the QEMU process of a virtual machine.
   *
   * @param player the virtual machine to stop, which is no longer the running one
   * @throws NullPointerException if {@code player} is null
   * @since 1.0.0
   */
  @Override
  protected void releasePlayer(final VmPlayer player) {
    Preconditions.checkNotNull(player, VIRTUAL_MACHINE_NULL_MESSAGE);

    // QEMU takes up to ten seconds to stop, and is then killed; no frame or sound of it meanwhile
    final VideoAttachableCallback video = player.getVideoAttachableCallback();
    final AudioAttachableCallback audio = player.getAudioAttachableCallback();
    CleanupUtils.runAll(video::detach, audio::detach, () -> this.releaseSound(player), () -> this.releaseInTheBackground(player::release));
  }

  /**
   * Handles {@code /mcav vm interact}: switches chat input to the virtual machine on or off for the player who runs
   * it.
   *
   * <p>While it is on, the chat messages of the player are not sent to chat. Their text is typed into the virtual
   * machine instead, as if on its keyboard. Clicking the map screen does not need this mode: left and right clicks on
   * the screen reach the virtual machine as mouse clicks for every player with this permission. Running the command
   * again switches it off, and the player is told which state is now active.
   *
   * <p>Requires the permission {@code mcav.command.vm.interact}. Only players can run it, since the console has no chat to
   * forward.
   *
   * @param sender the player who switches their chat input
   * @throws NullPointerException if {@code sender} is null
   * @since 1.0.0
   */
  @Command("mcav vm interact")
  @Permission(INTERACT_PERMISSION)
  @CommandDescription("mcav.command.vm.interact.info")
  public void toggleInteraction(final Player sender) {
    Preconditions.checkNotNull(sender, SENDER_NULL_MESSAGE);

    final Component enabled = Message.INTERACT_ENABLE.build();
    final Component disabled = Message.INTERACT_DISABLE.build();
    this.toggleInteraction(sender, enabled, disabled);
  }

  /**
   * Handles {@code /mcav vm release}: stops the QEMU process of the running virtual machine, forcibly if it does not
   * exit within a few seconds, stops streaming to its maps, and clears the maps for every viewer. The guest is not
   * asked to shut down first, so unsaved work inside it is lost.
   *
   * <p>Requires the permission {@code mcav.command.vm.release}; players and the console can run it. The sender is told
   * "Virtual machine released!" once the virtual machine has stopped, also when no virtual machine was running. When
   * it fails to stop, the sender is told that it could not be released, and the console logs why.
   *
   * @param sender who ran the command
   * @throws NullPointerException if {@code sender} is null
   * @since 1.0.0
   */
  @Command("mcav vm release")
  @Permission("mcav.command.vm.release")
  @CommandDescription("mcav.command.vm.release.info")
  public void releaseVm(final CommandSender sender) {
    Preconditions.checkNotNull(sender, SENDER_NULL_MESSAGE);

    final Component released = Message.VM_RELEASE.build();
    final Component failed = Message.VM_RELEASE_ERROR.build();
    this.releaseResource(sender, released, failed);
  }

  /**
   * Handles {@code /mcav vm create <playerSelector> <vmResolution> <targetFramesPerSecond> <blockDimensions> <mapId>
   * <ditheringAlgorithm> <architecture> <audioType> <flags> [--codec dither|mcv2]}: boots a QEMU virtual machine and
   * streams its display onto a wall of maps, dithered or with MCV2, and its sound into the chosen audio output. Only
   * x86-64 PC and Q35 machines have sound; MCAV adds their sound card itself, and the flags cannot change it.
   *
   * <p>QEMU must be installed on the server, with the program for the chosen architecture on the {@code PATH}, except on
   * x86-64 and ARM64 Linux, where MCAV installs it when it is missing. Build the wall first with {@code /mcav screen},
   * using the same block dimensions and map id. Players can then left click or right click the screen to click inside
   * the guest, and type into it after enabling {@code /mcav vm interact}. Only one virtual machine runs at a time:
   * creating a new one powers off the running one.
   *
   * <p>Requires the permission {@code mcav.command.vm.create}; players and the console can run it. When QEMU is not
   * installed and MCAV cannot install it, the dimensions are invalid, or an option is not supported, the sender gets an
   * error message and nothing starts. Otherwise the sender is told "Loading virtual machine...", and later that it was
   * created, that the QEMU program for the architecture is not on the {@code PATH}, or that it failed to start, with
   * the details in the console.
   *
   * @param sender                who ran the command
   * @param playerSelector        the players who see the virtual machine on the maps, such as a player name or
   *                              {@code @a}
   * @param vmResolution          the resolution of the display of the guest as {@code <width>x<height>} in pixels,
   *                              such as {@code 1280x720}; use 128 times the block dimensions to fill the wall exactly
   * @param targetFramesPerSecond how many frames per second are captured from the display, from 1 to 240; higher
   *                              values feel smoother but cost more CPU and bandwidth
   * @param blockDimensions       the size of the wall as {@code <width>x<height>} in maps, such as {@code 5x5}
   * @param mapId                 the id of the top left map of the wall, as given to {@code /mcav screen}
   * @param ditheringAlgorithm    how colors are reduced to the map palette; {@code NEAREST_COLOR} keeps text and
   *                              desktops sharp and stable, {@code FILTER_LITE} looks best for pictures, see
   *                              {@link DitheringArgument}
   * @param architecture          the processor the guest is emulated with: {@code X86_64}, {@code ARM},
   *                              {@code AARCH64}, or {@code RISCV64}, each run by its own {@code qemu-system-*} program
   * @param audioType             where the sound of the guest plays, as for the video commands, see
   *                              {@link AudioArgument}; {@code NONE} keeps the machine silent
   * @param flags                 the QEMU options, the rest of the command line, such as
   *                              {@code -cdrom "alpine linux.iso" -m 2048M}; quote values with spaces, and options QEMU
   *                              accepts more than once, such as {@code -drive}, may be repeated. Only the options of
   *                              {@link #supportedOptions()} are accepted, and a disk image must be a file of the
   *                              {@value DiskImages#FOLDER_NAME} folder of the plugin, named without its folder.
   *                              A trailing {@code --codec dither} or {@code --codec mcv2} is not a QEMU option: it
   *                              chooses how the picture reaches the players, as the flag of the other map commands
   *                              does, and the configured default applies without it
   * @throws NullPointerException if an argument is null
   * @since 1.0.0
   */
  @Command(
    "mcav vm create <playerSelector> <vmResolution> <targetFramesPerSecond> <blockDimensions> <mapId> <ditheringAlgorithm> <architecture> <audioType> <flags>"
  )
  @Permission("mcav.command.vm.create")
  @CommandDescription("mcav.command.vm.create.info")
  public void createVm(
    final CommandSender sender,
    final MultiplePlayerSelector playerSelector,
    @Argument(suggestions = SuggestionProvider.RESOLUTIONS) @Quoted final String vmResolution,
    @Argument(suggestions = SuggestionProvider.TARGET_FRAMES_PER_SECOND) @Range(min = "1", max = "240") final int targetFramesPerSecond,
    @Argument(suggestions = SuggestionProvider.DIMENSIONS) @Quoted final String blockDimensions,
    @Argument(suggestions = SuggestionProvider.IDS) @Range(min = "0") final int mapId,
    final DitheringArgument ditheringAlgorithm,
    final VmPlayer.Architecture architecture,
    final AudioArgument audioType,
    @Greedy final String flags
  ) {
    Preconditions.checkNotNull(sender, SENDER_NULL_MESSAGE);
    Preconditions.checkNotNull(playerSelector, "Player selector must not be null!");
    Preconditions.checkNotNull(vmResolution, "Virtual machine resolution must not be null!");
    Preconditions.checkNotNull(blockDimensions, "Block dimensions must not be null!");
    Preconditions.checkNotNull(ditheringAlgorithm, "Dithering algorithm must not be null!");
    Preconditions.checkNotNull(architecture, "Architecture must not be null!");
    Preconditions.checkNotNull(audioType, "Audio type must not be null!");
    Preconditions.checkNotNull(flags, "Flags must not be null!");

    final boolean qemuAvailable = this.plugin.isQemuAvailable();
    if (!qemuAvailable) {
      final Component message = Message.QEMU_NOT_INSTALLED.build();
      sender.sendMessage(message);
      return;
    }

    final Optional<Pair<Integer, Integer>> parsedResolution = parseDimensions(sender, vmResolution);
    final @Nullable Pair<Integer, Integer> resolution = parsedResolution.orElse(null);
    if (resolution == null) {
      return;
    }

    final Optional<Pair<Integer, Integer>> parsedBlocks = parseScreenDimensions(sender, blockDimensions);
    final @Nullable Pair<Integer, Integer> blocks = parsedBlocks.orElse(null);
    if (blocks == null) {
      return;
    }

    final @Nullable CodecSplit split = this.splitCodec(flags);
    if (split == null) {
      final Component message = Message.UNKNOWN_CODEC.build();
      sender.sendMessage(message);
      return;
    }

    final VmConfiguration vmConfiguration;
    try {
      final String options = split.options();
      vmConfiguration = this.buildConfiguration(options);
    } catch (final IllegalArgumentException exception) {
      final String detail = exception.getMessage();
      final String reason = Objects.requireNonNullElse(detail, "The options are not valid");
      final Component message = Message.UNSUPPORTED_VM_FLAGS.build(reason);
      sender.sendMessage(message);
      return;
    }

    final @Nullable Component audioProblem = this.findAudioProblem(architecture, audioType);
    if (audioProblem != null) {
      sender.sendMessage(audioProblem);
      return;
    }

    final VmSettings vmSettings = this.createVmSettings(resolution, targetFramesPerSecond);
    final @Nullable MapCodec requestedCodec = split.codec();
    final MapCodec codec = this.chooseCodec(requestedCodec);
    final ScreenSettings settings = new ScreenSettings(sender, playerSelector, blocks, resolution, mapId, ditheringAlgorithm, codec);
    final Screen screen = this.createScreen(settings);
    final Collection<Player> selectedPlayers = playerSelector.values();
    final Player[] viewers = selectedPlayers.toArray(Player[]::new);
    final ScreenSound sound = new ScreenSound(audioType, viewers);
    this.createResource(() -> this.startMachine(sender, screen, vmSettings, architecture, vmConfiguration, sound));
  }

  private @Nullable Component findAudioProblem(final VmPlayer.Architecture architecture, final AudioArgument audioType) {
    // only x86-64 PC and Q35 machines get a sound card from mcav-vm
    if (audioType != AudioArgument.NONE && architecture != VmPlayer.Architecture.X86_64) {
      return Message.VM_NO_SOUND.build();
    }

    final AudioProvider provider = this.plugin.getAudioProvider();
    final Optional<Component> foundAudioProblem = AudioOutputs.findProblem(provider, audioType);
    return foundAudioProblem.orElse(null);
  }

  private VmSettings createVmSettings(final Pair<Integer, Integer> resolution, final int targetFramesPerSecond) {
    final int width = resolution.getFirst();
    final int height = resolution.getSecond();
    return VmSettings.of(width, height, targetFramesPerSecond);
  }

  private void startMachine(
    final CommandSender sender,
    final Screen screen,
    final VmSettings settings,
    final VmPlayer.Architecture architecture,
    final VmConfiguration vmConfiguration,
    final ScreenSound sound
  ) {
    final VmPlayer machine = VmPlayer.create();
    this.ownCreatedPlayer(machine);

    final VideoAttachableCallback callback = machine.getVideoAttachableCallback();
    final VideoPipelineStep pipeline = screen.getPipeline();
    callback.attach(pipeline);

    final AudioAttachableCallback audio = machine.getAudioAttachableCallback();
    this.attachSound(machine, audio, sound, "Virtual machine");

    final Component loading = Message.VM_LOADING.build();
    sender.sendMessage(loading);

    final ExecutorService executor = this.startExecutor(machine, screen);
    final CompletableFuture<Boolean> start = machine.startAsync(settings, architecture, vmConfiguration, executor);
    this.reportStartWhenDone(sender, machine, screen, start, "the virtual machine");
    this.sendSoundLinkWhenStarted(start, screen, sound);
  }

  /**
   * Creates the message that tells the sender whether the virtual machine started. A QEMU program missing from the
   * {@code PATH} gets its own message, also when wrapped in a {@link CompletionException}; other reasons are
   * logged, not shown.
   *
   * @param outcome whether the virtual machine started
   * @param failure why the virtual machine failed to start, if known
   * @return the message
   * @throws NullPointerException if {@code outcome} is null
   * @since 1.0.0
   */
  @Override
  protected Component createStartMessage(final StartOutcome outcome, final @Nullable Throwable failure) {
    Preconditions.checkNotNull(outcome, "Outcome must not be null!");

    if (outcome == StartOutcome.STARTED) {
      return Message.VM_CREATE.build();
    }

    Throwable cause = failure;
    if (cause instanceof CompletionException) {
      cause = cause.getCause();
    }

    if (cause instanceof ExecutableNotInPathException) {
      return Message.VM_PATH.build();
    }

    return Message.VM_ERROR.build();
  }

  // Cloud flags cannot follow the QEMU options, which start with a dash too
  @Nullable CodecSplit splitCodec(final String flags) {
    final Matcher matcher = TRAILING_CODEC.matcher(flags);
    final boolean found = matcher.find();
    if (!found) {
      return new CodecSplit(flags, null);
    }

    final String name = matcher.group(1);
    for (final MapCodec codec : MapCodec.values()) {
      final String codecName = codec.name();
      if (codecName.equalsIgnoreCase(name)) {
        final int start = matcher.start();
        final String remainingFlags = flags.substring(0, start);
        final String options = remainingFlags.strip();
        return new CodecSplit(options, codec);
      }
    }
    return null;
  }

  private VmConfiguration buildConfiguration(final String flags) {
    final Path dataFolder = this.plugin.getDataPath();
    final Path imageFolder = DiskImages.folderOf(dataFolder);
    final long maximumMemory = maximumMemoryBytes();
    final VmConfigurationBuilder builder = parseOptions(flags, imageFolder, maximumMemory);
    // without it, the guest gets MCAV's network that reaches nothing, as no option of the command chooses one
    final PluginDataConfigurationMapper settings = this.plugin.getConfiguration();
    final boolean networkAllowed = settings.isVmAllowNetwork();
    if (networkAllowed) {
      builder.network(QEMU_NETWORK);
    }

    return builder.build();
  }

  // quoted values may hold spaces; options QEMU repeats, such as -drive, are kept
  static VmConfigurationBuilder parseOptions(final String commandLine, final Path imageFolder) {
    return parseOptions(commandLine, imageFolder, Long.MAX_VALUE);
  }

  // a guest can use all of its memory, so the server's limit applies
  static VmConfigurationBuilder parseOptions(final String commandLine, final Path imageFolder, final long maximumMemoryBytes) {
    final VmConfigurationBuilder builder = parseAllowedOptions(commandLine, imageFolder);
    final VmConfiguration configuration = builder.build();
    final Optional<String> requestedMemory = configuration.find(QemuHardwareValues.MEMORY_OPTION);
    final @Nullable String requestedValue = requestedMemory.orElse(null);
    final String memory = Objects.requireNonNullElse(requestedValue, QemuHardwareValues.DEFAULT_MEMORY_SIZE);
    final long requested = QemuHardwareValues.memoryBytes(memory);
    if (requested > maximumMemoryBytes) {
      final long maximumMebibytes = maximumMemoryBytes >> QemuHardwareValues.MEBIBYTE_SHIFT;
      final String message = "QEMU option -m %s asks for more than the %s MiB a machine may have on this server!".formatted(
        memory,
        maximumMebibytes
      );
      throw new IllegalArgumentException(message);
    }

    return builder;
  }

  // the JVM reports a container's memory limit here, not the host's
  static long maximumMemoryBytes() {
    final OperatingSystemMXBean system = ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);
    final long totalMemoryBytes = system.getTotalMemorySize();
    return maximumMemoryBytes(totalMemoryBytes);
  }

  static long maximumMemoryBytes(final long totalMemoryBytes) {
    return Math.max(MINIMUM_MACHINE_MEMORY_BYTES, totalMemoryBytes / MACHINE_MEMORY_DIVISOR);
  }

  private static VmConfigurationBuilder parseAllowedOptions(final String commandLine, final Path imageFolder) {
    final List<String> tokens = tokenize(commandLine);
    final VmConfigurationBuilder builder = VmConfiguration.builder();
    final int count = tokens.size();
    int index = 0;
    while (index < count) {
      index = addOption(builder, tokens, index, imageFolder);
    }
    return builder;
  }

  private static int addOption(final VmConfigurationBuilder builder, final List<String> tokens, final int index, final Path imageFolder) {
    final String token = tokens.get(index);
    final int next = index + 1;
    if (!token.startsWith(OPTION_PREFIX)) {
      return next;
    }

    final String name = token.substring(OPTION_PREFIX.length());
    final int count = tokens.size();
    final boolean hasValue = next < count && isValue(tokens, next);
    if (!hasValue) {
      requireFlagOption(name);
      builder.flag(name);
      return next;
    }

    final String rawValue = tokens.get(next);
    final String value = checkedValue(name, rawValue, imageFolder);
    final boolean repeatable = REPEATABLE_OPTIONS.contains(name);
    if (repeatable) {
      builder.repeatable(name, value);
    } else {
      builder.option(name, value);
    }

    return next + 1;
  }

  private static void requireFlagOption(final String name) {
    final boolean flagOption = FLAG_OPTIONS.contains(name);
    if (flagOption) {
      return;
    }

    final boolean needsValue =
      HARDWARE_OPTIONS.contains(name) || IMAGE_OPTIONS.contains(name) || name.equals(QemuHardwareValues.DRIVE_OPTION);
    if (needsValue) {
      final String message = "QEMU option -%s needs a value!".formatted(name);
      throw new IllegalArgumentException(message);
    }

    throw unsupported(name);
  }

  private static String checkedValue(final String name, final String value, final Path imageFolder) {
    final boolean hardware = HARDWARE_OPTIONS.contains(name);
    if (hardware) {
      QemuHardwareValues.check(name, value);
      return value;
    }

    final boolean image = IMAGE_OPTIONS.contains(name);
    if (image) {
      final Path resolved = DiskImages.require(imageFolder, value);
      return resolved.toString();
    }

    final boolean driveOption = name.equals(QemuHardwareValues.DRIVE_OPTION);
    if (driveOption) {
      return checkedDrive(value, imageFolder);
    }

    final boolean flagOption = FLAG_OPTIONS.contains(name);
    if (flagOption) {
      final String message = "QEMU option -%s takes no value!".formatted(name);
      throw new IllegalArgumentException(message);
    }

    throw unsupported(name);
  }

  private static String checkedDrive(final String value, final Path imageFolder) {
    final List<String> parts = DRIVE_SPLITTER.splitToList(value);
    final List<String> checked = new ArrayList<>(parts.size());
    int fileCount = 0;
    for (final String part : parts) {
      final boolean namesFile = part.startsWith(FILE_KEY);
      if (namesFile) {
        fileCount++;
        final String image = part.substring(FILE_KEY.length());
        final String file = driveFile(image, imageFolder);
        checked.add(file);
      } else {
        requireNoPath(QemuHardwareValues.DRIVE_OPTION, part);
        QemuHardwareValues.checkDriveProperty(part, value);
        checked.add(part);
      }
    }
    final boolean exactlyOneFile = fileCount == 1;
    if (!exactlyOneFile) {
      final String message = "Drive %s must name its disk image exactly once, as file=<image>!".formatted(value);
      throw new IllegalArgumentException(message);
    }

    return String.join(VALUE_SEPARATOR, checked);
  }

  // QEMU splits the value of a drive at commas, so a comma of the image's path is written twice
  private static String driveFile(final String image, final Path imageFolder) {
    final Path resolved = DiskImages.require(imageFolder, image);
    final String path = resolved.toString();
    final String escapedPath = path.replace(VALUE_SEPARATOR, ESCAPED_VALUE_SEPARATOR);
    return FILE_KEY + escapedPath;
  }

  // only a disk image of the image folder may name a file
  private static void requireNoPath(final String name, final String value) {
    final boolean namesPath = value.indexOf(UNIX_SEPARATOR) >= 0 || value.indexOf(WINDOWS_SEPARATOR) >= 0;
    if (namesPath) {
      final String message = "QEMU option -%s must not name a file, but %s does!".formatted(name, value);
      throw new IllegalArgumentException(message);
    }
  }

  private static IllegalArgumentException unsupported(final String name) {
    final String supported = String.join(NAME_SEPARATOR, supportedOptions());
    final String message = "QEMU option -%s is not supported, use one of %s instead!".formatted(name, supported);
    return new IllegalArgumentException(message);
  }

  /**
   * Returns the QEMU options the command accepts, with a dash and in alphabetical order.
   *
   * @return the option names, as an unmodifiable list
   * @since 1.0.0
   */
  public static List<String> supportedOptions() {
    return Stream.of(FLAG_OPTIONS, HARDWARE_OPTIONS, IMAGE_OPTIONS, Set.of(QemuHardwareValues.DRIVE_OPTION))
      .flatMap(Set::stream)
      .map(OPTION_PREFIX::concat)
      .sorted()
      .toList();
  }

  private static boolean isValue(final List<String> tokens, final int index) {
    final String token = tokens.get(index);
    return !token.startsWith(OPTION_PREFIX);
  }

  // "" is an empty token
  static List<String> tokenize(final String commandLine) {
    final List<String> tokens = new ArrayList<>();
    final StringBuilder current = new StringBuilder();
    boolean quoted = false;
    boolean inToken = false;
    final int length = commandLine.length();
    for (int index = 0; index < length; index++) {
      final char character = commandLine.charAt(index);
      if (character == QUOTE) {
        quoted = !quoted;
        inToken = true;
      } else if (quoted || !Character.isWhitespace(character)) {
        current.append(character);
        inToken = true;
      } else if (inToken) {
        finishToken(tokens, current);
        inToken = false;
      }
    }

    if (inToken) {
      finishToken(tokens, current);
    }
    return tokens;
  }

  private static void finishToken(final List<String> tokens, final StringBuilder current) {
    final String token = current.toString();
    tokens.add(token);
    current.setLength(0);
  }

  /**
   * Returns the permission a player needs to send input to the running virtual machine.
   *
   * @return {@code mcav.command.vm.interact}
   * @since 1.0.0
   */
  @Override
  protected String getInteractionPermission() {
    return INTERACT_PERMISSION;
  }

  record CodecSplit(String options, @Nullable MapCodec codec) {}
}
