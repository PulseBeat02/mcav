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
package me.brandonli.mcav.sandbox.command.video;

import com.google.common.base.Preconditions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Channel;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Configuration;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Viewers;
import me.brandonli.mcav.media.mcv2.FrameParser;
import me.brandonli.mcav.media.mcv2.Mcv2Exception;
import me.brandonli.mcav.media.mcv2.Mcv2Frame;
import me.brandonli.mcav.sandbox.MCAVSandbox;
import me.brandonli.mcav.sandbox.command.AnnotationCommandFeature;
import me.brandonli.mcav.sandbox.locale.Message;
import me.brandonli.mcav.sandbox.utils.ArgumentUtils;
import me.brandonli.mcav.utils.immutable.Pair;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.incendo.cloud.annotation.specifier.Quoted;
import org.incendo.cloud.annotation.specifier.Range;
import org.incendo.cloud.annotations.Argument;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.CommandDescription;
import org.incendo.cloud.annotations.Permission;
import org.incendo.cloud.bukkit.data.MultiplePlayerSelector;

/**
 * {@code /mcav mcv2 play} and {@code /mcav mcv2 stop}: plays a stream that is already MCV2-encoded on a wall of maps,
 * looping, without a video player or an encoder. It shows streams made offline, such as the encoder's own conformance
 * streams, and is how the pack is tested in-game.
 */
public final class Mcv2PlayCommand implements AnnotationCommandFeature {

  private final MCAVSandbox plugin;
  private @Nullable BukkitTask task;
  private @Nullable Mcv2Channel channel;

  /**
   * Constructs the command.
   *
   * @param plugin the plugin
   */
  public Mcv2PlayCommand(final MCAVSandbox plugin) {
    Preconditions.checkNotNull(plugin, "Plugin must not be null");
    this.plugin = plugin;
  }

  /**
   * Handles {@code /mcav mcv2 play <playerSelector> <blockDimensions> <mapId> <ticks> <file>}: plays an MCV2 stream,
   * a file of frames each preceded by its length as a little-endian 32-bit number, one frame every {@code ticks}
   * server ticks, on the wall built with {@code /mcav screen}. The players are sent the MCV2 pack first; playback
   * starts over from the first frame whenever a player starts watching. Replaces the stream played before.
   *
   * <p>Requires the permission {@code mcav.command.mcv2.play}.
   *
   * @param sender          who ran the command
   * @param playerSelector  the players who watch
   * @param blockDimensions the size of the wall as {@code <width>x<height>} in maps
   * @param mapId           the id of the top-left map of the wall
   * @param ticks           the server ticks between frames, 1 or more
   * @param file            the path of the stream on the server
   */
  @Command("mcav mcv2 play <playerSelector> <blockDimensions> <mapId> <ticks> <file>")
  @Permission("mcav.command.mcv2.play")
  @CommandDescription("mcav.command.mcv2.play.info")
  public void play(
    final CommandSender sender,
    final MultiplePlayerSelector playerSelector,
    @Argument(suggestions = "dimensions") @Quoted final String blockDimensions,
    @Argument(suggestions = "ids") @Range(min = "0") final int mapId,
    @Range(min = "1") final int ticks,
    @Quoted final String file
  ) {
    final Pair<Integer, Integer> blocks = AbstractVideoCommand.parseScreenDimensions(sender, blockDimensions);
    if (blocks == null) {
      return;
    }
    final List<byte[]> frames;
    final Mcv2Frame first;
    try {
      frames = read(Path.of(file));
      first = FrameParser.parse(frames.getFirst());
    } catch (final IOException | Mcv2Exception | RuntimeException exception) {
      sender.sendMessage(Message.MCV2_FILE_ERROR.build(String.valueOf(exception.getMessage())));
      return;
    }
    final Pair<Integer, Integer> resolution = Pair.pair(first.getWidth(), first.getHeight());
    final Mcv2Configuration configuration = VideoMcv2Command.configure(
      sender,
      blocks,
      resolution,
      mapId,
      Mcv2Profile.SHIP,
      ArgumentUtils.parsePlayerSelectors(playerSelector)
    );
    if (configuration == null) {
      return;
    }
    this.stop();
    final List<Player> players = List.copyOf(playerSelector.values());
    final Mcv2Viewers viewers = this.plugin.getMcv2Support().offer(configuration, players);
    final Mcv2Channel opened = new Mcv2Channel(configuration, viewers);
    opened.open();
    this.channel = opened;
    this.task = Bukkit.getScheduler().runTaskTimerAsynchronously(this.plugin, new Mcv2Playback(opened, frames), 0, ticks);
    sender.sendMessage(Message.MCV2_PLAY.build());
  }

  /**
   * Handles {@code /mcav mcv2 stop}: stops the stream and removes the screen's page frames.
   *
   * @param sender who ran the command
   */
  @Command("mcav mcv2 stop")
  @Permission("mcav.command.mcv2.play")
  @CommandDescription("mcav.command.mcv2.stop.info")
  public void stop(final CommandSender sender) {
    this.stop();
    sender.sendMessage(Message.MCV2_STOP.build());
  }

  /** Stops the stream played before, if any. Call on the main thread. */
  void stop() {
    final BukkitTask running = this.task;
    if (running != null) {
      running.cancel();
      this.task = null;
    }
    final Mcv2Channel opened = this.channel;
    if (opened != null) {
      opened.close();
      this.channel = null;
    }
    this.plugin.getMcv2Support().close();
  }

  /**
   * Reads a stream: frames, each preceded by its length as a little-endian 32-bit number.
   *
   * @param file the stream
   * @return the frames
   * @throws IOException if the file cannot be read or is cut off
   */
  static List<byte[]> read(final Path file) throws IOException {
    final byte[] data = Files.readAllBytes(file);
    final List<byte[]> frames = new ArrayList<>();
    int offset = 0;
    while (offset < data.length) {
      if (data.length - offset < 4) {
        throw new IOException("Truncated frame length");
      }
      final long length =
        (data[offset] & 0xFFL) |
        ((data[offset + 1] & 0xFFL) << 8) |
        ((data[offset + 2] & 0xFFL) << 16) |
        ((data[offset + 3] & 0xFFL) << 24);
      if (length > data.length - offset - 4) {
        throw new IOException("Truncated frame");
      }
      final byte[] frame = new byte[(int) length];
      System.arraycopy(data, offset + 4, frame, 0, frame.length);
      frames.add(frame);
      offset += 4 + frame.length;
    }
    if (frames.isEmpty()) {
      throw new IOException("Empty stream");
    }
    return frames;
  }
}
