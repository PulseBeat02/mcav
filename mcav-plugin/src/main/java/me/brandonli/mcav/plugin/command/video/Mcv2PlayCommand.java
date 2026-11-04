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
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2.Pool;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Channel;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Configuration;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.Frame;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2FileEncoder;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2PackServer;
import me.brandonli.mcav.plugin.MCAVSandbox;
import me.brandonli.mcav.plugin.command.AnnotationCommandFeature;
import me.brandonli.mcav.plugin.command.SuggestionProvider;
import me.brandonli.mcav.plugin.locale.Message;
import me.brandonli.mcav.plugin.utils.ArgumentUtils;
import me.brandonli.mcav.plugin.utils.TaskUtils;
import me.brandonli.mcav.utils.immutable.Pair;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
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

  private final MCAVSandbox plugin;

  private @Nullable BukkitTask task;

  private @Nullable ScheduledExecutorService streamer;

  private @Nullable ScheduledFuture<?> streaming;

  private @Nullable Mcv2Channel channel;

  private Mcv2PackServer.@Nullable Lease lease;

  private @Nullable FileEncode encoding;

  // one thread, so quick repeated commands hold one file at a time
  private final ExecutorService reads;

  // a load is dropped once a later play, stream or stop command was given; main thread only
  private long loads;

  private Opener opener = Mcv2FileEncoder::ffmpeg;

  /**
   * Creates the command.
   *
   * @param plugin the plugin
   * @since 1.0.0
   */
  public Mcv2PlayCommand(final MCAVSandbox plugin) {
    Preconditions.checkNotNull(plugin, "Plugin must not be null!");

    this.plugin = plugin;
    this.reads = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("mcav-mcv2-stream-read").factory());
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
    final FileEncode running;
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
    this.task = Bukkit.getScheduler().runTaskTimerAsynchronously(this.plugin, playback, 0, ticks);
    sender.sendMessage(Message.MCV2_PLAY.build());
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
      Thread.ofPlatform().daemon().name("mcav-mcv2-stream").factory()
    );
    this.streaming = executor.scheduleAtFixedRate(playback, 0, TimeUnit.SECONDS.toNanos(1) / framesPerSecond, TimeUnit.NANOSECONDS);
    this.streamer = executor;
    sender.sendMessage(Message.MCV2_PLAY.build());
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

    final long load = ++this.loads;
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
      final List<byte[]> frames = read(this.prepareStreamFile(file));
      final Frame first = Mcv2Decoder.parse(frames.getFirst());
      return () -> this.openIfCurrent(load, sender, playerSelector, blocks, mapId, frames, first, start);
    } catch (final IOException | RuntimeException exception) {
      final Component message = Message.MCV2_FILE_ERROR.build(String.valueOf(exception.getMessage()));
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

    final Pair<Integer, Integer> resolution = Pair.pair(first.getWidth(), first.getHeight());
    final Mcv2Support support = this.plugin.getMcv2Support();
    final Optional<Mcv2Configuration> foundConfiguration = support.configure(
      sender,
      blocks,
      resolution,
      mapId,
      Mcv2Profile.DEFAULT.getSettings(),
      ArgumentUtils.resolveViewers(playerSelector, this.plugin.getOnlinePlayers())
    );
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
    final Mcv2Channel opened = new Mcv2Channel(slotted, support.getViewers());
    opened.open();
    this.channel = opened;
    this.lease = slot;
    start.accept(new Mcv2Playback(opened, frames, slotted.getFirstFrameId()));
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
   * @param profile    the encoder profile, {@code DEFAULT} or {@code FAST}
   * @since 1.0.0
   */
  @Command("mcav mcv2 encode <file> <output> <resolution> <profile>")
  @Permission("mcav.command.mcv2.encode")
  @CommandDescription("mcav.command.mcv2.encode.info")
  public void encode(
    final CommandSender sender,
    @Quoted final String file,
    @Quoted final String output,
    @Argument(suggestions = SuggestionProvider.RESOLUTIONS) @Quoted final String resolution,
    final Mcv2Profile profile
  ) {
    final Pair<Integer, Integer> size = this.parseEncodeSize(sender, resolution);
    if (size == null) {
      return;
    }

    final Path source = Path.of(file);
    final Path target = this.resolveStreamFile(sender, output);
    if (target == null) {
      return;
    }

    this.startEncode(sender, source, target, size, profile);
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
      sender.sendMessage(Message.UNSUPPORTED_DIMENSION.build());
      return null;
    }

    return size;
  }

  private @Nullable Path resolveStreamFile(final CommandSender sender, final String output) {
    try {
      return this.prepareStreamFile(output);
    } catch (final IOException exception) {
      sender.sendMessage(Message.MCV2_ENCODE_ERROR.build(String.valueOf(exception.getMessage())));
      return null;
    }
  }

  private void startEncode(
    final CommandSender sender,
    final Path source,
    final Path target,
    final Pair<Integer, Integer> size,
    final Mcv2Profile profile
  ) {
    final int width = size.getFirst();
    final int height = size.getSecond();
    final Pool budget = Pool.shared();
    final Opener open = this.opener;
    synchronized (this) {
      final FileEncode running = this.encoding;
      if (running != null && running.thread().isAlive()) {
        sender.sendMessage(Message.MCV2_ENCODE_BUSY.build());
        return;
      }

      sender.sendMessage(
        Message.MCV2_ENCODE_START.build(
          "%s into %s at %sx%s with the %s profile, on %s encoder threads".formatted(
            source,
            target,
            width,
            height,
            profile.name().toLowerCase(Locale.ROOT),
            budget.getThreads()
          )
        )
      );
      final AtomicBoolean stopped = new AtomicBoolean();
      final Opener stoppable = (video, frameWidth, frameHeight) -> new StoppableFrames(open.open(video, frameWidth, frameHeight), stopped);
      final Thread thread = Thread.ofPlatform()
        .daemon()
        .name("mcav-mcv2-file-encode")
        .start(() -> encodeFile(sender, stoppable, source, target, width, height, profile, budget, PROGRESS_INTERVAL_NANOSECONDS));
      this.encoding = new FileEncode(thread, stopped);
    }
  }

  static void encodeFile(
    final CommandSender sender,
    final Opener opener,
    final Path source,
    final Path target,
    final int width,
    final int height,
    final Mcv2Profile profile,
    final Pool budget,
    final long interval
  ) {
    final Path fileName = target.getFileName();
    final Path partial = target.resolveSibling("%s%s".formatted(fileName, PARTIAL_SUFFIX));
    final long started = System.nanoTime();
    final LongConsumer progress = createProgressReporter(sender, started, interval);
    final Mcv2FileEncoder.Result result;
    try {
      try (final OutputStream output = new BufferedOutputStream(Files.newOutputStream(partial))) {
        result = Mcv2FileEncoder.encode(opener.open(source, width, height), width, height, profile.getSettings(), budget, output, progress);
      }
      Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
    } catch (final IOException | RuntimeException exception) {
      deleteQuietly(partial);
      reportFailure(sender, exception);
      return;
    }
    reportDone(sender, result, target, started);
  }

  private static void reportFailure(final CommandSender sender, final Exception exception) {
    if (exception.getCause() instanceof InterruptedException) {
      sender.sendMessage(Message.MCV2_ENCODE_CANCELLED.build());
      return;
    }

    sender.sendMessage(Message.MCV2_ENCODE_ERROR.build(String.valueOf(exception.getMessage())));
  }

  private static void reportDone(final CommandSender sender, final Mcv2FileEncoder.Result result, final Path target, final long started) {
    final long elapsedSeconds = Math.round((System.nanoTime() - started) / NANOSECONDS_PER_SECOND);
    final long millisecondsPerFrame = Math.round(result.millisecondsPerFrame());
    sender.sendMessage(
      Message.MCV2_ENCODE_DONE.build(
        "%s frames (%s keyframes) into %s in %s s, %s ms per frame".formatted(
          result.getFrames(),
          result.getKeyframes(),
          target,
          elapsedSeconds,
          millisecondsPerFrame
        )
      )
    );
  }

  private static LongConsumer createProgressReporter(final CommandSender sender, final long started, final long interval) {
    final long[] reported = { started };
    return frames -> reportProgress(sender, started, interval, reported, frames);
  }

  private static void reportProgress(
    final CommandSender sender,
    final long started,
    final long interval,
    final long[] reported,
    final long frames
  ) {
    final long now = System.nanoTime();
    if (isProgressDue(now, reported[0], interval)) {
      reported[0] = now;
      final double millisecondsPerFrame = (now - started) / NANOSECONDS_PER_MILLISECOND / frames;
      final long roundedMillisecondsPerFrame = Math.round(millisecondsPerFrame);
      sender.sendMessage(Message.MCV2_ENCODE_PROGRESS.build("%s frames, %s ms per frame".formatted(frames, roundedMillisecondsPerFrame)));
    }
  }

  // a hook so a test can kill the boundary mutant of the progress interval check
  static boolean isProgressDue(final long now, final long reported, final long interval) {
    final long elapsed = now - reported;
    return elapsed >= interval;
  }

  Path prepareStreamFile(final String name) throws IOException {
    final Path folder = this.plugin.getDataFolder().toPath().resolve(STREAM_FOLDER).toAbsolutePath().normalize();
    final Path file = folder.resolve(name).normalize();
    if (!file.startsWith(folder) || file.equals(folder)) {
      final String message = "Stream file %s is not a file in %s, where stream files are kept!".formatted(name, folder);
      throw new IOException(message);
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
    final FileEncode running;
    // the encode stays the running one until its thread ends: a new encode before then would write the same partial
    // file the cancelled one deletes as it ends
    synchronized (this) {
      running = this.encoding;
    }
    if (running == null || !running.thread().isAlive()) {
      sender.sendMessage(Message.MCV2_ENCODE_NONE.build());
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
    sender.sendMessage(Message.MCV2_STOP.build());
  }

  // the pack stays loaded, so clients do not reload their resources for the next stream
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
    final Mcv2PackServer.Lease slot = this.lease;
    if (slot != null) {
      slot.close();
      this.lease = null;
    }
  }

  static List<byte[]> read(final Path file) throws IOException {
    return read(file, MAXIMUM_STREAM_BYTES);
  }

  // regular files only, size checked first: /dev/zero never ends and a huge file fills the memory
  static List<byte[]> read(final Path file, final long limit) throws IOException {
    if (!Files.isRegularFile(file)) {
      final String message = "File %s is not a stream file!".formatted(file.getFileName());
      throw new IOException(message);
    }

    if (Files.size(file) > limit) {
      final String message = "The stream file is larger than %s bytes!".formatted(limit);
      throw new IOException(message);
    }

    final byte[] data = Files.readAllBytes(file);
    return splitFrames(data);
  }

  private static List<byte[]> splitFrames(final byte[] data) throws IOException {
    final List<byte[]> frames = new ArrayList<>();
    int offset = 0;
    while (offset < data.length) {
      if (data.length - offset < LENGTH_BYTES) {
        final String message = "Truncated frame length!";
        throw new IOException(message);
      }
      final long length = Mcv2Decoder.u32(data, offset);
      if (length > data.length - offset - LENGTH_BYTES) {
        final String message = "Truncated frame!";
        throw new IOException(message);
      }
      // a record no frame can be is refused before it is copied: records of a few bytes each would make the copies
      // take several times the file's size in memory
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
    final FileEncode current = this.encoding;
    return current == null ? null : current.thread();
  }

  void setOpener(final Opener replacement) {
    this.opener = replacement;
  }

  @FunctionalInterface
  interface Opener {
    Mcv2FileEncoder.FrameReader open(Path video, int width, int height) throws IOException;
  }

  private record FileEncode(Thread thread, AtomicBoolean stopped) {
    void stop() {
      this.stopped.set(true);
      this.thread.interrupt();
    }
  }

  // re-interrupts around every read: code on the thread, such as an instrumenting agent, may clear the interrupt
  private record StoppableFrames(Mcv2FileEncoder.FrameReader frames, AtomicBoolean stopped) implements Mcv2FileEncoder.FrameReader {
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
        Thread.currentThread().interrupt();
      }
    }
  }
}
