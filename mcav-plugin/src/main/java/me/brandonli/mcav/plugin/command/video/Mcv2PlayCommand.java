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
package me.brandonli.mcav.plugin.command.video;

import com.google.common.base.Preconditions;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2.Pool;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2.Settings;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Channel;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Configuration;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.Frame;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2FileEncoder;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2PackServer;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Viewers;
import me.brandonli.mcav.plugin.MCAVSandbox;
import me.brandonli.mcav.plugin.command.AnnotationCommandFeature;
import me.brandonli.mcav.plugin.command.SuggestionProvider;
import me.brandonli.mcav.plugin.listener.OnlinePlayers;
import me.brandonli.mcav.plugin.locale.Message;
import me.brandonli.mcav.plugin.utils.ArgumentUtils;
import me.brandonli.mcav.plugin.utils.TaskUtils;
import me.brandonli.mcav.utils.immutable.Pair;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.scheduler.BukkitScheduler;
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
 * Both read the stream file on a thread of their own, as a file of up to a gibibyte, or a busy disk, would hold the
 * server up for seconds, and open the screen on the main thread once it is read.
 * {@code /mcav mcv2 encode} makes such a stream from a video file ahead of time, the path for a server too small to
 * encode while a video plays, and {@code /mcav mcv2 cancel} stops it.
 *
 * <p>Stream files are kept in the {@code mcv2} folder of the plugin's data folder: a command names a file inside it,
 * and a name that leads out of it, absolute or climbing with {@code ..}, is refused, so a command can neither read nor
 * replace other files of the server.
 *
 * <p>This class is not thread-safe: use it on the server main thread; stream files are read on a thread of their own, a
 * stream plays on its own thread and an encode on another.
 *
 * @since 1.0.0
 */
public final class Mcv2PlayCommand implements AnnotationCommandFeature {

  private static final long PROGRESS_INTERVAL_NANOSECONDS = TimeUnit.SECONDS.toNanos(30);

  static final String STREAM_FOLDER = "mcv2";

  // a stream is held in memory while it plays
  private static final long MAXIMUM_STREAM_BYTES = 1L << 30;

  private static final double NANOSECONDS_PER_MILLISECOND = 1e6;
  private static final double NANOSECONDS_PER_SECOND = 1e9;

  private static final int LENGTH_BYTES = Integer.BYTES;

  private static final String PARTIAL_SUFFIX = ".part";

  private static final String STREAM_READ_THREAD_NAME = "mcav-mcv2-stream-read";
  private static final String STREAM_THREAD_NAME = "mcav-mcv2-stream";
  private static final String FILE_ENCODE_THREAD_NAME = "mcav-mcv2-file-encode";

  private static final boolean INTERRUPT_IF_RUNNING = true;

  private final MCAVSandbox plugin;

  // one thread, so quick repeated commands hold one file at a time
  private final ExecutorService reads;

  private @Nullable BukkitTask task;

  private @Nullable ScheduledExecutorService streamer;

  private @Nullable ScheduledFuture<?> streaming;

  private @Nullable Mcv2Playback playback;

  private @Nullable Mcv2Channel channel;

  private Mcv2PackServer.@Nullable Lease lease;

  private @Nullable FileEncode encoding;

  // a load is dropped once a later play, stream or stop command was given; main thread only
  private long loads;

  private Opener opener;

  /**
   * Creates the command.
   *
   * @param plugin the plugin
   * @throws NullPointerException if {@code plugin} is null
   * @since 1.0.0
   */
  public Mcv2PlayCommand(final MCAVSandbox plugin) {
    Preconditions.checkNotNull(plugin, "Plugin must not be null!");

    this.plugin = plugin;
    this.opener = Mcv2FileEncoder::ffmpeg;
    this.reads = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name(STREAM_READ_THREAD_NAME).factory());
  }

  /**
   * Stops the stream, the reading of a stream file and the file encode when the plugin is disabled.
   *
   * @since 1.0.0
   */
  @Override
  public void shutdown() {
    this.loads++;
    this.reads.shutdownNow();
    this.stop();

    final @Nullable FileEncode running;
    synchronized (this) {
      running = this.encoding;
      this.encoding = null;
    }

    if (running != null) {
      running.stop();
    }
  }

  /**
   * Handles {@code /mcav mcv2 play <playerSelector> <blockDimensions> <mapId> <ticks> <file>}: plays an MCV2 stream,
   * a file of frames each preceded by its length as a little-endian 32-bit number, one frame every {@code ticks}
   * server ticks, on the wall built with {@code /mcav screen}. The players are sent the MCV2 pack first; playback
   * starts over from the first frame whenever a player starts watching. Replaces the stream played before. The file is
   * read off the main thread, and the screen opens once it is read, unless another play, stream or stop command came
   * in the meantime.
   *
   * <p>Requires the permission {@code mcav.command.mcv2.play}.
   *
   * @param sender          who ran the command
   * @param playerSelector  the players who watch
   * @param blockDimensions the size of the wall as {@code <width>x<height>} in maps
   * @param mapId           the id of the top-left map of the wall
   * @param ticks           the server ticks between frames, 1 or more
   * @param file            the stream file, in the plugin's {@code mcv2} folder
   * @since 1.0.0
   */
  @Command("mcav mcv2 play <playerSelector> <blockDimensions> <mapId> <ticks> <file>")
  @Permission("mcav.command.mcv2.play")
  @CommandDescription("mcav.command.mcv2.play.info")
  public void play(
    final CommandSender sender,
    final MultiplePlayerSelector playerSelector,
    @Argument(suggestions = SuggestionProvider.DIMENSIONS) @Quoted final String blockDimensions,
    @Argument(suggestions = SuggestionProvider.IDS) @Range(min = "0") final int mapId,
    @Range(min = "1") final int ticks,
    @Quoted final String file
  ) {
    this.load(sender, playerSelector, blockDimensions, mapId, file, playback -> this.startTask(sender, ticks, playback));
  }

  private void startTask(final CommandSender sender, final int ticks, final Mcv2Playback playback) {
    final BukkitScheduler scheduler = Bukkit.getScheduler();
    this.task = scheduler.runTaskTimerAsynchronously(this.plugin, playback, 0, ticks);
    final Component message = Message.MCV2_PLAY.build();
    sender.sendMessage(message);
  }

  /**
   * Handles {@code /mcav mcv2 stream <playerSelector> <blockDimensions> <mapId> <framesPerSecond> <file>}: plays an MCV2 stream
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
   * @param framesPerSecond the frames sent per second, 1 to 240
   * @param file            the stream file, in the plugin's {@code mcv2} folder
   * @since 1.0.0
   */
  @Command("mcav mcv2 stream <playerSelector> <blockDimensions> <mapId> <framesPerSecond> <file>")
  @Permission("mcav.command.mcv2.play")
  @CommandDescription("mcav.command.mcv2.stream.info")
  public void stream(
    final CommandSender sender,
    final MultiplePlayerSelector playerSelector,
    @Argument(suggestions = SuggestionProvider.DIMENSIONS) @Quoted final String blockDimensions,
    @Argument(suggestions = SuggestionProvider.IDS) @Range(min = "0") final int mapId,
    @Range(min = "1", max = "240") final int framesPerSecond,
    @Quoted final String file
  ) {
    this.load(sender, playerSelector, blockDimensions, mapId, file, playback -> this.startStream(sender, framesPerSecond, playback));
  }

  private void startStream(final CommandSender sender, final int framesPerSecond, final Mcv2Playback playback) {
    final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
      Thread.ofPlatform().daemon().name(STREAM_THREAD_NAME).factory()
    );
    this.streaming = executor.scheduleAtFixedRate(playback, 0, TimeUnit.SECONDS.toNanos(1) / framesPerSecond, TimeUnit.NANOSECONDS);
    this.streamer = executor;
    final Component message = Message.MCV2_PLAY.build();
    sender.sendMessage(message);
  }

  private void load(
    final CommandSender sender,
    final MultiplePlayerSelector playerSelector,
    final String blockDimensions,
    final int mapId,
    final String file,
    final Consumer<Mcv2Playback> start
  ) {
    final Optional<Pair<Integer, Integer>> parsedBlocks = AbstractVideoCommand.parseScreenDimensions(sender, blockDimensions);
    final @Nullable Pair<Integer, Integer> blocks = parsedBlocks.orElse(null);
    if (blocks == null) {
      return;
    }

    this.loads++;
    final long load = this.loads;
    this.reads.execute(() ->
      TaskUtils.runOnMainThread(this.plugin, this.readStream(load, sender, playerSelector, blocks, mapId, file, start))
    );
  }

  private Runnable readStream(
    final long load,
    final CommandSender sender,
    final MultiplePlayerSelector playerSelector,
    final Pair<Integer, Integer> blocks,
    final int mapId,
    final String file,
    final Consumer<Mcv2Playback> start
  ) {
    try {
      final Path streamFile = this.prepareStreamFile(file);
      final List<byte[]> frames = this.read(streamFile);
      final byte[] firstFrame = frames.getFirst();
      final Frame first = Mcv2Decoder.parse(firstFrame);
      return () -> this.openIfCurrent(load, sender, playerSelector, blocks, mapId, frames, first, start);
    } catch (final IOException | RuntimeException exception) {
      final @Nullable String detail = exception.getMessage();
      final String reason = String.valueOf(detail);
      final Component message = Message.MCV2_FILE_ERROR.build(reason);
      return () -> this.reportIfCurrent(load, sender, message);
    }
  }

  private void reportIfCurrent(final long load, final CommandSender sender, final Component message) {
    if (load == this.loads) {
      sender.sendMessage(message);
    }
  }

  private void openIfCurrent(
    final long load,
    final CommandSender sender,
    final MultiplePlayerSelector playerSelector,
    final Pair<Integer, Integer> blocks,
    final int mapId,
    final List<byte[]> frames,
    final Frame first,
    final Consumer<Mcv2Playback> start
  ) {
    if (load != this.loads) {
      return;
    }

    final int videoWidth = first.getWidth();
    final int videoHeight = first.getHeight();
    final Pair<Integer, Integer> resolution = Pair.pair(videoWidth, videoHeight);
    final Mcv2Support support = this.plugin.getMcv2Support();
    final Settings settings = Mcv2Profile.DEFAULT.getSettings();
    final OnlinePlayers onlinePlayers = this.plugin.getOnlinePlayers();
    final Collection<UUID> viewers = ArgumentUtils.resolveViewers(playerSelector, onlinePlayers);
    final Optional<Mcv2Configuration> foundConfiguration = support.configure(sender, blocks, resolution, mapId, settings, viewers);
    final @Nullable Mcv2Configuration configuration = foundConfiguration.orElse(null);
    if (configuration == null) {
      return;
    }

    this.stop();

    final Optional<Mcv2PackServer.Lease> foundSlot = support.open(sender, configuration);
    final Mcv2PackServer.@Nullable Lease slot = foundSlot.orElse(null);
    if (slot == null) {
      return;
    }

    final Mcv2Configuration slotted = slot.getConfiguration();
    final Mcv2Viewers packViewers = support.getViewers();
    final Mcv2Channel opened = new Mcv2Channel(slotted, packViewers);
    opened.open();
    this.channel = opened;
    this.lease = slot;
    final long firstFrameId = slotted.getFirstFrameId();
    final Mcv2Playback playback = new Mcv2Playback(opened, frames, firstFrameId);
    this.playback = playback;
    start.accept(playback);
  }

  /**
   * Handles {@code /mcav mcv2 encode <file> <output> <resolution> <preset>}: encodes a video file into an MCV2 stream
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
   * @param preset     the encoder preset, {@code DEFAULT} or {@code FAST}
   * @since 1.0.0
   */
  @Command("mcav mcv2 encode <file> <output> <resolution> <preset>")
  @Permission("mcav.command.mcv2.encode")
  @CommandDescription("mcav.command.mcv2.encode.info")
  public void encode(
    final CommandSender sender,
    @Quoted final String file,
    @Quoted final String output,
    @Argument(suggestions = SuggestionProvider.RESOLUTIONS) @Quoted final String resolution,
    final Mcv2Profile preset
  ) {
    final @Nullable Pair<Integer, Integer> size = this.parseEncodeSize(sender, resolution);
    if (size == null) {
      return;
    }

    final @Nullable Path source = this.resolveVideoFile(sender, file);
    if (source == null) {
      return;
    }

    final @Nullable Path target = this.resolveStreamFile(sender, output);
    if (target == null) {
      return;
    }

    this.startEncode(sender, source, target, size, preset);
  }

  private @Nullable Pair<Integer, Integer> parseEncodeSize(final CommandSender sender, final String resolution) {
    final Optional<Pair<Integer, Integer>> parsedSize = AbstractVideoCommand.parseDimensions(sender, resolution);
    final @Nullable Pair<Integer, Integer> size = parsedSize.orElse(null);
    if (size == null) {
      return null;
    }

    final int width = size.getFirst();
    final int height = size.getSecond();
    if (width > Mcv2Decoder.MAXIMUM_DIMENSION || height > Mcv2Decoder.MAXIMUM_DIMENSION) {
      final Component message = Message.UNSUPPORTED_DIMENSION.build();
      sender.sendMessage(message);
      return null;
    }

    return size;
  }

  private @Nullable Path resolveVideoFile(final CommandSender sender, final String file) {
    try {
      return Path.of(file);
    } catch (final InvalidPathException exception) {
      this.reportFailure(sender, exception);
      return null;
    }
  }

  private @Nullable Path resolveStreamFile(final CommandSender sender, final String output) {
    try {
      return this.prepareStreamFile(output);
    } catch (final IOException | InvalidPathException exception) {
      final @Nullable String detail = exception.getMessage();
      final String reason = String.valueOf(detail);
      final Component message = Message.MCV2_ENCODE_ERROR.build(reason);
      sender.sendMessage(message);
      return null;
    }
  }

  private void startEncode(
    final CommandSender sender,
    final Path source,
    final Path target,
    final Pair<Integer, Integer> size,
    final Mcv2Profile preset
  ) {
    final int width = size.getFirst();
    final int height = size.getSecond();
    final Pool budget = Pool.shared();
    final Opener open = this.opener;
    synchronized (this) {
      final @Nullable FileEncode running = this.encoding;
      if (this.isEncodingRunning(running)) {
        final Component message = Message.MCV2_ENCODE_BUSY.build();
        sender.sendMessage(message);
        return;
      }

      final String presetName = preset.name();
      final String lowerPresetName = presetName.toLowerCase(Locale.ROOT);
      final int encoderThreads = budget.getThreads();
      final String description = "%s into %s at %sx%s with the %s preset, on %s encoder threads".formatted(
        source,
        target,
        width,
        height,
        lowerPresetName,
        encoderThreads
      );
      final Component message = Message.MCV2_ENCODE_START.build(description);
      sender.sendMessage(message);
      final AtomicBoolean stopped = new AtomicBoolean();
      final Opener stoppable = (video, frameWidth, frameHeight) -> this.openStoppableFrames(open, video, frameWidth, frameHeight, stopped);
      final Thread thread = Thread.ofPlatform()
        .daemon()
        .name(FILE_ENCODE_THREAD_NAME)
        .start(() -> this.encodeFile(sender, stoppable, source, target, width, height, preset, budget, PROGRESS_INTERVAL_NANOSECONDS));
      this.encoding = new FileEncode(thread, stopped);
    }
  }

  private boolean isEncodingRunning(final @Nullable FileEncode running) {
    if (running == null) {
      return false;
    }

    final Thread thread = running.thread();
    return thread.isAlive();
  }

  private Mcv2FileEncoder.FrameReader openStoppableFrames(
    final Opener opener,
    final Path video,
    final int width,
    final int height,
    final AtomicBoolean stopped
  ) throws IOException {
    final Mcv2FileEncoder.FrameReader frames = opener.open(video, width, height);
    return new StoppableFrames(frames, stopped);
  }

  void encodeFile(
    final CommandSender sender,
    final Opener opener,
    final Path source,
    final Path target,
    final int width,
    final int height,
    final Mcv2Profile preset,
    final Pool budget,
    final long interval
  ) {
    final Path fileName = target.getFileName();
    final Path partial = target.resolveSibling("%s%s".formatted(fileName, PARTIAL_SUFFIX));
    final long started = System.nanoTime();
    final LongConsumer progress = this.createProgressReporter(sender, started, interval);
    final Mcv2FileEncoder.Result result;
    try {
      try (final OutputStream output = new BufferedOutputStream(Files.newOutputStream(partial))) {
        final Mcv2FileEncoder.FrameReader frames = opener.open(source, width, height);
        final Settings settings = preset.getSettings();
        result = Mcv2FileEncoder.encode(frames, width, height, settings, budget, output, progress);
      }
      Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
    } catch (final IOException | RuntimeException exception) {
      this.deleteQuietly(partial);
      this.reportFailure(sender, exception);
      return;
    }

    this.reportDone(sender, result, target, started);
  }

  private void reportFailure(final CommandSender sender, final Exception exception) {
    final @Nullable Throwable cause = exception.getCause();
    if (cause instanceof InterruptedException) {
      final Component message = Message.MCV2_ENCODE_CANCELLED.build();
      sender.sendMessage(message);
      return;
    }

    final @Nullable String detail = exception.getMessage();
    final String reason = String.valueOf(detail);
    final Component message = Message.MCV2_ENCODE_ERROR.build(reason);
    sender.sendMessage(message);
  }

  private void reportDone(final CommandSender sender, final Mcv2FileEncoder.Result result, final Path target, final long started) {
    final long now = System.nanoTime();
    final long elapsedSeconds = Math.round((now - started) / NANOSECONDS_PER_SECOND);
    final long frames = result.getFrames();
    final long millisecondsPerFrame = this.computeMillisecondsPerFrame(started, now, frames);
    final long encodingMillisecondsPerFrame = Math.round(result.millisecondsPerFrame());
    final long keyframes = result.getKeyframes();
    final String description = "%s frames (%s keyframes) into %s in %s s, %s ms per frame, %s ms of it encoding".formatted(
      frames,
      keyframes,
      target,
      elapsedSeconds,
      millisecondsPerFrame,
      encodingMillisecondsPerFrame
    );
    final Component message = Message.MCV2_ENCODE_DONE.build(description);
    sender.sendMessage(message);
  }

  private long computeMillisecondsPerFrame(final long started, final long now, final long frames) {
    if (frames == 0) {
      return 0;
    }

    final double millisecondsPerFrame = (now - started) / NANOSECONDS_PER_MILLISECOND / frames;
    return Math.round(millisecondsPerFrame);
  }

  private LongConsumer createProgressReporter(final CommandSender sender, final long started, final long interval) {
    final long[] reported = { started };
    return frames -> this.reportProgress(sender, started, interval, reported, frames);
  }

  private void reportProgress(
    final CommandSender sender,
    final long started,
    final long interval,
    final long[] reported,
    final long frames
  ) {
    final long now = System.nanoTime();
    if (this.isProgressDue(now, reported[0], interval)) {
      reported[0] = now;
      final long millisecondsPerFrame = this.computeMillisecondsPerFrame(started, now, frames);
      final String description = "%s frames, %s ms per frame".formatted(frames, millisecondsPerFrame);
      final Component message = Message.MCV2_ENCODE_PROGRESS.build(description);
      sender.sendMessage(message);
    }
  }

  // a hook so a test can kill the boundary mutant of the progress interval check
  boolean isProgressDue(final long now, final long reported, final long interval) {
    final long elapsed = now - reported;
    return elapsed >= interval;
  }

  Path prepareStreamFile(final String name) throws IOException {
    final File dataFolder = this.plugin.getDataFolder();
    final Path dataPath = dataFolder.toPath();
    final Path streamFolder = dataPath.resolve(STREAM_FOLDER);
    final Path absoluteFolder = streamFolder.toAbsolutePath();
    final Path folder = absoluteFolder.normalize();
    final Path resolvedFile = folder.resolve(name);
    final Path file = resolvedFile.normalize();
    if (!file.startsWith(folder) || file.equals(folder)) {
      final String message = "Stream file %s is not a file in %s, where stream files are kept!".formatted(name, folder);
      throw new IOException(message);
    }

    Files.createDirectories(folder);

    return file;
  }

  private void deleteQuietly(final Path file) {
    try {
      Files.deleteIfExists(file);
    } catch (final IOException exception) {
      // what is left of an encode that stopped is overwritten by the next
    }
  }

  /**
   * Handles {@code /mcav mcv2 cancel}: stops the file encode that is running.
   *
   * <p>Requires the permission {@code mcav.command.mcv2.encode}.
   *
   * @param sender who ran the command
   * @since 1.0.0
   */
  @Command("mcav mcv2 cancel")
  @Permission("mcav.command.mcv2.encode")
  @CommandDescription("mcav.command.mcv2.cancel.info")
  public void cancel(final CommandSender sender) {
    final @Nullable FileEncode running;
    // the encode stays the running one until its thread ends, since a new encode would write the partial file the cancelled one deletes
    synchronized (this) {
      running = this.encoding;
    }

    if (running == null || !this.isEncodingRunning(running)) {
      final Component message = Message.MCV2_ENCODE_NONE.build();
      sender.sendMessage(message);
      return;
    }

    // the encode tells its sender that it stopped
    running.stop();
  }

  /**
   * Handles {@code /mcav mcv2 stop}: stops the stream, also one whose file is still being read, and removes the screen's
   * page frames.
   *
   * @param sender who ran the command
   * @since 1.0.0
   */
  @Command("mcav mcv2 stop")
  @Permission("mcav.command.mcv2.play")
  @CommandDescription("mcav.command.mcv2.stop.info")
  public void stop(final CommandSender sender) {
    this.loads++;
    this.stop();

    final Component message = Message.MCV2_STOP.build();
    sender.sendMessage(message);
  }

  // the pack stays loaded, so clients do not reload their resources for the next stream
  void stop() {
    final @Nullable BukkitTask running = this.task;
    if (running != null) {
      running.cancel();
      this.task = null;
    }

    final @Nullable ScheduledFuture<?> schedule = this.streaming;
    if (schedule != null) {
      schedule.cancel(INTERRUPT_IF_RUNNING);
      this.streaming = null;
    }

    final @Nullable ScheduledExecutorService executor = this.streamer;
    if (executor != null) {
      executor.shutdownNow();
      this.streamer = null;
    }

    final @Nullable Mcv2Playback playing = this.playback;
    if (playing != null) {
      playing.stop();
      this.playback = null;
    }

    final @Nullable Mcv2Channel opened = this.channel;
    if (opened != null) {
      opened.close();
      this.channel = null;
    }

    final Mcv2PackServer.@Nullable Lease slot = this.lease;
    if (slot != null) {
      slot.close();
      this.lease = null;
    }
  }

  List<byte[]> read(final Path file) throws IOException {
    return this.read(file, MAXIMUM_STREAM_BYTES);
  }

  // regular files only, size checked first: /dev/zero never ends and a huge file fills the memory
  List<byte[]> read(final Path file, final long limit) throws IOException {
    if (!Files.isRegularFile(file)) {
      final Path fileName = file.getFileName();
      final String message = "File %s is not a stream file!".formatted(fileName);
      throw new IOException(message);
    }

    final long fileSize = Files.size(file);
    if (fileSize > limit) {
      final String message = "The stream file is larger than %s bytes!".formatted(limit);
      throw new IOException(message);
    }

    final byte[] data = Files.readAllBytes(file);
    return this.splitFrames(data);
  }

  private List<byte[]> splitFrames(final byte[] data) throws IOException {
    final List<byte[]> frames = new ArrayList<>();
    int offset = 0;
    while (offset < data.length) {
      if (data.length - offset < LENGTH_BYTES) {
        final String message = "Truncated frame length!";
        throw new IOException(message);
      }
      final long length = Mcv2Decoder.readUnsignedInt(data, offset);
      if (length > data.length - offset - LENGTH_BYTES) {
        final String message = "Truncated frame!";
        throw new IOException(message);
      }
      // a record no frame can be is refused before the copy: tiny records would make copies take several times the file's size in memory
      if (length < Mcv2Decoder.HEADER_BYTES || length > Mcv2Decoder.MAXIMUM_FRAME_BYTES) {
        final String message = "MCV2 frame length %s is invalid!".formatted(length);
        throw new IOException(message);
      }
      final byte[] frame = new byte[(int) length];
      System.arraycopy(data, offset + LENGTH_BYTES, frame, 0, frame.length);
      frames.add(frame);
      offset += LENGTH_BYTES + frame.length;
    }

    if (frames.isEmpty()) {
      final String message = "Empty stream!";
      throw new IOException(message);
    }

    return frames;
  }

  // still set while a cancelled encode cleans up
  synchronized @Nullable Thread getEncodingThread() {
    final @Nullable FileEncode current = this.encoding;
    if (current == null) {
      return null;
    }

    return current.thread();
  }

  void setOpener(final Opener replacement) {
    this.opener = replacement;
  }

  @FunctionalInterface
  interface Opener {
    Mcv2FileEncoder.FrameReader open(final Path video, final int width, final int height) throws IOException;
  }

  private record FileEncode(Thread thread, AtomicBoolean stopped) {
    void stop() {
      this.stopped.set(true);
      this.thread.interrupt();
    }
  }

  // re-interrupts around every read: code on the thread, such as an instrumenting agent, may clear the interrupt
  private static final class StoppableFrames implements Mcv2FileEncoder.FrameReader {

    private final Mcv2FileEncoder.FrameReader frames;
    private final AtomicBoolean stopped;

    private StoppableFrames(final Mcv2FileEncoder.FrameReader frames, final AtomicBoolean stopped) {
      this.frames = frames;
      this.stopped = stopped;
    }

    @Override
    public boolean read(final byte[] rgb) {
      // before, so a read that waits is interrupted
      this.interruptIfStopped();
      final boolean more = this.frames.read(rgb);
      this.interruptIfStopped();

      return more;
    }

    @Override
    public void close() {
      this.frames.close();
    }

    private void interruptIfStopped() {
      if (this.stopped.get()) {
        final Thread current = Thread.currentThread();
        current.interrupt();
      }
    }
  }
}
