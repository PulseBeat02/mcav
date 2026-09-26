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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
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
 * {@code /mcav mcv2 play}, {@code /mcav mcv2 stream} and {@code /mcav mcv2 stop}: plays a stream that is already
 * MCV2-encoded on a wall of maps, looping, without a video player or an encoder. It shows streams made offline, such as
 * the encoder's own conformance streams, and is how the pack and the transport are tested in-game: {@code play} sends a
 * frame every few server ticks, {@code stream} at a frame rate on its own thread, off the server's 20 ticks a second.
 */
public final class Mcv2PlayCommand implements AnnotationCommandFeature {

  private final MCAVSandbox plugin;
  private @Nullable BukkitTask task;
  private @Nullable ScheduledExecutorService streamer;
  private @Nullable ScheduledFuture<?> streaming;
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
    final Mcv2Playback playback = this.open(sender, playerSelector, blockDimensions, mapId, file);
    if (playback == null) {
      return;
    }
    this.task = Bukkit.getScheduler().runTaskTimerAsynchronously(this.plugin, playback, 0, ticks);
    sender.sendMessage(Message.MCV2_PLAY.build());
  }

  /**
   * Handles {@code /mcav mcv2 stream <playerSelector> <blockDimensions> <mapId> <fps> <file>}: plays an MCV2 stream
   * like {@code /mcav mcv2 play}, but at a frame rate, on its own thread: a server runs 20 ticks a second, and a stream
   * of 30 or 60 frames a second is sent between them, the way the encoder's frames are. Replaces the stream played
   * before.
   *
   * <p>Requires the permission {@code mcav.command.mcv2.play}.
   *
   * @param sender          who ran the command
   * @param playerSelector  the players who watch
   * @param blockDimensions the size of the wall as {@code <width>x<height>} in maps
   * @param mapId           the id of the top-left map of the wall
   * @param fps             the frames sent per second, 1 to 240
   * @param file            the path of the stream on the server
   */
  @Command("mcav mcv2 stream <playerSelector> <blockDimensions> <mapId> <fps> <file>")
  @Permission("mcav.command.mcv2.play")
  @CommandDescription("mcav.command.mcv2.stream.info")
  public void stream(
    final CommandSender sender,
    final MultiplePlayerSelector playerSelector,
    @Argument(suggestions = "dimensions") @Quoted final String blockDimensions,
    @Argument(suggestions = "ids") @Range(min = "0") final int mapId,
    @Range(min = "1", max = "240") final int fps,
    @Quoted final String file
  ) {
    final Mcv2Playback playback = this.open(sender, playerSelector, blockDimensions, mapId, file);
    if (playback == null) {
      return;
    }
    final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
      Thread.ofPlatform().daemon().name("mcav-mcv2-stream").factory()
    );
    this.streaming = executor.scheduleAtFixedRate(playback, 0, TimeUnit.SECONDS.toNanos(1) / fps, TimeUnit.NANOSECONDS);
    this.streamer = executor;
    sender.sendMessage(Message.MCV2_PLAY.build());
  }

  /**
   * Opens a screen for a stream: reads it, stops the stream played before, offers the players the pack and opens the
   * channel.
   *
   * @return the playback to schedule, or null when the wall, the file or the stream is wrong, which is reported
   */
  private @Nullable Mcv2Playback open(
    final CommandSender sender,
    final MultiplePlayerSelector playerSelector,
    final String blockDimensions,
    final int mapId,
    final String file
  ) {
    final Pair<Integer, Integer> blocks = AbstractVideoCommand.parseScreenDimensions(sender, blockDimensions);
    if (blocks == null) {
      return null;
    }
    final List<byte[]> frames;
    final Mcv2Frame first;
    try {
      frames = read(Path.of(file));
      first = FrameParser.parse(frames.getFirst());
    } catch (final IOException | Mcv2Exception | RuntimeException exception) {
      sender.sendMessage(Message.MCV2_FILE_ERROR.build(String.valueOf(exception.getMessage())));
      return null;
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
      return null;
    }
    this.stop();
    final List<Player> players = List.copyOf(playerSelector.values());
    final Mcv2Viewers viewers = this.plugin.getMcv2Support().offer(configuration, players);
    final Mcv2Channel opened = new Mcv2Channel(configuration, viewers);
    opened.open();
    this.channel = opened;
    return new Mcv2Playback(opened, frames);
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

  /**
   * Stops the stream played before, if any. The pack stays served and loaded, so the players' clients do not reload
   * their resources when the next stream on the same screen starts. Call on the main thread.
   */
  void stop() {
    final BukkitTask running = this.task;
    if (running != null) {
      running.cancel();
      this.task = null;
    }
    final ScheduledFuture<?> schedule = this.streaming;
    if (schedule != null) {
      schedule.cancel(true);
      this.streaming = null;
    }
    final ScheduledExecutorService executor = this.streamer;
    if (executor != null) {
      executor.shutdownNow();
      this.streamer = null;
    }
    final Mcv2Channel opened = this.channel;
    if (opened != null) {
      opened.close();
      this.channel = null;
    }
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
