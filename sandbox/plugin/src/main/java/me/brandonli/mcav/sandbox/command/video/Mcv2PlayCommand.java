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
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Channel;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Configuration;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Viewers;
import me.brandonli.mcav.media.mcv2.FrameParser;
import me.brandonli.mcav.media.mcv2.Mcv2Exception;
import me.brandonli.mcav.media.mcv2.Mcv2Format;
import me.brandonli.mcav.media.mcv2.Mcv2Frame;
import me.brandonli.mcav.media.mcv2.encode.EncoderPool;
import me.brandonli.mcav.media.mcv2.encode.Mcv2FileEncoder;
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
 * {@code /mcav mcv2 encode} makes such a stream from a video file ahead of time, the path for a server too small to
 * encode while a video plays, and {@code /mcav mcv2 cancel} stops it.
 *
 * <p>Stream files are kept in the {@code mcv2} folder of the plugin's data folder: a command names a file inside it,
 * and a name that leads out of it, absolute or climbing with {@code ..}, is refused, so a command can neither read nor
 * replace other files of the server.
 */
public final class Mcv2PlayCommand implements AnnotationCommandFeature {

  /** How often a running file encode tells its progress. */
  private static final long PROGRESS_NANOS = TimeUnit.SECONDS.toNanos(30);

  /** The folder of the stream files, in the plugin's data folder. */
  static final String STREAMS = "mcv2";

  /** The largest stream file played, one gibibyte: a stream is held in memory while it plays. */
  private static final long MAX_STREAM_BYTES = 1L << 30;

  private static final double NANOS_PER_MILLISECOND = 1e6;

  private static final double NANOS_PER_SECOND = 1e9;

  /** A stream file frames each frame with its length, a little-endian 32-bit word. */
  private static final int LENGTH_BYTES = Integer.BYTES;

  private final MCAVSandbox plugin;

  private @Nullable BukkitTask task;

  private @Nullable ScheduledExecutorService streamer;

  private @Nullable ScheduledFuture<?> streaming;

  private @Nullable Mcv2Channel channel;

  private @Nullable Thread encoding;

  private Opener opener = Mcv2FileEncoder::ffmpeg;

  /** Opens a video file's frames at a size. */
  @FunctionalInterface
  interface Opener {
    /**
     * Opens a video file.
     *
     * @param video  the file
     * @param width  the frame width
     * @param height the frame height
     * @return the frames
     * @throws IOException if the file cannot be opened
     */
    Mcv2FileEncoder.FrameReader open(Path video, int width, int height) throws IOException;
  }

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
   * @param file            the stream file, in the plugin's {@code mcv2} folder
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
   * @param file            the stream file, in the plugin's {@code mcv2} folder
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
      frames = read(this.streamFile(file));
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
   * Handles {@code /mcav mcv2 encode <file> <output> <resolution> <profile>}: encodes a video file into an MCV2 stream
   * file ahead of time, which {@code /mcav mcv2 play} and {@code stream} play. The frames are encoded one after
   * another on a thread of its own, inside the encoder budget every MCV2 screen of the server shares
   * ({@code mcv2.encoder-threads} in {@code config.yml}), so the server keeps ticking and the screens keep their share;
   * the sender is told the progress every thirty seconds and the result at the end. One encode runs at a time.
   *
   * <p>Requires the permission {@code mcav.command.mcv2.encode}.
   *
   * @param sender     who ran the command
   * @param file       the path of the video file on the server
   * @param output     the stream file to write, in the plugin's {@code mcv2} folder, replaced when it exists
   * @param resolution the video size as {@code <width>x<height>}
   * @param profile    the encoder profile, usually {@code ship}
   */
  @Command("mcav mcv2 encode <file> <output> <resolution> <profile>")
  @Permission("mcav.command.mcv2.encode")
  @CommandDescription("mcav.command.mcv2.encode.info")
  public void encode(
    final CommandSender sender,
    @Quoted final String file,
    @Quoted final String output,
    @Argument(suggestions = "resolutions") @Quoted final String resolution,
    final Mcv2Profile profile
  ) {
    final Pair<Integer, Integer> size = AbstractVideoCommand.parseDimensions(sender, resolution);
    if (size == null) {
      return;
    }
    final int width = size.getFirst();
    final int height = size.getSecond();
    final Path source = Path.of(file);
    final Path target;
    try {
      target = this.streamFile(output);
    } catch (final IOException exception) {
      sender.sendMessage(Message.MCV2_ENCODE_ERROR.build(String.valueOf(exception.getMessage())));
      return;
    }
    final EncoderPool budget = EncoderPool.shared();
    final Opener open = this.opener;
    synchronized (this) {
      final Thread running = this.encoding;
      if (running != null && running.isAlive()) {
        sender.sendMessage(Message.MCV2_ENCODE_BUSY.build());
        return;
      }
      sender.sendMessage(
        Message.MCV2_ENCODE_START.build(
          "%s into %s at %dx%d with the %s profile, on %d encoder threads".formatted(
              source,
              target,
              width,
              height,
              profile.name().toLowerCase(Locale.ROOT),
              budget.getThreads()
            )
        )
      );
      this.encoding = Thread.ofPlatform()
        .daemon()
        .name("mcav-mcv2-file-encode")
        .start(() -> encodeFile(sender, open, source, target, width, height, profile, budget, PROGRESS_NANOS));
    }
  }

  /**
   * Encodes a video file into a stream file, first into a file next to it that replaces it when the encode is done,
   * and tells the sender how it goes.
   *
   * @param sender   who is told
   * @param opener   opens the video file
   * @param source   the video file
   * @param target   the stream file
   * @param width    the video width
   * @param height   the video height
   * @param profile  the encoder profile
   * @param budget   the encoder budget
   * @param interval how often the progress is told, in nanoseconds
   */
  static void encodeFile(
    final CommandSender sender,
    final Opener opener,
    final Path source,
    final Path target,
    final int width,
    final int height,
    final Mcv2Profile profile,
    final EncoderPool budget,
    final long interval
  ) {
    final Path partial = target.resolveSibling(target.getFileName() + ".part");
    final long started = System.nanoTime();
    final long[] reported = { started };
    final Mcv2FileEncoder.Result result;
    try {
      try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(partial))) {
        result = Mcv2FileEncoder.encode(opener.open(source, width, height), width, height, profile.getSettings(), budget, out, frames -> {
          final long now = System.nanoTime();
          if (now - reported[0] >= interval) {
            reported[0] = now;
            sender.sendMessage(
              Message.MCV2_ENCODE_PROGRESS.build(
                String.format(Locale.ROOT, "%d frames, %.0f ms per frame", frames, (now - started) / NANOS_PER_MILLISECOND / frames)
              )
            );
          }
        });
      }
      Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
    } catch (final IOException exception) {
      deleteQuietly(partial);
      sender.sendMessage(Message.MCV2_ENCODE_ERROR.build(String.valueOf(exception.getMessage())));
      return;
    } catch (final InterruptedException exception) {
      deleteQuietly(partial);
      sender.sendMessage(Message.MCV2_ENCODE_CANCELLED.build());
      return;
    }
    sender.sendMessage(
      Message.MCV2_ENCODE_DONE.build(
        String.format(
          Locale.ROOT,
          "%d frames (%d keyframes) into %s in %.0f s, %.0f ms per frame",
          result.frames(),
          result.keyframes(),
          target,
          (System.nanoTime() - started) / NANOS_PER_SECOND,
          result.millisecondsPerFrame()
        )
      )
    );
  }

  /**
   * Resolves a stream file a command names inside the plugin's {@code mcv2} folder, which is created when missing.
   *
   * @param name the file name, or a path relative to the folder
   * @return the file
   * @throws IOException if the name leads out of the folder or the folder cannot be created
   */
  Path streamFile(final String name) throws IOException {
    final Path folder = this.plugin.getDataFolder().toPath().resolve(STREAMS).toAbsolutePath().normalize();
    final Path file = folder.resolve(name).normalize();
    if (!file.startsWith(folder) || file.equals(folder)) {
      throw new IOException("Stream files are kept in " + folder + ", and " + name + " is not a file in it");
    }
    Files.createDirectories(folder);
    return file;
  }

  private static void deleteQuietly(final Path file) {
    try {
      Files.deleteIfExists(file);
    } catch (final IOException exception) {
      // what is left of an encode that stopped is overwritten by the next
    }
  }

  /**
   * Sets how video files are opened, instead of FFmpeg.
   *
   * @param replacement the opener
   */
  void setOpener(final Opener replacement) {
    this.opener = replacement;
  }

  /**
   * Gets the thread of the file encode started last.
   *
   * @return the thread, or null if none was started or it was cancelled
   */
  synchronized @Nullable Thread getEncoding() {
    return this.encoding;
  }

  /**
   * Handles {@code /mcav mcv2 cancel}: stops the file encode that is running.
   *
   * <p>Requires the permission {@code mcav.command.mcv2.encode}.
   *
   * @param sender who ran the command
   */
  @Command("mcav mcv2 cancel")
  @Permission("mcav.command.mcv2.encode")
  @CommandDescription("mcav.command.mcv2.cancel.info")
  public void cancel(final CommandSender sender) {
    final Thread running;
    synchronized (this) {
      running = this.encoding;
      this.encoding = null;
    }
    if (running == null || !running.isAlive()) {
      sender.sendMessage(Message.MCV2_ENCODE_NONE.build());
      return;
    }
    // the encode tells its sender that it stopped
    running.interrupt();
  }

  /**
   * Stops the stream and the file encode when the plugin is disabled.
   */
  @Override
  public void shutdown() {
    this.stop();
    final Thread running;
    synchronized (this) {
      running = this.encoding;
      this.encoding = null;
    }
    if (running != null) {
      running.interrupt();
    }
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
   * @throws IOException if the file is not a regular file of at most {@link #MAX_STREAM_BYTES}, cannot be read or is
   *                     cut off
   */
  static List<byte[]> read(final Path file) throws IOException {
    return read(file, MAX_STREAM_BYTES);
  }

  /**
   * Reads a stream of at most some size. Only a regular file is read, and its size is checked first: a device such as
   * {@code /dev/zero} would never end, and a huge file would fill the memory.
   *
   * @param file  the stream
   * @param limit the most bytes read
   * @return the frames
   * @throws IOException if the file is not a regular file of at most the limit, cannot be read or is cut off
   */
  static List<byte[]> read(final Path file, final long limit) throws IOException {
    if (!Files.isRegularFile(file)) {
      throw new IOException("Not a stream file: " + file.getFileName());
    }
    if (Files.size(file) > limit) {
      throw new IOException("The stream file is larger than " + limit + " bytes");
    }
    final byte[] data = Files.readAllBytes(file);
    final List<byte[]> frames = new ArrayList<>();
    int offset = 0;
    while (offset < data.length) {
      if (data.length - offset < LENGTH_BYTES) {
        throw new IOException("Truncated frame length");
      }
      final long length = Mcv2Format.u32(data, offset);
      if (length > data.length - offset - LENGTH_BYTES) {
        throw new IOException("Truncated frame");
      }
      final byte[] frame = new byte[(int) length];
      System.arraycopy(data, offset + LENGTH_BYTES, frame, 0, frame.length);
      frames.add(frame);
      offset += LENGTH_BYTES + frame.length;
    }
    if (frames.isEmpty()) {
      throw new IOException("Empty stream");
    }
    return frames;
  }
}
