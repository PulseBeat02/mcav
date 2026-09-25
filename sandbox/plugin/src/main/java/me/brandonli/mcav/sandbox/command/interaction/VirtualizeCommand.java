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
package me.brandonli.mcav.sandbox.command.interaction;

import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import me.brandonli.mcav.json.ytdlp.format.URLParseDump;
import me.brandonli.mcav.media.player.attachable.AudioAttachableCallback;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.pipeline.filter.audio.AudioFilter;
import me.brandonli.mcav.media.player.pipeline.step.AudioPipelineStep;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.sandbox.MCAVSandbox;
import me.brandonli.mcav.sandbox.audio.AudioOutputs;
import me.brandonli.mcav.sandbox.audio.AudioProvider;
import me.brandonli.mcav.sandbox.locale.Message;
import me.brandonli.mcav.sandbox.utils.AudioArgument;
import me.brandonli.mcav.sandbox.utils.DiskImages;
import me.brandonli.mcav.sandbox.utils.DitheringArgument;
import me.brandonli.mcav.sandbox.utils.TaskUtils;
import me.brandonli.mcav.utils.immutable.Pair;
import me.brandonli.mcav.utils.interaction.MouseClick;
import me.brandonli.mcav.vm.ExecutableNotInPathException;
import me.brandonli.mcav.vm.VMConfiguration;
import me.brandonli.mcav.vm.VMPlayer;
import me.brandonli.mcav.vm.VMSettings;
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
 */
public final class VirtualizeCommand extends AbstractInteractiveCommand<VMPlayer> {

  /**
   * The permission a player needs to send input to the running virtual machine, by chat or by clicking the screen.
   */
  static final String INTERACT_PERMISSION = "mcav.vm.interact";

  /**
   * The options QEMU accepts more than once, of those the command supports.
   */
  private static final Set<String> REPEATABLE_OPTIONS = Set.of("drive");

  /**
   * The options that are a switch, so they must not be given a value.
   */
  private static final Set<String> FLAG_OPTIONS = Set.of("snapshot", "no-reboot", "no-hpet", "no-fd-bootchk", "enable-kvm", "usb");

  /**
   * The options that describe the hardware of the machine. Their values must have a form {@link QemuHardwareValues}
   * allows, so they never name a file nor change what mcav owns.
   */
  private static final Set<String> HARDWARE_OPTIONS = Set.of("m", "smp", "cpu", "machine", "accel", "boot", "name", "k", "vga", "rtc");

  /**
   * The options whose value is a disk image of the {@value DiskImages#FOLDER_NAME} folder.
   */
  private static final Set<String> IMAGE_OPTIONS = Set.of("cdrom", "hda", "hdb", "hdc", "hdd", "fda", "fdb");

  /**
   * The option that describes a drive, whose {@code file} names a disk image.
   */
  private static final String DRIVE_OPTION = "drive";

  private static final String FILE_KEY = "file=";

  private static final Splitter DRIVE_SPLITTER = Splitter.on(',');

  // the machine whose sound plays into an audio output, which is let go of when the machine is released
  private final AtomicReference<@Nullable VMPlayer> audioOwner;

  /**
   * Constructs the command.
   *
   * @param plugin the plugin
   */
  public VirtualizeCommand(final MCAVSandbox plugin) {
    super(plugin);
    this.audioOwner = new AtomicReference<>();
  }

  /**
   * Gets the permission a player needs to send input to the running virtual machine.
   *
   * @return {@value #INTERACT_PERMISSION}
   */
  @Override
  protected String getInteractionPermission() {
    return INTERACT_PERMISSION;
  }

  /**
   * Clicks the display of the virtual machine with the left mouse button.
   *
   * @param current the running virtual machine
   * @param x       the x coordinate on the display, in pixels
   * @param y       the y coordinate on the display, in pixels
   */
  @Override
  protected void handleLeftClick(final VMPlayer current, final int x, final int y) {
    Preconditions.checkNotNull(current, "Virtual machine must not be null");
    current.sendMouseEvent(MouseClick.LEFT, x, y);
  }

  /**
   * Clicks the display of the virtual machine with the right mouse button.
   *
   * @param current the running virtual machine
   * @param x       the x coordinate on the display, in pixels
   * @param y       the y coordinate on the display, in pixels
   */
  @Override
  protected void handleRightClick(final VMPlayer current, final int x, final int y) {
    Preconditions.checkNotNull(current, "Virtual machine must not be null");
    current.sendMouseEvent(MouseClick.RIGHT, x, y);
  }

  /**
   * Types text on the keyboard of the virtual machine.
   *
   * @param current the running virtual machine
   * @param text    the text to type
   */
  @Override
  protected void handleTextInput(final VMPlayer current, final String text) {
    Preconditions.checkNotNull(current, "Virtual machine must not be null");
    Preconditions.checkNotNull(text, "Text must not be null");
    current.sendKeyEvent(text);
  }

  /**
   * Stops the QEMU process of a virtual machine.
   *
   * @param current the virtual machine to stop, which is no longer the running one
   */
  @Override
  protected void releasePlayer(final VMPlayer current) {
    Preconditions.checkNotNull(current, "Virtual machine must not be null");
    current.release();
    // the machine lets go of the audio output it played into, and only a machine that played into one
    final boolean owner = this.audioOwner.compareAndSet(current, null);
    if (owner) {
      final AudioProvider provider = this.plugin.getAudioProvider();
      provider.releaseAudioFilter();
    }
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
   * <p>Requires the permission {@code mcav.vm.interact}. Only players can run it, since the console has no chat to
   * forward.
   *
   * @param sender the player who switches their chat input
   */
  @Command("mcav vm interact")
  @Permission(INTERACT_PERMISSION)
  @CommandDescription("mcav.command.vm.interact.info")
  public void toggleInteraction(final Player sender) {
    Preconditions.checkNotNull(sender, "Sender must not be null");
    final Component enabled = Message.INTERACT_ENABLE.build();
    final Component disabled = Message.INTERACT_DISABLE.build();
    this.toggleInteraction(sender, enabled, disabled);
  }

  /**
   * Handles {@code /mcav vm release}: stops the QEMU process of the running virtual machine, forcibly if it does not
   * exit within a few seconds, stops streaming to its maps, and clears the maps for every viewer. The guest is not
   * asked to shut down first, so unsaved work inside it is lost.
   *
   * <p>Requires the permission {@code mcav.vm.release}; players and the console can run it. The sender is told
   * "Virtual machine released!", also when no virtual machine was running.
   *
   * @param sender who ran the command
   */
  @Command("mcav vm release")
  @Permission("mcav.vm.release")
  @CommandDescription("mcav.command.vm.release.info")
  public void releaseVM(final CommandSender sender) {
    Preconditions.checkNotNull(sender, "Sender must not be null");
    final Component released = Message.VM_RELEASE.build();
    this.releaseResource(sender, released);
  }

  /**
   * Handles {@code /mcav vm create <playerSelector> <vmResolution> <targetFps> <blockDimensions> <mapId>
   * <ditheringAlgorithm> <architecture> <audioType> <flags>}: boots a QEMU virtual machine and streams its display
   * onto a wall of maps, and its sound into the chosen audio output. Only x86-64 PC and Q35 machines have sound; mcav
   * adds their sound card itself, and the flags cannot change it.
   *
   * <p>QEMU must be installed on the server, with the program for the chosen architecture on the {@code PATH}. Build
   * the wall first with {@code /mcav screen}, using the same block dimensions and map id. Players can then left
   * click or right click the screen to click inside the guest, and type into it after enabling
   * {@code /mcav vm interact}. Only one virtual machine runs at a time: creating a new one powers off the running
   * one.
   *
   * <p>Requires the permission {@code mcav.command.vm.create}; players and the console can run it. When QEMU is not
   * installed, the dimensions are invalid, or an option is not supported, the sender gets an error message and
   * nothing starts. Otherwise the
   * sender is told "Loading virtual machine...", and later that it was created, that the QEMU program for the
   * architecture is not on the {@code PATH}, or that it failed to start, with the details in the console.
   *
   * @param sender             who ran the command
   * @param playerSelector     the players who see the virtual machine on the maps, such as a player name or
   *                           {@code @a}
   * @param vmResolution       the resolution of the display of the guest as {@code <width>x<height>} in pixels,
   *                           such as {@code 1280x720}; use 128 times the block dimensions to fill the wall exactly
   * @param targetFps          how many frames per second are captured from the display, from 1 to 240; higher
   *                           values feel smoother but cost more CPU and bandwidth
   * @param blockDimensions    the size of the wall as {@code <width>x<height>} in maps, such as {@code 5x5}
   * @param mapId              the id of the top left map of the wall, as given to {@code /mcav screen}
   * @param ditheringAlgorithm how colors are reduced to the map palette; {@code NEAREST_COLOR} keeps text and
   *                           desktops sharp and stable, {@code FILTER_LITE} looks best for pictures, see
   *                           {@link DitheringArgument}
   * @param architecture       the processor the guest is emulated with: {@code X86_64}, {@code ARM},
   *                           {@code AARCH64}, or {@code RISCV64}, each run by its own {@code qemu-system-*} program
   * @param audioType          where the sound of the guest plays, as for the video commands, see
   *                           {@link AudioArgument}; {@code NONE} keeps the machine silent
   * @param flags              the QEMU options, the rest of the command line, such as
   *                           {@code -cdrom "alpine linux.iso" -m 2048M}; quote values with spaces, and options QEMU
   *                           accepts more than once, such as {@code -drive}, may be repeated. Only the options of
   *                           {@link #supportedOptions()} are accepted, and a disk image must be a file of the
   *                           {@value DiskImages#FOLDER_NAME} folder of the plugin, named without its folder
   */
  @Command(
    "mcav vm create <playerSelector> <vmResolution> <targetFps> <blockDimensions> <mapId> <ditheringAlgorithm> <architecture> <audioType> <flags>"
  )
  @Permission("mcav.command.vm.create")
  @CommandDescription("mcav.command.vm.create.info")
  public void createVM(
    final CommandSender sender,
    final MultiplePlayerSelector playerSelector,
    @Argument(suggestions = "resolutions") @Quoted final String vmResolution,
    @Argument(suggestions = "target-fps") @Range(min = "1", max = "240") final int targetFps,
    @Argument(suggestions = "dimensions") @Quoted final String blockDimensions,
    @Argument(suggestions = "ids") @Range(min = "0") final int mapId,
    final DitheringArgument ditheringAlgorithm,
    final VMPlayer.Architecture architecture,
    final AudioArgument audioType,
    @Greedy final String flags
  ) {
    Preconditions.checkNotNull(sender, "Sender must not be null");
    Preconditions.checkNotNull(playerSelector, "Player selector must not be null");
    Preconditions.checkNotNull(ditheringAlgorithm, "Dithering algorithm must not be null");
    Preconditions.checkNotNull(architecture, "Architecture must not be null");
    Preconditions.checkNotNull(audioType, "Audio type must not be null");
    Preconditions.checkNotNull(flags, "Flags must not be null");

    final boolean qemu = this.plugin.isQemuInstalled();
    if (!qemu) {
      final Component message = Message.QEMU_NOT_INSTALLED.build();
      sender.sendMessage(message);
      return;
    }

    final Pair<Integer, Integer> resolution = parseDimensions(sender, vmResolution);
    final Pair<Integer, Integer> blocks = resolution == null ? null : parseScreenDimensions(sender, blockDimensions);
    if (resolution == null || blocks == null) {
      return;
    }

    final VMConfiguration vmConfiguration = this.parseOptions(sender, flags);
    if (vmConfiguration == null) {
      return;
    }
    final AudioProvider provider = this.plugin.getAudioProvider();
    final Component audioProblem = AudioOutputs.findProblem(provider, audioType);
    if (audioProblem != null) {
      sender.sendMessage(audioProblem);
      return;
    }
    final int width = resolution.getFirst();
    final int height = resolution.getSecond();
    final VMSettings vmSettings = VMSettings.of(width, height, targetFps);
    final ScreenSettings settings = new ScreenSettings(playerSelector, blocks, resolution, mapId, ditheringAlgorithm);
    final Screen screen = this.createScreen(settings);
    final Player[] viewers = playerSelector.values().toArray(Player[]::new);
    final Sound sound = new Sound(audioType, viewers);
    this.createResource(() -> this.startMachine(sender, screen, vmSettings, architecture, vmConfiguration, sound));
  }

  private void startMachine(
    final CommandSender sender,
    final Screen screen,
    final VMSettings settings,
    final VMPlayer.Architecture architecture,
    final VMConfiguration vmConfiguration,
    final Sound sound
  ) {
    final VMPlayer machine = VMPlayer.create();
    this.ownCreatedPlayer(machine);
    final VideoAttachableCallback callback = machine.getVideoAttachableCallback();
    final VideoPipelineStep pipeline = screen.getPipeline();
    callback.attach(pipeline);
    this.attachSound(machine, sound);

    final Component loading = Message.VM_LOADING.build();
    sender.sendMessage(loading);
    final ExecutorService executor = this.startExecutor(machine, screen);
    final CompletableFuture<Boolean> start = machine.startAsync(settings, architecture, vmConfiguration, executor);
    this.reportStartWhenDone(sender, machine, screen, start, "the virtual machine");
    TaskUtils.whenComplete(start, (started, error) -> {
      if (error == null && Boolean.TRUE.equals(started)) {
        final AudioProvider provider = this.plugin.getAudioProvider();
        TaskUtils.runOnMainThread(this.plugin, () -> AudioOutputs.sendLink(provider, sound.getType(), sound.getViewers()));
      }
    });
  }

  /**
   * Plays the sound of a machine into the chosen audio output, and remembers the machine as the one that owns the
   * output, so releasing it lets go of the output.
   *
   * @param machine the machine
   * @param sound   the output and the players who hear it
   */
  private void attachSound(final VMPlayer machine, final Sound sound) {
    final AudioArgument type = sound.getType();
    if (type == AudioArgument.NONE) {
      return;
    }
    final AudioProvider provider = this.plugin.getAudioProvider();
    final URLParseDump dump = new URLParseDump();
    dump.title = "Virtual machine";
    final AudioFilter filter = provider.constructFilter(type, dump, sound.getViewers());
    final AudioAttachableCallback audio = machine.getAudioAttachableCallback();
    audio.attach(AudioPipelineStep.of(filter));
    this.audioOwner.set(machine);
  }

  /**
   * Where the sound of a machine plays, and who hears it.
   */
  static final class Sound {

    private final AudioArgument type;
    private final Player[] viewers;

    /**
     * Constructs the sound of a machine.
     *
     * @param type    the audio output
     * @param viewers the players who see the machine
     */
    Sound(final AudioArgument type, final Player[] viewers) {
      this.type = type;
      this.viewers = viewers.clone();
    }

    AudioArgument getType() {
      return this.type;
    }

    Player[] getViewers() {
      return this.viewers.clone();
    }
  }

  /**
   * Creates the message that tells the sender whether the virtual machine started. A QEMU program missing from the
   * {@code PATH} gets its own message, also when wrapped in a {@link CompletionException}; other reasons are
   * logged, not shown.
   *
   * @param success whether the virtual machine started
   * @param error   why the virtual machine failed to start, if known
   * @return the message
   */
  @Override
  protected Component createStartMessage(final boolean success, final @Nullable Throwable error) {
    if (success) {
      return Message.VM_CREATE.build();
    }

    Throwable cause = error;
    if (cause instanceof CompletionException) {
      cause = cause.getCause();
    }
    if (cause instanceof ExecutableNotInPathException) {
      return Message.VM_PATH.build();
    }
    return Message.VM_ERROR.build();
  }

  /**
   * Parses the QEMU options of the sender, telling them when an option is not supported.
   *
   * @param sender who ran the command
   * @param flags  the options as entered
   * @return the configuration, or {@code null} if an option was refused
   */
  private @Nullable VMConfiguration parseOptions(final CommandSender sender, final String flags) {
    final Path dataFolder = this.plugin.getDataPath();
    final Path imageFolder = DiskImages.folderOf(dataFolder);
    try {
      return parseOptions(flags, imageFolder);
    } catch (final IllegalArgumentException exception) {
      final String cause = exception.getMessage();
      final String reason = Objects.requireNonNullElse(cause, "The options are not valid");
      final Component message = Message.UNSUPPORTED_VM_FLAGS.build(reason);
      sender.sendMessage(message);
      return null;
    }
  }

  /**
   * Parses QEMU options such as {@code -cdrom "alpine linux.iso" -m 2048M -enable-kvm}. Quoted values may contain
   * spaces, and options QEMU accepts more than once, such as {@code -drive}, are kept. Only the options of
   * {@link #supportedOptions()} are accepted, and every disk image is resolved in the image folder.
   *
   * @param commandLine the options
   * @param imageFolder the folder the disk images live in
   * @return the configuration
   * @throws IllegalArgumentException if an option is not supported, is given a value it does not take, misses the
   *                                  value it needs, or names a disk image outside the image folder
   */
  static VMConfiguration parseOptions(final String commandLine, final Path imageFolder) {
    final List<String> tokens = tokenize(commandLine);
    final VMConfiguration configuration = VMConfiguration.builder();
    final int count = tokens.size();
    int index = 0;
    while (index < count) {
      index = addOption(configuration, tokens, index, imageFolder);
    }
    return configuration;
  }

  /**
   * Adds the option at an index to the configuration, with the token after it as its value unless that token is an
   * option too. Words that are not options are skipped.
   *
   * @return the index of the token after the option and its value
   */
  private static int addOption(final VMConfiguration configuration, final List<String> tokens, final int index, final Path imageFolder) {
    final String token = tokens.get(index);
    final int next = index + 1;
    if (!token.startsWith("-")) {
      return next;
    }

    final String name = token.substring(1);
    final int count = tokens.size();
    final boolean hasValue = next < count && isValue(tokens, next);
    if (!hasValue) {
      requireFlagOption(name);
      configuration.flag(name);
      return next;
    }

    final String rawValue = tokens.get(next);
    final String value = checkedValue(name, rawValue, imageFolder);
    final boolean repeatable = REPEATABLE_OPTIONS.contains(name);
    if (repeatable) {
      configuration.repeatable(name, value);
    } else {
      configuration.option(name, value);
    }
    return next + 1;
  }

  private static void requireFlagOption(final String name) {
    final boolean flag = FLAG_OPTIONS.contains(name);
    if (flag) {
      return;
    }
    final boolean needsValue = HARDWARE_OPTIONS.contains(name) || IMAGE_OPTIONS.contains(name) || name.equals(DRIVE_OPTION);
    if (needsValue) {
      throw new IllegalArgumentException("The QEMU option -" + name + " needs a value");
    }
    throw unsupported(name);
  }

  /**
   * Checks the value of an option and returns the value the machine is given, which is the resolved path of a disk
   * image and the value as written for every other option.
   */
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
    final boolean drive = name.equals(DRIVE_OPTION);
    if (drive) {
      return checkedDrive(value, imageFolder);
    }
    final boolean flag = FLAG_OPTIONS.contains(name);
    if (flag) {
      throw new IllegalArgumentException("The QEMU option -" + name + " takes no value");
    }
    throw unsupported(name);
  }

  /**
   * Checks the parts of a drive, whose {@code file} names a disk image and whose other parts describe how the drive
   * is attached.
   */
  private static String checkedDrive(final String value, final Path imageFolder) {
    final List<String> parts = DRIVE_SPLITTER.splitToList(value);
    final List<String> checked = new ArrayList<>(parts.size());
    int files = 0;
    for (final String part : parts) {
      final boolean names = part.startsWith(FILE_KEY);
      if (names) {
        files++;
        final String image = part.substring(FILE_KEY.length());
        final Path resolved = DiskImages.require(imageFolder, image);
        checked.add(FILE_KEY + resolved);
      } else {
        requireNoPath(DRIVE_OPTION, part);
        checked.add(part);
      }
    }
    final boolean one = files == 1;
    Preconditions.checkArgument(one, "A drive names its disk image exactly once, as file=<image>, but got %s", value);
    return String.join(",", checked);
  }

  /**
   * Refuses a value that names a file, because only a disk image of the image folder may.
   */
  private static void requireNoPath(final String name, final String value) {
    final boolean path = value.indexOf('/') >= 0 || value.indexOf('\\') >= 0;
    if (path) {
      throw new IllegalArgumentException("The QEMU option -" + name + " must not name a file, but got " + value);
    }
  }

  private static IllegalArgumentException unsupported(final String name) {
    final String supported = String.join(", ", supportedOptions());
    return new IllegalArgumentException("Unsupported QEMU option -" + name + "; the command accepts " + supported);
  }

  /**
   * Gets the QEMU options the command accepts, with a dash and in alphabetical order.
   *
   * @return the option names, as an unmodifiable list
   */
  public static List<String> supportedOptions() {
    final List<String> names = new ArrayList<>();
    for (final String flag : FLAG_OPTIONS) {
      names.add("-" + flag);
    }
    for (final String hardware : HARDWARE_OPTIONS) {
      names.add("-" + hardware);
    }
    for (final String image : IMAGE_OPTIONS) {
      names.add("-" + image);
    }
    names.add("-" + DRIVE_OPTION);
    Collections.sort(names);
    return Collections.unmodifiableList(names);
  }

  private static boolean isValue(final List<String> tokens, final int index) {
    final String token = tokens.get(index);
    return !token.startsWith("-");
  }

  /**
   * Splits a command line at unquoted whitespace. Double quotes group words and are removed, so {@code ""} is an
   * empty token.
   *
   * @param commandLine the command line
   * @return the tokens
   */
  static List<String> tokenize(final String commandLine) {
    final List<String> tokens = new ArrayList<>();
    final StringBuilder current = new StringBuilder();
    boolean quoted = false;
    boolean inToken = false;
    final int length = commandLine.length();
    for (int index = 0; index < length; index++) {
      final char character = commandLine.charAt(index);
      if (character == '"') {
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
}
