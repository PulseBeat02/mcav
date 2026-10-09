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
import java.nio.ByteBuffer;
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
import me.brandonli.mcav.bukkit.media.mcv2.MCV2.Pool;
import me.brandonli.mcav.bukkit.media.mcv2.MCV2.Settings;
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
 * ({@link MCV2#begin}), then hands it to the screen's sender thread, which verifies it ({@link MCV2#finish})
 * and sends it while the next frame is searched: one frame is in flight at most, and a frame is sent only once
 * verified. A frame that fails its verification stops the screen. A keyframe the channel asks for while a frame is in
 * flight comes with the frame after it; another preset begins only after the frame in flight is sent. The screen's
 * threads only wait for the budget. When the encoder is slower than the video, frames that arrive while it works
 * replace each other and only the newest is encoded, which the encoder's previous-frame reference allows. The encoder
 * bounds every frame to what the screen's page slots carry ({@link MCV2#setFrameLimit}): a frame that would take
 * more pages is searched again at a higher lambda. One that still has more pages than the screen has slots is not sent,
 * and the next is a keyframe.
 *
 * <p>A {@link Mcv2Pacer} keeps the screen within what its budget sustains: when the frames take longer than the video
 * gives them, it first searches less hard, down the preset ladder from the screen's settings
 * ({@code DEFAULT}, then {@code FAST}) on one encoder that switches without a keyframe; then it encodes fewer frames, down to {@link Mcv2Pacer#MIN_FRAME_RATE} a second,
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

  private static final long JOIN_SECONDS = 10;

  // Vanilla can initialize a map after its first snapshot; unchanged deltas cannot repair it.
  private static final long FALLBACK_REFRESH_NANOSECONDS = TimeUnit.SECONDS.toNanos(4);

  private static final double NANOSECONDS_PER_MILLISECOND = 1e6;

  private static final int LINK_COUNTS = 3;

  private static final int SENT = 0;

  private static final int BEHIND = 1;

  private static final int UNDECODABLE = 2;

  private static final String FRAME_TOO_LARGE = "MCV2 frame {} of {} bytes needs more than {} pages; it is not sent";

  private static final String PACING_STEP = "{}";

  private static final String ENCODER_FAILED = "The MCV2 screen stops: encoding a frame failed";

  private static final String SENDER_FAILED = "The MCV2 screen stops: verifying or sending a frame failed";

  private static final double NANOSECONDS_PER_SECOND = 1e9;

  // Above one so a frame arriving early is kept, below two so a pause cannot release a burst.
  private static final double FRAME_CREDIT_CAP = 1.5;

  private static final double DEFAULT_COST = 1;

  private static final double FAST_COST = 0.9;

  private final Mcv2Configuration requested;

  private final @Nullable Fallback fallback;

  private final Set<UUID> fallbackViewers;

  private final Executor dithering;

  private final @Nullable ExecutorService ditheringThread;

  private final AtomicBoolean ditheringBusy = new AtomicBoolean();

  private final Object lock;

  private final Statistics statistics;

  private final LongSupplier clock;

  private final double framesPerNanosecond;

  private double frameCredit = FRAME_CREDIT_CAP;

  private long lastFrame = Long.MIN_VALUE;

  private long lastFallbackRefresh;

  private final List<Settings> ladder;

  private final List<Mcv2Pacer.Preset> presets;

  private final Function<Settings, MCV2> encoders;

  private final Mcv2LatestFrame<Arrival> pending;

  private boolean running;

  private @Nullable Thread worker;

  private @Nullable Thread sender;

  private @Nullable Pipeline pipeline;

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

  private record Fallback(CompressedMapResult result, DitherAlgorithm algorithm) {}

  static final class Arrival {

    private final byte[] pictureBytes;

    private final int width;

    private final int height;

    private final long arrived;

    private final Mcv2Pacer.Preset preset;

    Arrival(final byte[] pictureBytes, final int width, final int height, final long arrived, final Mcv2Pacer.Preset preset) {
      this.pictureBytes = pictureBytes;
      this.width = width;
      this.height = height;
      this.arrived = arrived;
      this.preset = preset;
    }
  }

  private record Handoff(MCV2 encoder, MCV2.Pending pending, long frameId, Arrival source) {}

  private record Pipeline(Pool budget, BlockingQueue<Handoff> queue) {}

  private static final class Delivery {

    private final byte[] frame;

    private final MCV2.Stats stats;

    private final long frameId;

    private final Arrival source;

    private Delivery(final byte[] frame, final MCV2.Stats stats, final long frameId, final Arrival source) {
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

    Statistics() {}

    synchronized void add(final MCV2.Stats stats, final int mapColors) {
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

  Mcv2Result(
    final Mcv2Configuration configuration,
    final Mcv2Channel channel,
    final @Nullable DitherAlgorithm fallbackAlgorithm,
    final LongSupplier clock,
    final @Nullable Executor dithering
  ) {
    this(configuration, channel, fallbackAlgorithm, clock, dithering, settings -> configuration.getEncoderPool().encoder(settings, true));
  }

  Mcv2Result(
    final Mcv2Configuration configuration,
    final Mcv2Channel channel,
    final @Nullable DitherAlgorithm fallbackAlgorithm,
    final LongSupplier clock,
    final @Nullable Executor dithering,
    final Function<Settings, MCV2> encoders
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
    this.pending = new Mcv2LatestFrame<>();
    this.statistics = new Statistics();
    this.clock = clock;
    this.lastFallbackRefresh = clock.getAsLong();
    this.framesPerNanosecond = configuration.getMaxFrameRate() / NANOSECONDS_PER_SECOND;
    this.ladder = ladder(configuration.getSettings());
    this.presets = presets(this.ladder);
    this.encoders = encoders;
  }

  private static List<Settings> ladder(final Settings settings) {
    if (settings.fast()) {
      return List.of(settings);
    }
    final double lambda = (settings.lambda() * Settings.FAST.lambda()) / Settings.DEFAULT.lambda();
    return List.of(settings, new Settings(lambda, true));
  }

  private static List<Mcv2Pacer.Preset> presets(final List<Settings> ladder) {
    if (ladder.size() == 1) {
      return List.of(Mcv2Pacer.Preset.ONLY);
    }
    final List<Mcv2Pacer.Preset> presets = new ArrayList<>();
    for (final Settings settings : ladder) {
      presets.add(preset(settings));
    }
    return List.copyOf(presets);
  }

  private static Mcv2Pacer.Preset preset(final Settings settings) {
    if (settings.fast()) {
      return new Mcv2Pacer.Preset("FAST", FAST_COST);
    }
    return new Mcv2Pacer.Preset("DEFAULT", DEFAULT_COST);
  }

  private static MapConfiguration fallbackConfiguration(final Mcv2Configuration configuration, final Set<UUID> viewers) {
    return MapConfiguration.builder()
      .map(configuration.getMap())
      .mapBlockWidth(configuration.getColumns())
      .mapBlockHeight(configuration.getRows())
      // Dithered maps need wall scaling to avoid cropping or centering a smaller video.

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
    final Pool encoderPool = this.requested.getEncoderPool();
    final MCV2 encoder = this.encoders.apply(this.ladder.getFirst());

    final Thread thread = Thread.ofPlatform()
      .daemon()
      .name("mcav-mcv2-screen")
      .unstarted(() -> this.encodeLoop(encoder));

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
      this.pending.open();
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

    final Set<UUID> others = everyoneDithered ? current.channel().near(this.requested.getViewers()) : current.channel().update();
    this.fallbackViewers.retainAll(others);
    this.fallbackViewers.addAll(others);
    final int width = current.configuration().getVideoWidth();
    final int height = current.configuration().getVideoHeight();
    if (data.getWidth() != width || data.getHeight() != height) {
      new ResizeFilter(width, height).applyFilter(data);
    }

    if (encode && !current.channel().getRecipients().isEmpty()) {
      // A full-frame ARGB copy at 1080p adds an 8 MB G1 humongous allocation per frame.

      final Arrival arrival = new Arrival(rgb(data.getData(), width * height), width, height, System.currentTimeMillis(), preset);
      this.pending.offer(arrival);
    }
    final Fallback dithered = this.fallback;
    if (dithered != null && !others.isEmpty() && this.ditheringBusy.compareAndSet(false, true)) {
      // ImageBuffer replaces its pixel array, allowing asynchronous reads without a copy.

      final int[] packedColors = data.getPixels();
      try {
        this.dithering.execute(() -> this.dither(dithered, packedColors, width, height));
      } catch (final RejectedExecutionException released) {
        this.ditheringBusy.set(false);
      }
    }
    return true;
  }

  // A client decodes at most one frame per frame it draws, and a frame it misses breaks the P frames after it.
  boolean takeFrame(final long now) {
    if (this.framesPerNanosecond == 0) {
      return true;
    }
    synchronized (this.lock) {
      if (this.lastFrame != Long.MIN_VALUE) {
        this.frameCredit = Math.min(FRAME_CREDIT_CAP, this.frameCredit + (now - this.lastFrame) * this.framesPerNanosecond);
      }
      this.lastFrame = now;
      if (this.frameCredit < 1) {
        return false;
      }
      this.frameCredit -= 1;
      return true;
    }
  }

  private void dither(final Fallback dithered, final int[] packedColors, final int width, final int height) {
    try (final ImageBuffer frame = ImageBuffer.buffer(packedColors, width, height)) {
      final CompressedMapResult maps = dithered.result();
      final long now = this.clock.getAsLong();
      if (now - this.lastFallbackRefresh >= FALLBACK_REFRESH_NANOSECONDS) {
        maps.refresh();
        this.lastFallbackRefresh = now;
      }
      maps.process(frame, dithered.algorithm());
    } finally {
      this.ditheringBusy.set(false);
    }
  }

  static byte[] rgb(final ByteBuffer sourceBuffer, final int pixels) {
    final byte[] pictureBytes = new byte[pixels * Mcv2Decoder.CHANNELS];
    sourceBuffer.get(sourceBuffer.position(), pictureBytes);
    for (int pixelOffset = 0; pixelOffset < pictureBytes.length; pixelOffset += Mcv2Decoder.CHANNELS) {
      final byte blue = pictureBytes[pixelOffset];
      pictureBytes[pixelOffset] = pictureBytes[pixelOffset + 2];
      pictureBytes[pixelOffset + 2] = blue;
    }
    return pictureBytes;
  }

  private long[] linkCounts() {
    final long[] counts = new long[LINK_COUNTS];
    for (final Mcv2Link link : this.screen.channel().getLinks().values()) {
      counts[SENT] += link.getSent();
      counts[BEHIND] += link.getBehind();
      counts[UNDECODABLE] += link.getUndecodable();
    }
    return counts;
  }

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
    event.fingerprint = Mcv2FrameEvent.fingerprint(delivery.source.pictureBytes, delivery.source.width, delivery.source.height);
    event.commit();
  }

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

  void encodeLoop(final MCV2 first) {
    final Pipeline own;
    synchronized (this.lock) {
      own = this.pipeline;
    }
    long frameId = this.requested.getFirstFrameId();
    MCV2 encoder = first;
    try {
      for (Arrival arrival = this.take(); arrival != null; arrival = this.take()) {
        final Settings settings = this.settingsFor(arrival);
        if (!settings.equals(encoder.getSettings())) {
          this.drain();
          encoder = this.encoderFor(settings, encoder);
        }
        this.send(encoder, arrival, frameId);
        frameId = (frameId + 1) & Mcv2Decoder.MAX_U32;
      }
    } catch (final InterruptedException exception) {
      Thread.currentThread().interrupt();
    } catch (final RuntimeException failure) {
      LOGGER.error(ENCODER_FAILED, failure);
    } finally {
      this.stopPipeline(own);
    }
  }

  Settings settingsFor(final Arrival arrival) {
    return this.ladder.get(this.presets.indexOf(arrival.preset));
  }

  MCV2 encoderFor(final Settings settings, final MCV2 encoder) {
    encoder.switchTo(settings);
    return encoder;
  }

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

  @Nullable Arrival poll() {
    return this.pending.poll();
  }

  @Nullable Arrival take() throws InterruptedException {
    return this.pending.take();
  }

  void send(final MCV2 encoder, final Arrival arrival, final long frameId) throws InterruptedException {
    final Screen current = this.screen;
    if (current.channel().takeKeyframeRequest()) {
      encoder.requestKeyframe();
    }

    encoder.setFrameLimit(current.configuration().getPageSlots() * TransportPages.capacity());
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
      final byte[] frame = encoder.encode(arrival.pictureBytes, width, height, frameId);
      final MCV2.Stats stats = Preconditions.checkNotNull(encoder.getStats());
      isKeyframe = stats.keyframe();
      delivery = new Delivery(frame, stats, frameId, arrival);
    } else {
      final MCV2.Pending pending = running.budget().run(() -> encoder.begin(arrival.pictureBytes, width, height, frameId));
      isKeyframe = pending.isKeyframe();
      // Count before handoff so drain cannot overlook a frame the sender already owns.
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
        change = screenPacer.encoded((finished - started) / NANOSECONDS_PER_MILLISECOND, isKeyframe, finished);
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

  void drain() throws InterruptedException {
    synchronized (this.lock) {
      while (this.running && this.delivered < this.handed) {
        this.lock.wait();
      }
    }
  }

  private void stopPipeline(final @Nullable Pipeline stopped) {
    final Thread screenThread;
    final Thread delivery;
    synchronized (this.lock) {
      final Pipeline current = this.pipeline;

      if (stopped == null || !Objects.equals(stopped, current)) {
        return;
      }
      this.running = false;
      this.pending.close();
      this.lock.notifyAll();

      screenThread = Objects.requireNonNull(this.worker, "A running pipeline has its screen's thread");
      delivery = Objects.requireNonNull(this.sender, "A running pipeline has its sender");
    }

    screenThread.interrupt();
    delivery.interrupt();
  }

  private void deliverLoop(final Pipeline running, final Thread screenThread) {
    try {
      while (true) {
        final Handoff handoff = running.queue().take();
        final MCV2.Encoded encoded = running.budget().run(() -> handoff.encoder().finish(handoff.pending()));
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
      // Interrupt even after release clears the pipeline, or the screen thread can wait forever.
      screenThread.interrupt();
    }
  }

  private void deliver(final Delivery delivery) {
    final byte[] frame = delivery.frame;
    final long frameId = delivery.frameId;
    final MCV2.Stats stats = delivery.stats;
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

  private void follow(final Mcv2Pacer.Change change) {
    this.ditheredForAll = change.to().isDithered();
    if (this.ditheredForAll) {
      this.pending.clear();
    }
    Bukkit.getScheduler().runTask(BukkitModule.getPlugin(), this::sync);
  }

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
      this.pending.close();
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
    // Encoded viewers retain anchor maps, which keep their last decoded picture visible.

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

  private static long size(final int width, final int height) {
    return ((long) width << Integer.SIZE) | height;
  }
}
