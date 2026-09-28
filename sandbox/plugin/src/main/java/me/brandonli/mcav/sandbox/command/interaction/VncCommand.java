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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.sandbox.MCAVSandbox;
import me.brandonli.mcav.sandbox.data.PluginDataConfigurationMapper;
import me.brandonli.mcav.sandbox.locale.Message;
import me.brandonli.mcav.sandbox.utils.CleanupUtils;
import me.brandonli.mcav.sandbox.utils.DitheringArgument;
import me.brandonli.mcav.sandbox.utils.MapCodec;
import me.brandonli.mcav.utils.immutable.Pair;
import me.brandonli.mcav.utils.interaction.MouseClick;
import me.brandonli.mcav.vnc.VNCPlayer;
import me.brandonli.mcav.vnc.VNCSource;
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
 */
public final class VncCommand extends AbstractInteractiveCommand<VNCPlayer> {

  /**
   * The permission a player needs to send input to the desktop, by chat or by clicking the screen.
   */
  static final String INTERACT_PERMISSION = "mcav.vnc.interact";

  /**
   * Constructs the command.
   *
   * @param plugin the plugin
   */
  public VncCommand(final MCAVSandbox plugin) {
    super(plugin);
  }

  /**
   * Gets the permission a player needs to send input to the desktop.
   *
   * @return {@value #INTERACT_PERMISSION}
   */
  @Override
  protected String getInteractionPermission() {
    return INTERACT_PERMISSION;
  }

  /**
   * Clicks the desktop with the left mouse button.
   *
   * @param current the connected desktop
   * @param screenX the x coordinate on the desktop, in pixels
   * @param screenY the y coordinate on the desktop, in pixels
   */
  @Override
  protected void handleLeftClick(final VNCPlayer current, final int screenX, final int screenY) {
    Preconditions.checkNotNull(current, "Desktop must not be null");
    current.sendMouseEvent(MouseClick.LEFT, screenX, screenY);
  }

  /**
   * Clicks the desktop with the right mouse button.
   *
   * @param current the connected desktop
   * @param screenX the x coordinate on the desktop, in pixels
   * @param screenY the y coordinate on the desktop, in pixels
   */
  @Override
  protected void handleRightClick(final VNCPlayer current, final int screenX, final int screenY) {
    Preconditions.checkNotNull(current, "Desktop must not be null");
    current.sendMouseEvent(MouseClick.RIGHT, screenX, screenY);
  }

  /**
   * Types text on the desktop.
   *
   * @param current the connected desktop
   * @param text    the text to type
   */
  @Override
  protected void handleTextInput(final VNCPlayer current, final String text) {
    Preconditions.checkNotNull(current, "Desktop must not be null");
    Preconditions.checkNotNull(text, "Text must not be null");
    current.sendKeyEvent(text);
  }

  /**
   * Disconnects from a desktop.
   *
   * @param current the desktop to disconnect from, which is no longer the running one
   */
  @Override
  protected void releasePlayer(final VNCPlayer current) {
    Preconditions.checkNotNull(current, "Desktop must not be null");
    // closing the connection waits for the client's threads, which a stalled server can hold up
    CleanupUtils.runAll(() -> current.getVideoAttachableCallback().detach(), () -> this.releaseInTheBackground(current::release));
  }

  /**
   * Handles {@code /mcav vnc interact}: switches chat input to the desktop on or off for the player who runs it.
   *
   * <p>While it is on, the chat messages of the player are not sent to chat but typed on the desktop. Clicking the
   * screen does not need this mode: left and right clicks reach the desktop for every player with this permission.
   *
   * <p>Requires the permission {@code mcav.vnc.interact}. Only players can run it.
   *
   * @param sender the player who switches their chat input
   */
  @Command("mcav vnc interact")
  @Permission(INTERACT_PERMISSION)
  @CommandDescription("mcav.command.vnc.interact.info")
  public void toggleInteraction(final Player sender) {
    Preconditions.checkNotNull(sender, "Sender must not be null");
    final Component enabled = Message.INTERACT_ENABLE.build();
    final Component disabled = Message.INTERACT_DISABLE.build();
    this.toggleInteraction(sender, enabled, disabled);
  }

  /**
   * Handles {@code /mcav vnc release}: disconnects from the desktop, stops streaming to its maps, and clears the maps
   * for every viewer. The wall stays in place.
   *
   * <p>Requires the permission {@code mcav.vnc.release}; players and the console can run it.
   *
   * @param sender who ran the command
   */
  @Command("mcav vnc release")
  @Permission("mcav.vnc.release")
  @CommandDescription("mcav.command.vnc.release.info")
  public void releaseVnc(final CommandSender sender) {
    Preconditions.checkNotNull(sender, "Sender must not be null");
    final Component released = Message.VNC_RELEASE.build();
    this.releaseResource(sender, released);
  }

  /**
   * Handles {@code /mcav vnc create <playerSelector> <vncResolution> <targetFps> <blockDimensions> <mapId>
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
   * @param sender             who ran the command
   * @param playerSelector     the players who see the desktop on the maps, such as a player name or {@code @a}
   * @param vncResolution      the size the desktop is scaled to as {@code <width>x<height>} in pixels, such as
   *                           {@code 1280x720}; use 128 times the block dimensions to fill the wall exactly
   * @param targetFps          how many frames per second the desktop is streamed at, from 1 to 240
   * @param blockDimensions    the size of the wall as {@code <width>x<height>} in maps, such as {@code 5x5}
   * @param mapId              the id of the top left map of the wall, as given to {@code /mcav screen}
   * @param ditheringAlgorithm how colors are reduced to the map palette; {@code NEAREST_COLOR} keeps text sharp, see
   *                           {@link DitheringArgument}
   * @param server             the VNC server as {@code host:port}, or {@code [address]:port} for an IPv6 address, as
   *                           listed in {@code vnc.allowed-hosts}; the rest of the line up to a flag, since a quoted
   *                           argument would refuse the colon unquoted
   * @param codec              how the picture reaches the players, see {@link MapCodec}; the configured default when
   *                           absent
   */
  @Command("mcav vnc create <playerSelector> <vncResolution> <targetFps> <blockDimensions> <mapId> <ditheringAlgorithm> <server>")
  @Permission("mcav.command.vnc.create")
  @CommandDescription("mcav.command.vnc.create.info")
  public void createVnc(
    final CommandSender sender,
    final MultiplePlayerSelector playerSelector,
    @Argument(suggestions = "resolutions") @Quoted final String vncResolution,
    @Argument(suggestions = "target-fps") @Range(min = "1", max = "240") final int targetFps,
    @Argument(suggestions = "dimensions") @Quoted final String blockDimensions,
    @Argument(suggestions = "ids") @Range(min = "0") final int mapId,
    final DitheringArgument ditheringAlgorithm,
    @Greedy @FlagYielding final String server,
    @Flag("codec") final @Nullable MapCodec codec
  ) {
    Preconditions.checkNotNull(playerSelector, "Player selector must not be null");
    Preconditions.checkNotNull(ditheringAlgorithm, "Dithering algorithm must not be null");
    Preconditions.checkNotNull(server, "Server must not be null");

    final PluginDataConfigurationMapper configuration = this.plugin.getConfiguration();
    final VncAllowList allowList = configuration.getVncAllowList();
    final VncAllowList.Entry entry = allowList.find(server);
    if (entry == null) {
      sender.sendMessage(Message.VNC_NOT_ALLOWED.build(server));
      return;
    }
    final Pair<Integer, Integer> resolution = parseDimensions(sender, vncResolution);
    final Pair<Integer, Integer> blocks = resolution == null ? null : parseScreenDimensions(sender, blockDimensions);
    if (resolution == null || blocks == null) {
      return;
    }

    final VNCSource source = createSource(entry, resolution, targetFps);
    final MapCodec chosen = this.chooseCodec(codec);
    final ScreenSettings settings = new ScreenSettings(sender, playerSelector, blocks, resolution, mapId, ditheringAlgorithm, chosen);
    final Screen screen = this.createScreen(settings);
    this.createResource(() -> this.connect(sender, screen, source, entry));
  }

  /**
   * The source of a desktop. The password is the one of the server's entry, which only the source keeps.
   *
   * @param entry      the allowed server
   * @param resolution the size the desktop is scaled to
   * @param targetFps  the frames per second
   * @return the source
   */
  static VNCSource createSource(final VncAllowList.Entry entry, final Pair<Integer, Integer> resolution, final int targetFps) {
    final VNCSource.Builder builder = VNCSource.builder();
    builder.host(entry.host());
    builder.port(entry.port());
    final String password = entry.password();
    if (password != null) {
      builder.password(password);
    }
    builder.screenWidth(resolution.getFirst());
    builder.screenHeight(resolution.getSecond());
    builder.targetFrameRate(targetFps);
    return builder.build();
  }

  private void connect(final CommandSender sender, final Screen screen, final VNCSource source, final VncAllowList.Entry entry) {
    final VNCPlayer desktop = VNCPlayer.create();
    this.ownCreatedPlayer(desktop);
    final VideoAttachableCallback callback = desktop.getVideoAttachableCallback();
    final VideoPipelineStep pipeline = screen.getPipeline();
    callback.attach(pipeline);

    sender.sendMessage(Message.VNC_LOADING.build());
    final ExecutorService executor = this.startExecutor(desktop, screen);
    final CompletableFuture<Boolean> start = desktop.startAsync(source, executor);
    // the entry names the server by host and port only
    this.reportStartWhenDone(sender, desktop, screen, start, "the VNC desktop of " + entry);
  }

  /**
   * Creates the message that tells the sender whether the desktop streams. The reason of a failure is logged, not
   * shown, since it may contain details of the server's network.
   *
   * @param success whether the desktop streams
   * @param error   why it could not connect, if known
   * @return the message
   */
  @Override
  protected Component createStartMessage(final boolean success, final @Nullable Throwable error) {
    return success ? Message.VNC_CREATE.build() : Message.VNC_ERROR.build();
  }
}
