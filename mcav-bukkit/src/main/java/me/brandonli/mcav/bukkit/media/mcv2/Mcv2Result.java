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
package me.brandonli.mcav.bukkit.media.mcv2;

import com.google.common.base.Preconditions;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import me.brandonli.mcav.bukkit.BukkitModule;
import me.brandonli.mcav.bukkit.media.config.MapConfiguration;
import me.brandonli.mcav.bukkit.media.map.MapPacketFactory;
import me.brandonli.mcav.bukkit.media.mcv2.encode.EncoderPool;
import me.brandonli.mcav.bukkit.media.mcv2.encode.EncoderSettings;
import me.brandonli.mcav.bukkit.media.mcv2.encode.LiveSearch;
import me.brandonli.mcav.bukkit.media.mcv2.encode.Mcv2Encoder;
import me.brandonli.mcav.bukkit.media.mcv2.transport.MapAlphabet;
import me.brandonli.mcav.bukkit.media.mcv2.transport.TransportPages;
import me.brandonli.mcav.bukkit.media.result.CompressedMapResult;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.metadata.OriginalVideoMetadata;
import me.brandonli.mcav.media.player.pipeline.filter.video.FunctionalVideoFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.ResizeFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.DitherAlgorithm;
import org.bukkit.Bukkit;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Displays video on an MCV2 screen: players with the MCV2 resource pack receive every frame encoded as MCV2 pages,
 * which their shader decodes; every other viewer receives the video dithered onto the wall's maps, like
 * {@link CompressedMapResult}, so a player without the pack never sees a screen their client cannot show. Which
 * players have the pack, and when they start receiving frames, is decided by the {@link Mcv2Channel}. The maps are
 * dithered on a thread of their own, one frame at a time, the frames that arrive meanwhile left out: dithering a 1080p
 * frame onto a wall of maps takes a large part of a second, and done on the video's thread it would hold the video, and
 * every viewer's frame rate, to the dithering's.
 *
 * <p>Frames are encoded inside the screen's encoder budget ({@link Mcv2Configuration#getEncoderPool()}), which every
 * screen of the server shares unless it was given its own, in two steps: the screen's thread searches and writes a frame
 * ({@link Mcv2Encoder#begin}), then hands it to the screen's sender thread, which verifies it ({@link Mcv2Encoder#finish})
 * and sends it while the next frame is searched: one frame is in flight at most, and a frame is sent only once
 * verified. A frame that fails its verification stops the screen. A keyframe the channel asks for while a frame is in
 * flight comes with the frame after it; another preset begins only after the frame in flight is sent. The screen's
 * threads only wait for the budget. When the encoder is slower than the video, frames that arrive while it works
 * replace each other and only the newest is encoded, which the encoder's previous-frame reference allows. The encoder
 * bounds every frame to what the screen's page slots carry ({@link Mcv2Encoder#setFrameLimit}): a frame that would take
 * more pages is searched again at a higher lambda. One that still has more pages than the screen has slots is not sent,
 * and the next is a keyframe.
 *
 * <p>A {@link Mcv2Pacer} keeps the screen within what its budget sustains: when the frames take longer than the video
 * gives them, it first searches less hard, down the preset ladder from the screen's settings
 * ({@link EncoderSettings#faster()}: the exhaustive search, {@code live}, {@code adaptive}, {@code live-fast}), the
 * live presets one encoder that switches between them without a keyframe, the exhaustive search an encoder of its own
 * whose first frame is a keyframe; then it encodes fewer frames, down to {@link Mcv2Pacer#MIN_FPS} a second,
 * then shows a smaller video when the owner offers smaller sizes ({@link #setSmallerSizes}: each size has its own pack),
 * and when even that is too much, every viewer is shown the dithered maps, which need no encoder, until a later try
 * finds room again. Every step is logged, a step down as a warning, and handed to the
 * {@linkplain #setPacingListener(Consumer) pacing listener}, so the operator learns what was chosen and why. A result
 * without dithered maps stays at its lowest frame rate instead.
 *
 * <p>Call {@link #start()} and {@link #release()} on the main thread.
 *
 * <p>Use one start/release lifecycle per instance: configure callbacks and smaller sizes before start, stop
 * submitting frames before release, and create a new result for another lifecycle. Repeated start is not guarded,
 * and the fallback executor is shut down permanently by release. The result owns its worker threads, encoders,
 * channel screen and fallback result. The pack tracker, fallback algorithm and encoder pool remain caller-owned.
 * Serialize applyFilter calls; copied RGB/pixel data is handed to workers, so callers may reuse or close an input
 * buffer after the call returns. Do not mutate it during conversion.
 */
public final class Mcv2Result implements FunctionalVideoFilter {

  private static final Logger LOGGER = LoggerFactory.getLogger(Mcv2Result.class);

  /** How long a release waits for the encoding thread to stop. */
  private static final long JOIN_SECONDS = 10;

  private static final double NANOS_PER_MILLISECOND = 1e6;

  /** The link counts {@link #linkCounts} sums, and where each is. */
  private static final int LINK_COUNTS = 3;

  private static final int SENT = 0;

  private static final int BEHIND = 1;

  private static final int UNDECODABLE = 2;

  private static final String FRAME_TOO_LARGE = "MCV2 frame {} of {} bytes needs more than {} pages; it is not sent";

  /** A pacing step, as the pacer describes it to the pacing listener too. */
  private static final String PACING_STEP = "{}";

  private static final String ENCODER_FAILED = "The MCV2 screen stops: encoding a frame failed";

  private static final String SENDER_FAILED = "The MCV2 screen stops: verifying or sending a frame failed";

  private static final double NANOS_PER_SECOND = 1e9;

  /** The frames a screen may take at once after a pause: more than one, so a frame arriving early is not lost, fewer than two. */
  private static final double FRAME_CREDIT_CAP = 1.5;

  /**
   * The time a frame of each search of the preset ladder takes relative to the live search's, as the pacer measures it
   * (wall time in the budget, 1080p30, 12 threads, native kernels): the exhaustive search takes 570-800 ms where live
   * takes 21-43 ms, and live-fast 0.88 (gameplay) to 0.94 (quiet content) of live's time; the adaptive profile takes
   * live's on calm pictures and live-fast's in motion.
   */
  private static final double EXHAUSTIVE_COST = 20;

  private static final double LIVE_COST = 1;

  private static final double ADAPTIVE_COST = 0.95;

  private static final double LIVE_FAST_COST = 0.9;

  private final Mcv2Configuration requested;

  private final @Nullable Fallback fallback;

  private final Set<UUID> fallbackViewers;

  private final Executor dithering;

  private final @Nullable ExecutorService ditheringThread;

  private final AtomicBoolean ditheringBusy = new AtomicBoolean();

  private final Object lock;

  private final Statistics statistics;

  private final LongSupplier clock;

  /** The screen's frame rate per nanosecond of the clock. */
  private final double framesPerNano;

  /** The frames the screen may take now; one is taken for every frame shown. */
  private double frameCredit = FRAME_CREDIT_CAP;

  private long lastFrame = Long.MIN_VALUE;

  /** The settings the screen steps down through, from the ones it was asked for: each a faster search. */
  private final List<EncoderSettings> ladder;

  /** The pacer's preset of each of those settings, in the same order. */
  private final List<Mcv2Pacer.Preset> presets;

  /** Makes the encoder of a preset in the screen's encoder budget. */
  private final Function<EncoderSettings, Mcv2Encoder> encoders;

  private @Nullable Arrival pending;

  private boolean running;

  private @Nullable Thread worker;

  private @Nullable Thread sender;

  /** The budget the screen encodes in and the hand-off to its sender, while the result runs. */
  private @Nullable Pipeline pipeline;

  /** The frames handed to the sender, and the ones it has verified and sent, for draining the pipeline. */
  private long handed;

  private long delivered;

  private @Nullable Mcv2Pacer pacer;

  private boolean ditheredForAll;

  private Consumer<Mcv2Pacer.Change> pacingListener = _ -> {};

  private volatile Consumer<byte[]> frameListener = _ -> {};

  private volatile Screen screen;

  private boolean opened = true;

  private List<int[]> smaller = List.of();

  private @Nullable Resizer resizer;

  /** The screen the video is shown on now: its configuration, whose video size may change, and its channel. */
  private record Screen(Mcv2Configuration configuration, Mcv2Channel channel) {}

  /**
   * What a screen's owner does to show the video at another size: the pack decodes one video size, so a smaller video
   * needs its pack offered to the viewers, and a channel of its own.
   */
  @FunctionalInterface
  public interface Resizer {
    /**
     * Offers the viewers the pack of the screen at another video size and creates its channel, not opened yet. Called
     * on the main thread.
     *
     * @param configuration the screen at the new size
     * @return the channel that sends the video at that size
     */
    Mcv2Channel resize(Mcv2Configuration configuration);
  }

  /** The dithered maps of the viewers without the pack, and how they are dithered. */
  private record Fallback(CompressedMapResult result, DitherAlgorithm algorithm) {}

  /** A frame the video handed over, with the wall-clock time it arrived and the preset of the rung it arrived on. */
  static final class Arrival {

    private final byte[] rgb;

    private final int width;

    private final int height;

    private final long arrived;

    private final Mcv2Pacer.Preset preset;

    Arrival(final byte[] rgb, final int width, final int height, final long arrived, final Mcv2Pacer.Preset preset) {
      this.rgb = rgb;
      this.width = width;
      this.height = height;
      this.arrived = arrived;
      this.preset = preset;
    }
  }

  /** A frame searched and written, on its way to the sender, which verifies and sends it. */
  private record Handoff(Mcv2Encoder encoder, Mcv2Encoder.Pending pending, long frameId, Arrival source) {}

  /** The budget a started result encodes in, and where its screen's thread hands frames to its sender. */
  private record Pipeline(EncoderPool budget, BlockingQueue<Handoff> queue) {}

  /** An encoded frame on its way to the viewers, with its encoder statistics and where it came from. */
  private static final class Delivery {

    private final byte[] frame;

    private final Mcv2Encoder.Stats stats;

    private final long frameId;

    private final Arrival source;

    private Delivery(final byte[] frame, final Mcv2Encoder.Stats stats, final long frameId, final Arrival source) {
      this.frame = frame;
      this.stats = stats;
      this.frameId = frameId;
      this.source = source;
    }
  }

  /**
   * What the result has done so far.
   *
   * <p>Each getter is synchronized, but several getter calls are not one atomic snapshot. Counts describe
   * frames accepted by the channel, not multiplied by recipients or acknowledged by clients.
   */
  public static final class Statistics {

    private long frames;

    private long keyframes;

    private long dropped;

    private long bytes;

    private long mapBytes;

    private long nanoseconds;

    Statistics() {
      // one per result
    }

    synchronized void add(final Mcv2Encoder.Stats stats, final int mapColors) {
      this.frames++;
      this.keyframes += stats.keyframe() ? 1 : 0;
      this.bytes += stats.bytes();
      this.mapBytes += mapColors;
      this.nanoseconds += stats.nanoseconds();
    }

    private synchronized void drop() {
      this.dropped++;
    }

    /**
     * Gets the number of frames sent.
     *
     * @return the frames
     */
    public synchronized long getFrames() {
      return this.frames;
    }

    /**
     * Gets the number of keyframes sent.
     *
     * @return the keyframes
     */
    public synchronized long getKeyframes() {
      return this.keyframes;
    }

    /**
     * Gets the number of frames not sent because they had more pages than the screen has page slots.
     *
     * @return the dropped frames
     */
    public synchronized long getDropped() {
      return this.dropped;
    }

    /**
     * Gets the MCV2 bytes of the frames sent.
     *
     * @return the frame bytes
     */
    public synchronized long getBytes() {
      return this.bytes;
    }

    /**
     * Gets accumulated page-map color bytes per potential recipient: every page as whole 128-color rows.
     * Anchors, packet overhead and actual recipient counts are excluded.
     *
     * @return the map bytes per viewer
     */
    public synchronized long getMapBytes() {
      return this.mapBytes;
    }

    /**
     * Gets the time spent encoding the frames sent.
     *
     * @return the encode time in nanoseconds
     */
    public synchronized long getNanoseconds() {
      return this.nanoseconds;
    }
  }

  /**
   * Constructs a new result.
   *
   * @param configuration     the screen
   * @param viewers           who has the pack loaded
   * @param fallbackAlgorithm how the video is dithered for viewers without the pack, or null to show them nothing
   * @throws NullPointerException if configuration or viewers is null
   */
  public Mcv2Result(final Mcv2Configuration configuration, final Mcv2Viewers viewers, final @Nullable DitherAlgorithm fallbackAlgorithm) {
    this(configuration, new Mcv2Channel(configuration, viewers), fallbackAlgorithm);
  }

  Mcv2Result(final Mcv2Configuration configuration, final Mcv2Channel channel, final @Nullable DitherAlgorithm fallbackAlgorithm) {
    this(configuration, channel, fallbackAlgorithm, System::nanoTime, null);
  }

  /**
   * Constructs a result with the clock its pacer reads and the executor that dithers the maps of the viewers without
   * the pack.
   *
   * @param dithering runs the dithering, or null for a thread of the result's own, stopped on release
   */
  Mcv2Result(
    final Mcv2Configuration configuration,
    final Mcv2Channel channel,
    final @Nullable DitherAlgorithm fallbackAlgorithm,
    final LongSupplier clock,
    final @Nullable Executor dithering
  ) {
    this(configuration, channel, fallbackAlgorithm, clock, dithering, settings -> configuration.getEncoderPool().encoder(settings, true));
  }

  /**
   * Constructs a result with the clock its pacer reads, the executor that dithers the maps of the viewers without the
   * pack, and what makes the encoder of each preset.
   *
   * @param dithering runs the dithering, or null for a thread of the result's own, stopped on release
   * @param encoders  makes the encoder of a preset
   */
  Mcv2Result(
    final Mcv2Configuration configuration,
    final Mcv2Channel channel,
    final @Nullable DitherAlgorithm fallbackAlgorithm,
    final LongSupplier clock,
    final @Nullable Executor dithering,
    final Function<EncoderSettings, Mcv2Encoder> encoders
  ) {
    Preconditions.checkNotNull(configuration, "Configuration must not be null");
    Preconditions.checkNotNull(channel, "Channel must not be null");
    this.requested = configuration;
    this.screen = new Screen(configuration, channel);
    this.fallbackViewers = ConcurrentHashMap.newKeySet();
    this.fallback =
      fallbackAlgorithm == null
        ? null
        : new Fallback(new CompressedMapResult(fallbackConfiguration(configuration, this.fallbackViewers)), fallbackAlgorithm);
    if (dithering == null) {
      final ExecutorService thread = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("mcav-mcv2-dither").factory());
      this.ditheringThread = thread;
      this.dithering = thread;
    } else {
      this.ditheringThread = null;
      this.dithering = dithering;
    }
    this.lock = new Object();
    this.statistics = new Statistics();
    this.clock = clock;
    this.framesPerNano = configuration.getMaxFrameRate() / NANOS_PER_SECOND;
    this.ladder = ladder(configuration.getSettings());
    this.presets = presets(this.ladder);
    this.encoders = encoders;
  }

  /** The settings a screen steps down through: the ones it was asked for, then each faster search's. */
  private static List<EncoderSettings> ladder(final EncoderSettings settings) {
    final List<EncoderSettings> ladder = new ArrayList<>();
    for (EncoderSettings rung = settings; rung != null; rung = rung.faster()) {
      ladder.add(rung);
    }
    return List.copyOf(ladder);
  }

  /** The pacer's presets of a ladder: one without a name when there is nothing to step through. */
  private static List<Mcv2Pacer.Preset> presets(final List<EncoderSettings> ladder) {
    if (ladder.size() == 1) {
      return List.of(Mcv2Pacer.Preset.ONLY);
    }
    final List<Mcv2Pacer.Preset> presets = new ArrayList<>();
    for (final EncoderSettings settings : ladder) {
      presets.add(preset(settings));
    }
    return List.copyOf(presets);
  }

  /** The pacer's preset of a rung of the ladder, which {@link EncoderSettings#faster()} walks. */
  private static Mcv2Pacer.Preset preset(final EncoderSettings settings) {
    final LiveSearch search = settings.live();
    if (search == null) {
      return new Mcv2Pacer.Preset("exhaustive", EXHAUSTIVE_COST);
    }
    if (settings.adaptive() != null) {
      return new Mcv2Pacer.Preset("adaptive", ADAPTIVE_COST);
    }
    return LiveSearch.LIVE.equals(search) ? new Mcv2Pacer.Preset("live", LIVE_COST) : new Mcv2Pacer.Preset("live-fast", LIVE_FAST_COST);
  }

  private static MapConfiguration fallbackConfiguration(final Mcv2Configuration configuration, final Set<UUID> viewers) {
    return MapConfiguration.builder()
      .map(configuration.getMap())
      .mapBlockWidth(configuration.getColumns())
      .mapBlockHeight(configuration.getRows())
      // the pack scales the picture to the wall, so the dithered maps do too: a video larger than the maps would be
      // cropped to its middle, and a smaller one, such as a pacer rung, drawn small in the middle of the wall
      .resize(true)
      .viewers(viewers)
      .build();
  }

  /**
   * Gets what the result has done so far.
   *
   * @return the statistics, updated as frames are sent
   */
  public Statistics getStatistics() {
    return this.statistics;
  }

  /**
   * Gets the channel that delivers the frames.
   *
   * @return the channel
   */
  public Mcv2Channel getChannel() {
    return this.screen.channel();
  }

  /**
   * Gets the screen's configuration now: the one it was created with, or a copy at the smaller video size it stepped
   * down to.
   *
   * @return the configuration
   */
  public Mcv2Configuration getConfiguration() {
    return this.screen.configuration();
  }

  /**
   * Lets the screen step down to smaller video sizes when its encoder budget cannot sustain the size it was asked for,
   * before it falls back to the dithered maps. Call before {@link #start()}.
   *
   * <p>The list is copied, but its arrays are retained. Supply non-null arrays of exactly two positive pixel
   * dimensions, each within the codec limit, and do not mutate them afterward. Validation is deferred to pacer/configuration use.
   *
   * @param sizes   the smaller sizes, largest first, each a width and a height
   * @param resizer offers a size's pack and creates its channel
   * @throws NullPointerException if {@code sizes} or {@code resizer} is null
   */
  public void setSmallerSizes(final List<int[]> sizes, final Resizer resizer) {
    Preconditions.checkNotNull(sizes, "Sizes must not be null");
    Preconditions.checkNotNull(resizer, "Resizer must not be null");
    this.smaller = List.copyOf(sizes);
    this.resizer = resizer;
  }

  /**
   * Sets who is told of the pacer's steps besides the server log, for example the operator who started the screen.
   *
   * <p>The callback may run on different threads on different calls; it must be thread-safe, nonblocking and
   * must schedule Bukkit world changes on the main thread. Callback exceptions are not swallowed at the call site.
   *
   * @param listener called with every step, on the thread that caused it
   * @throws NullPointerException if {@code listener} is null
   */
  public void setPacingListener(final Consumer<Mcv2Pacer.Change> listener) {
    Preconditions.checkNotNull(listener, "Listener must not be null");
    this.pacingListener = listener;
  }

  /**
   * Sets who hears every frame the screen sends, as the bytes of its bitstream, for example to record the stream the
   * viewers were sent and decode it again with the reference decoder. A frame too large for the screen's page slots is
   * not sent, and not heard.
   *
   * <p>The callback receives the actual encoded array without a defensive copy. Treat it as read-only and
   * copy it before handing it to code that may mutate it. A callback that throws stops the screen: the failure is
   * logged, and no later frame is encoded or sent.
   *
   * @param listener called with every frame sent, on the thread that sends the frames, which it should not hold up
   * @throws NullPointerException if {@code listener} is null
   */
  public void setFrameListener(final Consumer<byte[]> listener) {
    Preconditions.checkNotNull(listener, "Listener must not be null");
    this.frameListener = listener;
  }

  /**
   * Gets the rung of the ladder the screen is on.
   *
   * @return the rung, or null before the result is started
   */
  public Mcv2Pacer.@Nullable Rung getRung() {
    synchronized (this.lock) {
      final Mcv2Pacer screenPacer = this.pacer;
      return screenPacer == null ? null : screenPacer.getRung();
    }
  }

  /**
   * Spawns the screen's page frames and starts the encoder. Call on the main thread.
   */
  @Override
  public void start() {
    this.screen.channel().open();
    this.opened = true;
    final Fallback dithered = this.fallback;
    if (dithered != null) {
      dithered.result().start();
    }
    final EncoderPool encoderPool = this.requested.getEncoderPool();
    final Mcv2Encoder encoder = this.encoders.apply(this.ladder.getFirst());
    // the screen's own thread only hands frames to the budget and waits for them
    final Thread thread = Thread.ofPlatform()
      .daemon()
      .name("mcav-mcv2-screen")
      .unstarted(() -> this.encodeLoop(encoder));
    // the frames are verified and sent on their own thread while the next frame is searched: a hand-off, so one frame
    // is in flight at most, and the search waits for its turn when verifying and sending fall behind
    final BlockingQueue<Handoff> queue = new SynchronousQueue<>();
    final Pipeline started = new Pipeline(encoderPool, queue);
    final Thread delivery = Thread.ofPlatform()
      .daemon()
      .name("mcav-mcv2-sender")
      .unstarted(() -> this.deliverLoop(started, thread));
    this.pace();
    synchronized (this.lock) {
      this.pipeline = started;
      this.worker = thread;
      this.sender = delivery;
      this.handed = 0;
      this.delivered = 0;
      this.running = true;
    }
    delivery.start();
    thread.start();
  }

  /**
   * Hands the frame to the encoder for the viewers with the pack, and dithers it for the others.
   *
   * @param data     the frame, resized in place to the screen's video size
   * @param metadata the metadata of the original video, which is not used
   * @return false when rate limiting skips the frame before conversion, otherwise true because it may
   *         have been resized; this does not report encoding, sending or client decoding success
   * @throws NullPointerException if {@code data} or {@code metadata} is null
   */
  @Override
  public boolean applyFilter(final ImageBuffer data, final OriginalVideoMetadata metadata) {
    Preconditions.checkNotNull(data, "Frame must not be null");
    Preconditions.checkNotNull(metadata, "Metadata must not be null");
    if (!this.takeFrame(this.clock.getAsLong())) {
      return false;
    }
    final boolean encode;
    final boolean everyoneDithered;
    Mcv2Pacer.Preset preset = this.presets.getFirst();
    Mcv2Pacer.Change tried = null;
    synchronized (this.lock) {
      final Mcv2Pacer screenPacer = this.pacer;
      if (screenPacer != null) {
        tried = screenPacer.arrive(this.clock.getAsLong());
        if (tried != null) {
          this.follow(tried);
        }
        encode = screenPacer.isEncoded();
        preset = screenPacer.getRung().preset();
      } else {
        encode = true;
      }
      everyoneDithered = this.ditheredForAll;
    }
    if (tried != null) {
      this.announce(tried);
    }
    final Screen current = this.screen;
    // on the dithered maps every viewer near the wall is dithered for; one too far from it sees no wall on any rung
    final Set<UUID> others = everyoneDithered ? current.channel().near(this.requested.getViewers()) : current.channel().update();
    this.fallbackViewers.retainAll(others);
    this.fallbackViewers.addAll(others);
    final int width = current.configuration().getVideoWidth();
    final int height = current.configuration().getVideoHeight();
    if (data.getWidth() != width || data.getHeight() != height) {
      new ResizeFilter(width, height).applyFilter(data);
    }
    // on the dithered maps the pacer encodes no frame
    if (encode && !current.channel().getRecipients().isEmpty()) {
      final Arrival arrival = new Arrival(rgb(data.getPixels(), width * height), width, height, System.currentTimeMillis(), preset);
      synchronized (this.lock) {
        this.pending = arrival;
        this.lock.notifyAll();
      }
    }
    final Fallback dithered = this.fallback;
    if (dithered != null && !others.isEmpty() && this.ditheringBusy.compareAndSet(false, true)) {
      // the dithering's own copy of the frame, which the video reuses once this returns
      final int[] argb = data.getPixels().clone();
      try {
        this.dithering.execute(() -> this.dither(dithered, argb, width, height));
      } catch (final RejectedExecutionException released) {
        // the result was released: the dithered maps are gone
        this.ditheringBusy.set(false);
      }
    }
    return true;
  }

  /**
   * Whether a frame arriving now is the screen's: a client decodes at most one frame for every frame it draws, and a
   * frame it never got breaks the frames predicted from it until the next keyframe, so a source faster than the
   * screen's rate, such as a browser painting at hundreds of frames a second, is thinned to it. A frame is earned every
   * frame interval, and a few earned while nothing arrived do not add up to a burst.
   *
   * @param now the clock, in nanoseconds
   * @return true if the frame is shown
   */
  boolean takeFrame(final long now) {
    if (this.framesPerNano == 0) {
      return true;
    }
    synchronized (this.lock) {
      if (this.lastFrame != Long.MIN_VALUE) {
        this.frameCredit = Math.min(FRAME_CREDIT_CAP, this.frameCredit + (now - this.lastFrame) * this.framesPerNano);
      }
      this.lastFrame = now;
      if (this.frameCredit < 1) {
        return false;
      }
      this.frameCredit -= 1;
      return true;
    }
  }

  /** Dithers one frame onto the maps of the viewers without the pack, then lets the next frame be dithered. */
  private void dither(final Fallback dithered, final int[] argb, final int width, final int height) {
    try (final ImageBuffer frame = ImageBuffer.buffer(argb, width, height)) {
      dithered.result().process(frame, dithered.algorithm());
    } finally {
      this.ditheringBusy.set(false);
    }
  }

  static byte[] rgb(final int[] argb, final int pixels) {
    final byte[] rgb = new byte[pixels * Mcv2Format.CHANNELS];
    for (int pixelIndex = 0; pixelIndex < pixels; pixelIndex++) {
      final int pixel = argb[pixelIndex];
      final int at = pixelIndex * Mcv2Format.CHANNELS;
      rgb[at] = (byte) (pixel >> 16);
      rgb[at + 1] = (byte) (pixel >> 8);
      rgb[at + 2] = (byte) pixel;
    }
    return rgb;
  }

  /** The frames the viewers' links sent, held back for the backlog and held back for a reference, summed. */
  private long[] linkCounts() {
    final long[] counts = new long[LINK_COUNTS];
    for (final Mcv2Link link : this.screen.channel().getLinks().values()) {
      counts[SENT] += link.getSent();
      counts[BEHIND] += link.getBehind();
      counts[UNDECODABLE] += link.getUndecodable();
    }
    return counts;
  }

  /** Commits a frame's flight recorder event, with what the viewers' links did with it. */
  private void record(final Mcv2FrameEvent event, final Delivery delivery, final int colors, final long[] before) {
    final long[] after = this.linkCounts();
    long backlog = 0;
    for (final Mcv2Link link : this.screen.channel().getLinks().values()) {
      backlog = Math.max(backlog, link.getBacklog());
    }
    event.frameId = delivery.frameId;
    event.keyframe = delivery.stats.keyframe();
    event.bytes = delivery.frame.length;
    event.colors = colors;
    event.arrived = delivery.source.arrived;
    event.sent = System.currentTimeMillis();
    event.encode = delivery.stats.nanoseconds();
    event.sentTo = (int) (after[SENT] - before[SENT]);
    event.behind = (int) (after[BEHIND] - before[BEHIND]);
    event.waiting = (int) (after[UNDECODABLE] - before[UNDECODABLE]);
    event.backlog = backlog;
    event.fingerprint = Mcv2FrameEvent.fingerprint(delivery.source.rgb, delivery.source.width, delivery.source.height);
    event.commit();
  }

  /**
   * Stops a thread of the result and waits for it: the sender waits for frames and the encoder may wait for the
   * sender, and both stop on an interrupt. A caller interrupted meanwhile keeps its interrupt.
   *
   * @param thread the thread, or null when it was never started
   */
  private static void stop(final @Nullable Thread thread) {
    if (thread == null) {
      return;
    }
    thread.interrupt();
    try {
      thread.join(TimeUnit.SECONDS.toMillis(JOIN_SECONDS));
    } catch (final InterruptedException exception) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Encodes frames until the result is released, each with the encoder of the preset of the rung it arrived on.
   *
   * @param first the encoder of the screen's settings, the top of its ladder
   */
  void encodeLoop(final Mcv2Encoder first) {
    final Pipeline own;
    synchronized (this.lock) {
      own = this.pipeline;
    }
    long frameId = this.requested.getFirstFrameId();
    Mcv2Encoder encoder = first;
    try {
      for (Arrival arrival = this.take(); arrival != null; arrival = this.take()) {
        final EncoderSettings settings = this.settingsFor(arrival);
        if (!settings.equals(encoder.getSettings())) {
          // the frame in flight goes out first, searched as it was begun
          this.drain();
          encoder = this.encoderFor(settings, encoder);
        }
        this.send(encoder, arrival, frameId);
        frameId = (frameId + 1) & Mcv2Format.MAX_U32;
      }
    } catch (final InterruptedException exception) {
      Thread.currentThread().interrupt();
    } catch (final RuntimeException failure) {
      // an encoder that cannot encode the frame, such as one of a size the codec cannot carry
      LOGGER.error(ENCODER_FAILED, failure);
    } finally {
      // the sender waits for this thread's frames only, so it stops with it
      this.stopPipeline(own);
    }
  }

  /**
   * Gets the settings of the preset of the rung a frame arrived on.
   *
   * @param arrival the frame
   * @return the settings
   */
  EncoderSettings settingsFor(final Arrival arrival) {
    return this.ladder.get(this.presets.indexOf(arrival.preset));
  }

  /**
   * Gets the encoder of another preset: between live presets the same encoder, switched without a keyframe (every live
   * search writes the same format from the same pictures); to or from the exhaustive search a new one, whose first
   * frame is a keyframe.
   *
   * @param settings the preset's settings
   * @param encoder  the encoder of the frame before, whose frames are all sent
   * @return the encoder of the next frame
   */
  Mcv2Encoder encoderFor(final EncoderSettings settings, final Mcv2Encoder encoder) {
    if (settings.live() != null && encoder.getSettings().live() != null) {
      encoder.switchTo(settings);
      return encoder;
    }
    return this.encoders.apply(settings);
  }

  /** Starts pacing the screen's frames from the top of its ladder, which {@link #start()} does. */
  void pace() {
    final List<int[]> sizes = new ArrayList<>();
    sizes.add(new int[] { this.requested.getVideoWidth(), this.requested.getVideoHeight() });
    sizes.addAll(this.smaller);
    final Mcv2Pacer screenPacer = new Mcv2Pacer(sizes, this.presets, this.fallback != null);
    synchronized (this.lock) {
      this.pacer = screenPacer;
      this.ditheredForAll = false;
    }
  }

  /**
   * Takes the newest frame that was not encoded yet, without waiting.
   *
   * @return the frame, or null if there is none
   */
  @Nullable Arrival poll() {
    synchronized (this.lock) {
      final Arrival arrival = this.pending;
      this.pending = null;
      return arrival;
    }
  }

  /**
   * Waits for the newest frame that was not encoded yet.
   *
   * @return the frame, or null once the result is released
   * @throws InterruptedException if the thread is interrupted while it waits
   */
  @Nullable Arrival take() throws InterruptedException {
    synchronized (this.lock) {
      while (this.running && this.pending == null) {
        this.lock.wait();
      }
      final Arrival arrival = this.pending;
      this.pending = null;
      return this.running ? arrival : null;
    }
  }

  /**
   * Encodes one frame: searches and writes it, and hands it to the sender thread, which verifies and sends it while the
   * next frame is searched; when the result was not started, encodes and sends it on this thread. The pacer is told
   * how long the frame held the pipeline: its search, and the wait for the frame before it to be verified and sent.
   *
   * @param encoder the encoder
   * @param arrival the frame and when it arrived
   * @param frameId the frame's id
   * @throws InterruptedException if the thread is interrupted while it waits for the sender to take the frame
   */
  void send(final Mcv2Encoder encoder, final Arrival arrival, final long frameId) throws InterruptedException {
    final Screen current = this.screen;
    if (current.channel().takeKeyframeRequest()) {
      encoder.requestKeyframe();
    }
    // no frame may take more pages than the screen has slots: one that would is searched again at a higher lambda
    encoder.setFrameLimit(current.configuration().getPageSlots() * TransportPages.capacity(MapAlphabet.SYMBOL_BITS));
    final int width = arrival.width;
    final int height = arrival.height;
    final Pipeline running;
    synchronized (this.lock) {
      running = this.pipeline;
    }
    final long started = this.clock.getAsLong();
    final boolean isKeyframe;
    Delivery delivery = null;
    if (running == null) {
      final byte[] frame = encoder.encode(arrival.rgb, width, height, frameId);
      final Mcv2Encoder.Stats stats = Preconditions.checkNotNull(encoder.getStats());
      isKeyframe = stats.keyframe();
      delivery = new Delivery(frame, stats, frameId, arrival);
    } else {
      final Mcv2Encoder.Pending pending = running.budget().run(() -> encoder.begin(arrival.rgb, width, height, frameId));
      isKeyframe = pending.isKeyframe();
      // counted before the hand-off: once the sender has the frame, a drain waits for it
      synchronized (this.lock) {
        this.handed++;
      }
      running.queue().put(new Handoff(encoder, pending, frameId, arrival));
    }
    final long finished = this.clock.getAsLong();
    Mcv2Pacer.Change change = null;
    synchronized (this.lock) {
      final Mcv2Pacer screenPacer = this.pacer;
      if (screenPacer != null) {
        change = screenPacer.encoded((finished - started) / NANOS_PER_MILLISECOND, isKeyframe, finished);
        if (change != null) {
          this.follow(change);
        }
      }
    }
    if (change != null) {
      this.announce(change);
    }
    if (delivery != null) {
      this.deliver(delivery);
    }
  }

  /**
   * Waits until the sender has verified and sent every frame handed to it.
   *
   * @throws InterruptedException if the thread is interrupted while it waits
   */
  void drain() throws InterruptedException {
    synchronized (this.lock) {
      // a stopped pipeline sends nothing more, so a frame it was handed will never be delivered
      while (this.running && this.delivered < this.handed) {
        this.lock.wait();
      }
    }
  }

  /**
   * Stops a pipeline from one of its own threads, once that thread ends for any reason: the screen takes no more
   * frames, a drain waits for nothing, and the other thread, which would otherwise wait for this one forever, is
   * interrupted. Does nothing once the result was released, or started again with another pipeline.
   *
   * @param stopped the pipeline the ending thread belongs to, or null when it ran without one
   */
  private void stopPipeline(final @Nullable Pipeline stopped) {
    final Thread screenThread;
    final Thread delivery;
    synchronized (this.lock) {
      final Pipeline current = this.pipeline;
      // each start makes a queue of its own, so a pipeline equals only itself; a thread that ran without one, or ends
      // after a release or a new start, stops nothing
      if (stopped == null || !Objects.equals(stopped, current)) {
        return;
      }
      this.running = false;
      this.lock.notifyAll();
      // start sets the pipeline and its two threads together, and release clears them together
      screenThread = Objects.requireNonNull(this.worker, "A running pipeline has its screen's thread");
      delivery = Objects.requireNonNull(this.sender, "A running pipeline has its sender");
    }
    // the release joins both threads; here they only learn that the screen stopped
    screenThread.interrupt();
    delivery.interrupt();
  }

  /**
   * Verifies and sends the frames the screen's thread hands over, in order, until the result is released. Anything
   * that fails here - a frame that fails its verification, a frame listener or a send that throws - stops the screen:
   * the failure is logged, and the screen's thread, which would otherwise wait forever to hand over its next frame, is
   * stopped too.
   *
   * @param running      the budget, and where the screen's thread hands the frames over
   * @param screenThread the screen's thread
   */
  private void deliverLoop(final Pipeline running, final Thread screenThread) {
    try {
      while (true) {
        final Handoff handoff = running.queue().take();
        final Mcv2Encoder.Encoded encoded = running.budget().run(() -> handoff.encoder().finish(handoff.pending()));
        this.deliver(new Delivery(encoded.getData(), encoded.getStats(), handoff.frameId(), handoff.source()));
        synchronized (this.lock) {
          this.delivered++;
          this.lock.notifyAll();
        }
      }
    } catch (final InterruptedException exception) {
      Thread.currentThread().interrupt();
    } catch (final RuntimeException failure) {
      LOGGER.error(SENDER_FAILED, failure);
    } finally {
      this.stopPipeline(running);
      // also when a release cleared the pipeline first: the screen's thread must not wait for this one
      screenThread.interrupt();
    }
  }

  /** Sends one encoded frame to the viewers and counts it. */
  private void deliver(final Delivery delivery) {
    final byte[] frame = delivery.frame;
    final long frameId = delivery.frameId;
    final Mcv2Encoder.Stats stats = delivery.stats;
    final Mcv2FrameEvent event = new Mcv2FrameEvent();
    final boolean recorded = event.isEnabled();
    final long[] before = recorded ? this.linkCounts() : new long[LINK_COUNTS];
    final Screen current = this.screen;
    final int colors = current.channel().send(frame);
    if (colors >= 0) {
      this.frameListener.accept(frame);
    }
    if (recorded) {
      this.record(event, delivery, colors, before);
    }
    if (colors < 0) {
      LOGGER.warn(FRAME_TOO_LARGE, frameId, frame.length, current.configuration().getPageSlots());
      this.statistics.drop();
      return;
    }
    this.statistics.add(stats, colors);
  }

  /**
   * Carries out a step of the pacer, under the lock: on the dithered maps every viewer is dithered for; the screen's
   * page frames exist in the world, so the main thread brings them in line with the pacer's rung ({@link #sync()}).
   */
  private void follow(final Mcv2Pacer.Change change) {
    this.ditheredForAll = change.to().isDithered();
    if (this.ditheredForAll) {
      this.pending = null;
    }
    Bukkit.getScheduler().runTask(BukkitModule.getPlugin(), this::sync);
  }

  /**
   * Brings the screen in line with the pacer's rung, on the main thread, however many steps came since the last time:
   * on the dithered maps the page frames go, while the channel still measures who is near the wall; on an encoded rung
   * of another video size the screen is replaced by one at that size (the owner offers its pack), and the page frames
   * of an encoded rung are there.
   */
  void sync() {
    final Mcv2Pacer.Rung rung;
    synchronized (this.lock) {
      final Mcv2Pacer screenPacer = this.pacer;
      if (screenPacer == null) {
        return;
      }
      rung = screenPacer.getRung();
    }
    final Screen current = this.screen;
    final Mcv2Configuration configuration = current.configuration();
    if (rung.isDithered()) {
      if (this.opened) {
        current.channel().removeFrames();
        this.opened = false;
      }
      return;
    }
    Screen target = current;
    if (size(rung.width(), rung.height()) != size(configuration.getVideoWidth(), configuration.getVideoHeight())) {
      // closed even after the dithered maps removed its page frames, as it still measured the distances
      current.channel().close();
      this.opened = false;
      final Mcv2Configuration size = this.requested.withVideo(rung.width(), rung.height());
      target = new Screen(size, Preconditions.checkNotNull(this.resizer).resize(size));
      this.screen = target;
    }
    if (!this.opened) {
      target.channel().open();
      this.opened = true;
    }
  }

  /** Tells the server log and the pacing listener of a step, outside the lock. */
  private void announce(final Mcv2Pacer.Change change) {
    if (change.down()) {
      LOGGER.warn(PACING_STEP, change.describe());
    } else {
      LOGGER.info(PACING_STEP, change.describe());
    }
    this.pacingListener.accept(change);
  }

  /**
   * Stops the encoder, removes the page frames and clears the wall's maps for every viewer, those dithered for and those
   * decoding MCV2. Call on the main thread.
   *
   * <p>Worker joins are bounded; a worker that does not respond to interruption may outlive this call.
   * Fallback executor shutdown does not await termination. Release does not close the shared encoder pool or pack tracker.
   */
  @Override
  public void release() {
    final Thread thread;
    final Thread delivery;
    synchronized (this.lock) {
      this.running = false;
      this.lock.notifyAll();
      thread = this.worker;
      delivery = this.sender;
      this.worker = null;
      this.sender = null;
      this.pipeline = null;
      this.pacer = null;
    }
    stop(thread);
    stop(delivery);
    this.screen.channel().close();
    this.opened = false;
    // the wall's maps of a viewer decoding MCV2 still carry the screen's anchors, over which the client keeps drawing
    // its last decoded picture: they are cleared too, as the dithered maps clear those of the viewers dithered for
    final Set<UUID> decoding = new HashSet<>(this.requested.getViewers());
    decoding.removeAll(this.fallbackViewers);
    final int maps = this.requested.getColumns() * this.requested.getRows();
    MapPacketFactory.clear(decoding, this.requested.getMap(), maps);
    final ExecutorService ditheringOwn = this.ditheringThread;
    if (ditheringOwn != null) {
      ditheringOwn.shutdown();
    }
    final Fallback dithered = this.fallback;
    if (dithered != null) {
      dithered.result().release();
    }
  }

  /** A width and a height in one value, for comparing sizes. */
  private static long size(final int width, final int height) {
    return ((long) width << Integer.SIZE) | height;
  }
}
