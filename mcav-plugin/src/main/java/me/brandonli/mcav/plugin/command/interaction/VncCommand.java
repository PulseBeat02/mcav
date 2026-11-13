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
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.plugin.MCAVSandbox;
import me.brandonli.mcav.plugin.command.SuggestionProvider;
import me.brandonli.mcav.plugin.data.PluginDataConfigurationMapper;
import me.brandonli.mcav.plugin.locale.LocaleTools.NullComponent;
import me.brandonli.mcav.plugin.locale.Message;
import me.brandonli.mcav.plugin.utils.CleanupUtils;
import me.brandonli.mcav.plugin.utils.DitheringArgument;
import me.brandonli.mcav.plugin.utils.MapCodec;
import me.brandonli.mcav.utils.immutable.Pair;
import me.brandonli.mcav.utils.interaction.MouseClick;
import me.brandonli.mcav.vnc.VncPlayer;
import me.brandonli.mcav.vnc.VncSource;
import me.brandonli.mcav.vnc.VncSourceBuilder;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.incendo.cloud.annotation.specifier.FlagYielding;
import org.incendo.cloud.annotation.specifier.Greedy;
import org.incendo.cloud.annotation.specifier.Quoted;
import org.incendo.cloud.annotation.specifier.Range;
import org.incendo.cloud.annotations.Argument;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.CommandDescription;
import org.incendo.cloud.annotations.Flag;
import org.incendo.cloud.annotations.Permission;
import org.incendo.cloud.bukkit.data.MultiplePlayerSelector;

/**
 * {@code /mcav vnc create|interact|release}: shows the desktop of a VNC server on a map screen.
 *
 * <p>The Minecraft server connects to the VNC server, so only servers an operator listed in {@code vnc.allowed-hosts}
 * of {@code config.yml} can be named, and none is listed by default: a player cannot make the server reach a host of
 * its network that way. A server's password is written in its entry there, never in the command, where the server's
 * log would keep it, and it is not logged or shown anywhere.
 *
 * <p>This class keeps no state of its own; {@link AbstractInteractiveCommand} names the threads that call its methods.
 *
 * @since 1.0.0
 */
public final class VncCommand extends AbstractInteractiveCommand<VncPlayer> {

  private static final String DESKTOP_NULL_MESSAGE = "Desktop must not be null!";
  private static final String SENDER_NULL_MESSAGE = "Sender must not be null!";

  static final String INTERACT_PERMISSION = "mcav.command.vnc.interact";

  /**
   * Creates the command.
   *
   * @param plugin the plugin
   * @throws NullPointerException if {@code plugin} is null
   * @since 1.0.0
   */
  public VncCommand(final MCAVSandbox plugin) {
    super(plugin);
  }

  /**
   * Clicks the desktop with the left mouse button.
   *
   * @param current the connected desktop
   * @param screenX the x coordinate on the desktop, in pixels
   * @param screenY the y coordinate on the desktop, in pixels
   * @throws NullPointerException if {@code current} is null
   * @since 1.0.0
   */
  @Override
  protected void forwardLeftClick(final VncPlayer current, final int screenX, final int screenY) {
    Preconditions.checkNotNull(current, DESKTOP_NULL_MESSAGE);

    current.sendMouseEvent(MouseClick.LEFT, screenX, screenY);
  }

  /**
   * Clicks the desktop with the right mouse button.
   *
   * @param current the connected desktop
   * @param screenX the x coordinate on the desktop, in pixels
   * @param screenY the y coordinate on the desktop, in pixels
   * @throws NullPointerException if {@code current} is null
   * @since 1.0.0
   */
  @Override
  protected void forwardRightClick(final VncPlayer current, final int screenX, final int screenY) {
    Preconditions.checkNotNull(current, DESKTOP_NULL_MESSAGE);

    current.sendMouseEvent(MouseClick.RIGHT, screenX, screenY);
  }

  /**
   * Types text on the desktop.
   *
   * @param current the connected desktop
   * @param text    the text to type
   * @throws NullPointerException if an argument is null
   * @since 1.0.0
   */
  @Override
  protected void forwardTextInput(final VncPlayer current, final String text) {
    Preconditions.checkNotNull(current, DESKTOP_NULL_MESSAGE);
    Preconditions.checkNotNull(text, "Text must not be null!");

    current.sendKeyEvent(text);
  }

  /**
   * Disconnects from a desktop.
   *
   * @param player the desktop to disconnect from, which is no longer the running one
   * @throws NullPointerException if {@code player} is null
   * @since 1.0.0
   */
  @Override
  protected void releasePlayer(final VncPlayer player) {
    Preconditions.checkNotNull(player, DESKTOP_NULL_MESSAGE);

    // closing the connection waits for the client's threads, which a stalled server can hold up
    final VideoAttachableCallback video = player.getVideoAttachableCallback();
    CleanupUtils.runAll(video::detach, () -> this.releaseInTheBackground(player::release));
  }

  /**
   * Handles {@code /mcav vnc interact}: switches chat input to the desktop on or off for the player who runs it.
   *
   * <p>While it is on, the chat messages of the player are not sent to chat but typed on the desktop. Clicking the
   * screen does not need this mode: left and right clicks reach the desktop for every player with this permission.
   *
   * <p>Requires the permission {@code mcav.command.vnc.interact}. Only players can run it.
   *
   * @param sender the player who switches their chat input
   * @throws NullPointerException if {@code sender} is null
   * @since 1.0.0
   */
  @Command("mcav vnc interact")
  @Permission(INTERACT_PERMISSION)
  @CommandDescription("mcav.command.vnc.interact.info")
  public void toggleInteraction(final Player sender) {
    Preconditions.checkNotNull(sender, SENDER_NULL_MESSAGE);

    final Component enabled = Message.INTERACT_ENABLE.build();
    final Component disabled = Message.INTERACT_DISABLE.build();
    this.toggleInteraction(sender, enabled, disabled);
  }

  /**
   * Handles {@code /mcav vnc release}: disconnects from the desktop, stops streaming to its maps, and clears the maps
   * for every viewer. The wall stays in place.
   *
   * <p>Requires the permission {@code mcav.command.vnc.release}; players and the console can run it. The sender is told
   * "VNC desktop released!" once the connection is closed, also when no desktop was connected. When the connection
   * fails to close, the sender is told that it could not be released, and the console logs why.
   *
   * @param sender who ran the command
   * @throws NullPointerException if {@code sender} is null
   * @since 1.0.0
   */
  @Command("mcav vnc release")
  @Permission("mcav.command.vnc.release")
  @CommandDescription("mcav.command.vnc.release.info")
  public void releaseVnc(final CommandSender sender) {
    Preconditions.checkNotNull(sender, SENDER_NULL_MESSAGE);

    final Component released = Message.VNC_RELEASE.build();
    final Component failed = Message.VNC_RELEASE_ERROR.build();
    this.releaseResource(sender, released, failed);
  }

  /**
   * Handles {@code /mcav vnc create <playerSelector> <vncResolution> <targetFramesPerSecond> <blockDimensions> <mapId>
   * <ditheringAlgorithm> <server> [--codec dither|mcv2]}: connects to a VNC server and streams its desktop onto a wall
   * of maps, dithered or with MCV2.
   *
   * <p>Build the wall first with {@code /mcav screen}, using the same block dimensions and map id. Players can then
   * click the screen to click the desktop, and type on it after enabling {@code /mcav vnc interact}. Only one desktop
   * is shown at a time: connecting to another disconnects the one shown.
   *
   * <p>Requires the permission {@code mcav.command.vnc.create}; players and the console can run it. A server that is not
   * in {@code vnc.allowed-hosts}, or invalid dimensions, are reported with an error message and nothing connects.
   * Otherwise the connection is made in the background, and the sender is told once the desktop streams, or that it
   * could not connect, with the details in the console.
   *
   * @param sender                who ran the command
   * @param playerSelector        the players who see the desktop on the maps, such as a player name or {@code @a}
   * @param vncResolution         the size the desktop is scaled to as {@code <width>x<height>} in pixels, such as
   *                              {@code 1280x720}; use 128 times the block dimensions to fill the wall exactly
   * @param targetFramesPerSecond how many frames per second the desktop is streamed at, from 1 to 240
   * @param blockDimensions       the size of the wall as {@code <width>x<height>} in maps, such as {@code 5x5}
   * @param mapId                 the id of the top left map of the wall, as given to {@code /mcav screen}
   * @param ditheringAlgorithm    how colors are reduced to the map palette; {@code NEAREST_COLOR} keeps text sharp, see
   *                              {@link DitheringArgument}
   * @param server                the VNC server as {@code host:port}, or {@code [address]:port} for an IPv6 address, as
   *                              listed in {@code vnc.allowed-hosts}; the rest of the line up to a flag, since a quoted
   *                              argument would refuse the colon unquoted
   * @param codec                 how the picture reaches the players, see {@link MapCodec}; the configured default when
   *                              absent
   * @throws NullPointerException if an argument other than {@code codec} is null
   * @since 1.0.0
   */
  @Command(
    "mcav vnc create <playerSelector> <vncResolution> <targetFramesPerSecond> <blockDimensions> <mapId> <ditheringAlgorithm> <server>"
  )
  @Permission("mcav.command.vnc.create")
  @CommandDescription("mcav.command.vnc.create.info")
  public void createVnc(
    final CommandSender sender,
    final MultiplePlayerSelector playerSelector,
    @Argument(suggestions = SuggestionProvider.RESOLUTIONS) @Quoted final String vncResolution,
    @Argument(suggestions = SuggestionProvider.TARGET_FRAMES_PER_SECOND) @Range(min = "1", max = "240") final int targetFramesPerSecond,
    @Argument(suggestions = SuggestionProvider.DIMENSIONS) @Quoted final String blockDimensions,
    @Argument(suggestions = SuggestionProvider.IDS) @Range(min = "0") final int mapId,
    final DitheringArgument ditheringAlgorithm,
    @Greedy @FlagYielding final String server,
    @Flag("codec") final @Nullable MapCodec codec
  ) {
    Preconditions.checkNotNull(sender, SENDER_NULL_MESSAGE);
    Preconditions.checkNotNull(playerSelector, "Player selector must not be null!");
    Preconditions.checkNotNull(vncResolution, "VNC resolution must not be null!");
    Preconditions.checkNotNull(blockDimensions, "Block dimensions must not be null!");
    Preconditions.checkNotNull(ditheringAlgorithm, "Dithering algorithm must not be null!");
    Preconditions.checkNotNull(server, "Server must not be null!");

    final PluginDataConfigurationMapper configuration = this.plugin.getConfiguration();
    final VncAllowList allowList = configuration.getVncAllowList();
    final VncAllowList.@Nullable Entry entry = allowList.find(server);
    if (entry == null) {
      final Component message = Message.VNC_NOT_ALLOWED.build(server);
      sender.sendMessage(message);
      return;
    }

    final Optional<Pair<Integer, Integer>> parsedResolution = parseDimensions(sender, vncResolution);
    final @Nullable Pair<Integer, Integer> resolution = parsedResolution.orElse(null);
    if (resolution == null) {
      return;
    }

    final Optional<Pair<Integer, Integer>> parsedBlocks = parseScreenDimensions(sender, blockDimensions);
    final @Nullable Pair<Integer, Integer> blocks = parsedBlocks.orElse(null);
    if (blocks == null) {
      return;
    }

    final VncSource source = this.createSource(entry, resolution, targetFramesPerSecond);
    final MapCodec chosen = this.chooseCodec(codec);
    final ScreenSettings settings = new ScreenSettings(sender, playerSelector, blocks, resolution, mapId, ditheringAlgorithm, chosen);
    final Screen screen = this.createScreen(settings);
    this.createResource(() -> this.connect(sender, screen, source, entry));
  }

  // only the source keeps the entry's password
  VncSource createSource(final VncAllowList.Entry entry, final Pair<Integer, Integer> resolution, final int targetFramesPerSecond) {
    final VncSourceBuilder builder = VncSource.builder();
    final String host = entry.getHost();
    builder.host(host);
    final int port = entry.getPort();
    builder.port(port);
    final Optional<String> password = entry.getPassword();
    password.ifPresent(builder::password);
    final int width = resolution.getFirst();
    builder.screenWidth(width);
    final int height = resolution.getSecond();
    builder.screenHeight(height);
    builder.targetFrameRate(targetFramesPerSecond);
    return builder.build();
  }

  private void connect(final CommandSender sender, final Screen screen, final VncSource source, final VncAllowList.Entry entry) {
    final VncPlayer desktop = VncPlayer.create();
    this.ownCreatedPlayer(desktop);

    final VideoAttachableCallback callback = desktop.getVideoAttachableCallback();
    final VideoPipelineStep pipeline = screen.getPipeline();
    callback.attach(pipeline);

    final Component loading = Message.VNC_LOADING.build();
    sender.sendMessage(loading);

    final ExecutorService executor = this.startExecutor(desktop, screen);
    final CompletableFuture<Boolean> start = desktop.startAsync(source, executor);
    final String host = entry.getHost();
    final int port = entry.getPort();
    final String server = VncAllowList.format(host, port);
    final String description = "the VNC desktop of %s".formatted(server);
    this.reportStartWhenDone(sender, desktop, screen, start, description);
  }

  /**
   * Creates the message that tells the sender whether the desktop streams. The reason of a failure is logged, not
   * shown, since it may contain details of the server's network.
   *
   * @param outcome whether the desktop streams
   * @param failure why it could not connect, if known
   * @return the message
   * @throws NullPointerException if {@code outcome} is null
   * @since 1.0.0
   */
  @Override
  protected Component createStartMessage(final StartOutcome outcome, final @Nullable Throwable failure) {
    Preconditions.checkNotNull(outcome, "Outcome must not be null!");

    final NullComponent message = outcome == StartOutcome.STARTED ? Message.VNC_CREATE : Message.VNC_ERROR;
    return message.build();
  }

  /**
   * Returns the permission a player needs to send input to the desktop.
   *
   * @return {@code mcav.command.vnc.interact}
   * @since 1.0.0
   */
  @Override
  protected String getInteractionPermission() {
    return INTERACT_PERMISSION;
  }
}
