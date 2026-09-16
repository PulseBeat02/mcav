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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.sandbox.MCAVSandbox;
import me.brandonli.mcav.sandbox.locale.Message;
import me.brandonli.mcav.sandbox.utils.DitheringArgument;
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
 */
public final class VirtualizeCommand extends AbstractInteractiveCommand<VMPlayer> {

  private static final Set<String> REPEATABLE_OPTIONS = Set.of(
    "drive",
    "device",
    "netdev",
    "chardev",
    "object",
    "nic",
    "net",
    "fsdev",
    "virtfs",
    "global",
    "usbdevice"
  );

  /**
   * Constructs the command.
   *
   * @param plugin the plugin
   */
  public VirtualizeCommand(final MCAVSandbox plugin) {
    super(plugin);
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
  }

  /**
   * Handles {@code /mcav vm interact}: switches chat input to the virtual machine on or off for the player who runs
   * it.
   *
   * <p>While it is on, the chat messages of the player are not sent to chat. Their text is typed into the virtual
   * machine instead, as if on its keyboard. Clicking the map screen does not need this mode: left and right clicks
   * on the screen always reach the virtual machine as mouse clicks. Running the command again switches it off, and
   * the player is told which state is now active.
   *
   * <p>Requires the permission {@code mcav.vm.interact}. Only players can run it, since the console has no chat to
   * forward.
   *
   * @param sender the player who switches their chat input
   */
  @Command("mcav vm interact")
  @Permission("mcav.vm.interact")
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
   * <ditheringAlgorithm> <architecture> <flags>}: boots a QEMU virtual machine and streams its display onto a wall
   * of maps.
   *
   * <p>QEMU must be installed on the server, with the program for the chosen architecture on the {@code PATH}. Build
   * the wall first with {@code /mcav screen}, using the same block dimensions and map id. Players can then left
   * click or right click the screen to click inside the guest, and type into it after enabling
   * {@code /mcav vm interact}. Only one virtual machine runs at a time: creating a new one powers off the running
   * one.
   *
   * <p>Requires the permission {@code mcav.command.vm.create}; players and the console can run it. When QEMU is not
   * installed or the dimensions are invalid, the sender gets an error message and nothing starts. Otherwise the
   * sender is told "Loading virtual machine...", and later that it was created, that the QEMU program for the
   * architecture is not on the {@code PATH}, or that it failed to start, with the details in the console.
   *
   * @param sender             who ran the command
   * @param playerSelector     the players who see the virtual machine on the maps, such as a player name or
   *                           {@code @a}
   * @param vmResolution       the resolution of the display of the guest as {@code <width>x<height>} in pixels,
   *                           such as {@code 1280x720}; use 128 times the block dimensions to fill the wall exactly
   * @param targetFps          how many frames per second are captured from the display; higher values feel
   *                           smoother but cost more CPU and bandwidth
   * @param blockDimensions    the size of the wall as {@code <width>x<height>} in maps, such as {@code 5x5}
   * @param mapId              the id of the top left map of the wall, as given to {@code /mcav screen}
   * @param ditheringAlgorithm how colors are reduced to the map palette; {@code NEAREST_COLOR} keeps text and
   *                           desktops sharp and stable, {@code FILTER_LITE} looks best for pictures, see
   *                           {@link DitheringArgument}
   * @param architecture       the processor the guest is emulated with: {@code X86_64}, {@code ARM},
   *                           {@code AARCH64}, or {@code RISCV64}, each run by its own {@code qemu-system-*} program
   * @param flags              the QEMU options, the rest of the command line, such as
   *                           {@code -cdrom "/isos/alpine.iso" -m 2048M}; quote values with spaces, and options QEMU
   *                           accepts more than once, such as {@code -drive}, may be repeated
   */
  @Command(
    "mcav vm create <playerSelector> <vmResolution> <targetFps> <blockDimensions> <mapId> <ditheringAlgorithm> <architecture> <flags>"
  )
  @Permission("mcav.command.vm.create")
  @CommandDescription("mcav.command.vm.create.info")
  public void createVM(
    final CommandSender sender,
    final MultiplePlayerSelector playerSelector,
    @Argument(suggestions = "resolutions") @Quoted final String vmResolution,
    @Argument(suggestions = "target-fps") @Range(min = "1") final int targetFps,
    @Argument(suggestions = "dimensions") @Quoted final String blockDimensions,
    @Argument(suggestions = "ids") @Range(min = "0") final int mapId,
    final DitheringArgument ditheringAlgorithm,
    final VMPlayer.Architecture architecture,
    @Greedy final String flags
  ) {
    Preconditions.checkNotNull(sender, "Sender must not be null");
    Preconditions.checkNotNull(playerSelector, "Player selector must not be null");
    Preconditions.checkNotNull(ditheringAlgorithm, "Dithering algorithm must not be null");
    Preconditions.checkNotNull(architecture, "Architecture must not be null");
    Preconditions.checkNotNull(flags, "Flags must not be null");

    final boolean qemu = this.plugin.isQemuInstalled();
    if (!qemu) {
      final Component message = Message.QEMU_NOT_INSTALLED.build();
      sender.sendMessage(message);
      return;
    }

    final Pair<Integer, Integer> resolution = parseDimensions(sender, vmResolution);
    final Pair<Integer, Integer> blocks = resolution == null ? null : parseDimensions(sender, blockDimensions);
    if (resolution == null || blocks == null) {
      return;
    }

    final ScreenSettings settings = new ScreenSettings(playerSelector, blocks, resolution, mapId, ditheringAlgorithm);
    final Screen screen = this.createScreen(settings);
    this.startMachine(sender, screen, resolution, targetFps, architecture, flags);
  }

  private void startMachine(
    final CommandSender sender,
    final Screen screen,
    final Pair<Integer, Integer> resolution,
    final int targetFps,
    final VMPlayer.Architecture architecture,
    final String flags
  ) {
    final VMConfiguration vmConfiguration = parseOptions(flags);
    final int width = resolution.getFirst();
    final int height = resolution.getSecond();
    final VMSettings settings = VMSettings.of(width, height, targetFps);

    final VMPlayer machine = VMPlayer.create();
    final VideoAttachableCallback callback = machine.getVideoAttachableCallback();
    final VideoPipelineStep pipeline = screen.getPipeline();
    callback.attach(pipeline);

    final Component loading = Message.VM_LOADING.build();
    sender.sendMessage(loading);
    final CompletableFuture<Boolean> start = machine.startAsync(settings, architecture, vmConfiguration, this.service);
    this.reportStartWhenDone(sender, machine, screen, start, "the virtual machine");
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
   * Parses QEMU options such as {@code -cdrom "C:/My Images/alpine.iso" -m 2048M -enable-kvm}. Quoted values may
   * contain spaces, and options QEMU accepts more than once, such as {@code -drive}, are kept.
   *
   * @param commandLine the options
   * @return the configuration
   */
  static VMConfiguration parseOptions(final String commandLine) {
    final List<String> tokens = tokenize(commandLine);
    final VMConfiguration configuration = VMConfiguration.builder();
    final int count = tokens.size();
    int index = 0;
    while (index < count) {
      index = addOption(configuration, tokens, index);
    }
    return configuration;
  }

  /**
   * Adds the option at an index to the configuration, with the token after it as its value unless that token is an
   * option too. Words that are not options are skipped.
   *
   * @return the index of the token after the option and its value
   */
  private static int addOption(final VMConfiguration configuration, final List<String> tokens, final int index) {
    final String token = tokens.get(index);
    final int next = index + 1;
    if (!token.startsWith("-")) {
      return next;
    }

    final String name = token.substring(1);
    final int count = tokens.size();
    final boolean hasValue = next < count && isValue(tokens, next);
    if (!hasValue) {
      configuration.flag(name);
      return next;
    }

    final String value = tokens.get(next);
    final boolean repeatable = REPEATABLE_OPTIONS.contains(name);
    if (repeatable) {
      configuration.repeatable(name, value);
    } else {
      configuration.option(name, value);
    }
    return next + 1;
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
