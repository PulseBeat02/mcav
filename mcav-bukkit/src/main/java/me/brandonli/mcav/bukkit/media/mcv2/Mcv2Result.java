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

import static java.util.Objects.requireNonNull;

import com.google.common.base.Preconditions;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
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
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2.Pool;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2.Settings;
import me.brandonli.mcav.bukkit.media.mcv2.transport.TransportPages;
import me.brandonli.mcav.bukkit.media.result.CompressedMapResult;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.metadata.OriginalVideoMetadata;
import me.brandonli.mcav.media.player.pipeline.filter.video.FunctionalVideoFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.ResizeFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.DitherAlgorithm;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.checkerframework.checker.initialization.qual.UnderInitialization;
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
 * screen of the server shares unless it was given its own, in two steps on the screen's thread: it searches and writes
 * a frame ({@link Mcv2#begin}), verifies it ({@link Mcv2#finish}) and hands it to the screen's sender thread, which
 * sends it while the next frame is encoded. One frame is in flight at most, a frame is sent only once verified, and the
 * next frame is searched only after that, so a viewer's frames leave at least one search and verification apart. A
 * frame that fails its verification stops the screen. A keyframe the channel asks for while a frame is encoded comes
 * with the frame after it; another preset begins only after the frame in flight is sent. The screen also asks for a
 * keyframe once its last keyframe is four seconds old, so a client that missed a frame waits at most four seconds and
 * one frame for a picture it can decode. The screen's threads only wait for the budget. When the encoder is slower than
 * the video, frames that arrive while it works replace each other and only the newest is encoded, which the encoder's
 * previous-frame reference allows. The encoder bounds every frame to what the screen's page slots carry
 * ({@link Mcv2#setFrameLimit}): a frame that would take more pages is searched again at a higher lambda. One that still
 * has more pages than the screen has slots is not sent, and the next is a keyframe.
 *
 * <p>A {@link Mcv2Pacer} keeps the screen within what its budget sustains: when the frames take longer than the video
 * gives them, it first searches less hard, down the preset ladder from the screen's settings
 * ({@code DEFAULT}, then {@code FAST}) on one encoder that switches without a keyframe; then it encodes fewer frames,
 * down to {@link Mcv2Pacer#MINIMUM_FRAMES_PER_SECOND} a second,
 * then shows a smaller video when the owner offers smaller sizes ({@link #setSmallerSizes}: each size has its own pack),
 * and when even that is too much, every viewer is shown the dithered maps, which need no encoder, until a later try
 * finds room again. Every step is logged, a step down as a warning, and handed to the
 * {@linkplain #setPacingListener(Consumer) pacing listener}, so the operator learns what was chosen and why. A result
 * without dithered maps stays at its lowest frame rate instead.
 *
 * <p>While no frame arrives, such as while the video is paused or a page does not change, the screen checks every
 * second for viewers whose pack loaded meanwhile and shows them the screen. It sends the last picture once more as a
 * keyframe after about three seconds without a frame, and again when it starts showing a viewer, so every client can
 * decode the newest frame.
 *
 * <p>Call {@link #start()} and {@link #release()} on the main thread.
 *
 * <p>Use one start/release lifecycle per instance: configure callbacks and smaller sizes before start, stop
 * submitting frames before release, and create a new result for another lifecycle. Repeated start is not guarded,
 * and the fallback executor is shut down permanently by release. The result owns its worker threads, encoders,
 * channel screen and fallback result. The pack tracker, fallback algorithm and encoder pool remain caller-owned.
 * Serialize {@link #applyFilter} calls; copied RGB/pixel data is handed to workers, so callers may reuse or close an
 * input buffer after the call returns. Do not mutate it during conversion.
 *
 * {@snippet :
 * final Mcv2PackServer.Lease lease = packs.open(requested);
 * final Mcv2Configuration configuration = lease.getConfiguration();
 * final Mcv2Viewers viewers = packs.getViewers();
 * final DitherAlgorithm fallback = DitherAlgorithm.filterLite();
 * final Mcv2Result result = new Mcv2Result(configuration, viewers, fallback);
 * result.start();
 *
 * final VideoPipelineStep pipeline = VideoPipelineStep.of(result);
 * final VideoAttachableCallback video = player.getVideoAttachableCallback();
 * video.attach(pipeline);
 *
 * // when the video is over
 * result.release();
 * lease.close();
 * }
 *
 * @since 1.0.0
 */
public final class Mcv2Result implements FunctionalVideoFilter {

  private static final String LISTENER_NULL_MESSAGE = "Listener must not be null!";

  private static final Logger LOGGER = LoggerFactory.getLogger(Mcv2Result.class);

  private static final long JOIN_SECONDS = 10;

  // Vanilla can initialize a map after its first snapshot; unchanged deltas cannot repair it.
  private static final long FALLBACK_REFRESH_NANOSECONDS = TimeUnit.SECONDS.toNanos(4);

  // A viewer who missed a frame waits for a keyframe, and the encoder's 120 frames take long at low frame rates.
  private static final long KEYFRAME_PERIOD_NANOSECONDS = TimeUnit.SECONDS.toNanos(4);

  // A paused video or a still page sends no frames, yet a viewer who starts watching needs a keyframe.
  private static final long IDLE_CHECK_TICKS = 20L;

  // A source at about one frame a second leaves single idle checks between its frames.
  private static final int IDLE_CHECKS_BEFORE_REPEAT = 3;

  private static final double NANOSECONDS_PER_MILLISECOND = 1e6;
  private static final double NANOSECONDS_PER_SECOND = 1e9;

  private static final int LINK_COUNTS = 3;
  private static final int SENT = 0;
  private static final int BEHIND = 1;
  private static final int UNDECODABLE = 2;

  private static final String PACING_STEP = "{} during playback.";

  private static final String DITHER_THREAD_NAME = "mcav-mcv2-dither";
  private static final String SCREEN_THREAD_NAME = "mcav-mcv2-screen";
  private static final String SENDER_THREAD_NAME = "mcav-mcv2-sender";

  // Above one so a frame arriving early is kept, below two so a pause cannot release a burst.
  private static final double FRAME_CREDIT_CAP = 1.5;

  private static final double DEFAULT_COST = 1;
  private static final double FAST_COST = 0.9;

  private static final int LAST_CHANNEL_OFFSET = 2;

  private final Mcv2Configuration requested;

  private final @Nullable Fallback fallback;
  private final Set<UUID> fallbackViewers;
  private final Executor dithering;
  private final @Nullable ExecutorService ditheringThread;
  private final AtomicBoolean ditheringBusy;
  private final AtomicBoolean frameTaken;

  private final Object lock;
  private final Statistics statistics;
  private final LongSupplier clock;
  private final double framesPerNanosecond;

  private final List<Settings> ladder;
  private final List<Mcv2Pacer.Preset> presets;
  private final Function<Settings, Mcv2> encoders;
  private final Mcv2LatestFrame<Arrival> pending;

  private volatile @Nullable Arrival lastArrival;

  private double frameCredit;
  private long lastFrame;
  private long lastFallbackRefresh;
  private long lastKeyframe;

  private boolean running;
  private @Nullable Thread worker;
  private @Nullable Thread sender;
  private @Nullable Pipeline pipeline;
  private long handed;
  private long delivered;

  private @Nullable Mcv2Pacer pacer;
  private boolean ditheredForAll;

  private Consumer<Mcv2Pacer.Change> pacingListener;
  private volatile Consumer<byte[]> frameListener;

  private volatile Screen screen;
  private boolean opened;
  private boolean repeated;
  private int idleChecks;
  private @Nullable BukkitTask idleCheck;

  private List<int[]> smaller;
  private @Nullable Resizer resizer;

  /**
   * Creates a result.
   *
   * @param configuration     the screen
   * @param viewers           who has the pack loaded
   * @param fallbackAlgorithm how the video is dithered for viewers without the pack, or null to show them nothing
   * @throws NullPointerException if configuration or viewers is null
   * @since 1.0.0
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
    this(configuration, channel, fallbackAlgorithm, clock, dithering, settings ->
      configuration.getEncoderPool().encoder(settings, Mcv2.Verification.VERIFIED)
    );
  }

  Mcv2Result(
    final Mcv2Configuration configuration,
    final Mcv2Channel channel,
    final @Nullable DitherAlgorithm fallbackAlgorithm,
    final LongSupplier clock,
    final @Nullable Executor dithering,
    final Function<Settings, Mcv2> encoders
  ) {
    Preconditions.checkNotNull(configuration, "Configuration must not be null!");
    Preconditions.checkNotNull(channel, "Channel must not be null!");

    this.ditheringBusy = new AtomicBoolean();
    this.frameTaken = new AtomicBoolean();
    this.frameCredit = FRAME_CREDIT_CAP;
    this.lastFrame = Long.MIN_VALUE;
    this.pacingListener = _ -> {};
    this.frameListener = _ -> {};
    this.opened = true;
    this.smaller = List.of();
    this.requested = configuration;
    this.screen = new Screen(configuration, channel);
    this.fallbackViewers = ConcurrentHashMap.newKeySet();
    this.fallback = this.createFallback(configuration, this.fallbackViewers, fallbackAlgorithm);
    if (dithering == null) {
      final ExecutorService thread = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name(DITHER_THREAD_NAME).factory());
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
    this.lastKeyframe = clock.getAsLong();
    this.framesPerNanosecond = configuration.getMaximumFrameRate() / NANOSECONDS_PER_SECOND;
    this.ladder = ladder(configuration.getSettings());
    this.presets = this.presets(this.ladder);
    this.encoders = encoders;
  }

  /**
   * Spawns the screen's page frames, starts the encoder and the check for a screen without frames. Call on the main
   * thread.
   *
   * @since 1.0.0
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
    final Mcv2 encoder = this.encoders.apply(this.ladder.getFirst());

    final Thread worker = Thread.ofPlatform()
      .daemon()
      .name(SCREEN_THREAD_NAME)
      .unstarted(() -> this.encodeLoop(encoder));

    final BlockingQueue<Delivery> queue = new SynchronousQueue<>();
    final Pipeline started = new Pipeline(encoderPool, queue);
    final Thread sender = Thread.ofPlatform()
      .daemon()
      .name(SENDER_THREAD_NAME)
      .unstarted(() -> this.deliverLoop(started, worker));

    this.resetPacer();
    synchronized (this.lock) {
      this.pipeline = started;
      this.worker = worker;
      this.sender = sender;
      this.handed = 0;
      this.delivered = 0;
      this.running = true;
      this.pending.open();
    }

    sender.start();
    worker.start();

    final BukkitScheduler scheduler = Bukkit.getScheduler();
    final Plugin plugin = BukkitModule.getPlugin();
    this.idleCheck = scheduler.runTaskTimer(plugin, this::repeatWhileIdle, IDLE_CHECK_TICKS, IDLE_CHECK_TICKS);
  }

  /**
   * Stops the encoder and the check for a screen without frames, removes the page frames and clears the wall's maps for
   * every viewer, those dithered for and those decoding MCV2. Call on the main thread.
   *
   * <p>Worker joins are bounded; a worker that does not respond to interruption may outlive this call.
   * Fallback executor shutdown does not await termination. Release does not close the shared encoder pool or pack tracker.
   *
   * @since 1.0.0
   */
  @Override
  public void release() {
    this.cancelIdleCheck();

    final Thread worker;
    final Thread sender;
    synchronized (this.lock) {
      this.running = false;
      this.pending.close();
      this.lock.notifyAll();
      worker = this.worker;
      sender = this.sender;
      this.worker = null;
      this.sender = null;
      this.pipeline = null;
      this.pacer = null;
    }

    this.stop(worker);
    this.stop(sender);
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

  private void cancelIdleCheck() {
    final BukkitTask check = this.idleCheck;
    if (check != null) {
      check.cancel();
      this.idleCheck = null;
    }
  }

  private void stop(final @Nullable Thread thread) {
    if (thread == null) {
      return;
    }

    thread.interrupt();
    try {
      thread.join(TimeUnit.SECONDS.toMillis(JOIN_SECONDS));
    } catch (final InterruptedException exception) {
      final Thread current = Thread.currentThread();
      current.interrupt();
    }
  }

  private @Nullable Fallback createFallback(
    @UnderInitialization Mcv2Result this,
    final Mcv2Configuration configuration,
    final Set<UUID> viewers,
    final @Nullable DitherAlgorithm fallbackAlgorithm
  ) {
    if (fallbackAlgorithm == null) {
      return null;
    }

    final MapConfiguration mapConfiguration = this.fallbackConfiguration(configuration, viewers);
    final CompressedMapResult result = new CompressedMapResult(mapConfiguration);
    return new Fallback(result, fallbackAlgorithm);
  }

  private static List<Settings> ladder(final Settings settings) {
    if (settings.isFast()) {
      return List.of(settings);
    }

    // Every finite product is divided as before, so 72 still gives exactly 55; one that overflows is scaled instead.
    final double product = settings.getLambda() * Settings.FAST.getLambda();
    final double lambda = Double.isFinite(product)
      ? product / Settings.DEFAULT.getLambda()
      : settings.getLambda() * (Settings.FAST.getLambda() / Settings.DEFAULT.getLambda());
    final boolean fast = true;
    return List.of(settings, Settings.of(lambda, fast));
  }

  private List<Mcv2Pacer.Preset> presets(@UnderInitialization Mcv2Result this, final List<Settings> ladder) {
    if (ladder.size() == 1) {
      return List.of(Mcv2Pacer.Preset.ONLY);
    }

    final List<Mcv2Pacer.Preset> presets = new ArrayList<>();
    for (final Settings settings : ladder) {
      presets.add(this.preset(settings));
    }

    return List.copyOf(presets);
  }

  private Mcv2Pacer.Preset preset(@UnderInitialization Mcv2Result this, final Settings settings) {
    if (settings.isFast()) {
      return Mcv2Pacer.Preset.of("FAST", FAST_COST);
    }

    return Mcv2Pacer.Preset.of("DEFAULT", DEFAULT_COST);
  }

  private MapConfiguration fallbackConfiguration(
    @UnderInitialization Mcv2Result this,
    final Mcv2Configuration configuration,
    final Set<UUID> viewers
  ) {
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
   * Hands the frame to the encoder for the viewers with the pack, and dithers it for the others.
   *
   * @param data     the frame, resized in place to the screen's video size
   * @param metadata the metadata of the original video, which is not used
   * @return false when rate limiting skips the frame before conversion, otherwise true because it may
   *         have been resized; this does not report encoding, sending or client decoding success
   * @throws NullPointerException if {@code data} or {@code metadata} is null
   * @since 1.0.0
   */
  @Override
  public boolean applyFilter(final ImageBuffer data, final OriginalVideoMetadata metadata) {
    Preconditions.checkNotNull(data, "Frame must not be null!");
    Preconditions.checkNotNull(metadata, "Metadata must not be null!");

    if (!this.takeFrame(this.clock.getAsLong())) {
      return false;
    }

    this.frameTaken.set(true);

    final boolean encode;
    final boolean everyoneDithered;
    Mcv2Pacer.Preset preset = this.presets.getFirst();
    Optional<Mcv2Pacer.Change> tried = Optional.empty();
    synchronized (this.lock) {
      final Mcv2Pacer screenPacer = this.pacer;
      if (screenPacer != null) {
        tried = this.followArrival(screenPacer);
        encode = screenPacer.isEncoded();
        preset = screenPacer.getRung().getPreset();
      } else {
        encode = true;
      }

      everyoneDithered = this.ditheredForAll;
    }

    tried.ifPresent(this::announce);

    final Screen current = this.screen;

    final Set<UUID> others = this.selectFallbackViewers(current, everyoneDithered);
    final int width = current.configuration().getVideoWidth();
    final int height = current.configuration().getVideoHeight();
    this.resizeFrame(data, width, height);
    this.offerFrame(data, current, encode, preset, width, height);
    this.offerDithering(data, others, width, height);

    return true;
  }

  private Set<UUID> selectFallbackViewers(final Screen current, final boolean everyoneDithered) {
    final Set<UUID> others;
    if (everyoneDithered) {
      others = current.channel().near(this.requested.getViewers());
    } else {
      others = current.channel().update();
    }
    this.fallbackViewers.retainAll(others);
    this.fallbackViewers.addAll(others);
    return others;
  }

  private void resizeFrame(final ImageBuffer data, final int width, final int height) {
    if (data.getWidth() != width || data.getHeight() != height) {
      new ResizeFilter(width, height).applyFilter(data);
    }
  }

  private void offerFrame(
    final ImageBuffer data,
    final Screen current,
    final boolean encode,
    final Mcv2Pacer.Preset preset,
    final int width,
    final int height
  ) {
    if (encode && !current.channel().getRecipients().isEmpty()) {
      // A full-frame ARGB copy at 1080p adds an 8 MB G1 humongous allocation per frame.
      final Arrival arrival = new Arrival(rgbFromBgr(data.getData(), width * height), width, height, System.currentTimeMillis(), preset);
      this.lastArrival = arrival;
      this.pending.offer(arrival);
    }
  }

  private void offerDithering(final ImageBuffer data, final Set<UUID> others, final int width, final int height) {
    final Fallback dithered = this.fallback;
    if (dithered != null && !others.isEmpty() && this.ditheringBusy.compareAndSet(false, true)) {
      // ImageBuffer replaces its pixel array, allowing asynchronous reads without a copy.
      final int[] packedColors = data.getPixels();
      try {
        this.dithering.execute(() -> this.dither(dithered, packedColors, width, height));
      } catch (final RejectedExecutionException exception) {
        this.ditheringBusy.set(false);
      }
    }
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

  void repeatWhileIdle() {
    if (this.frameTaken.getAndSet(false)) {
      this.repeated = false;
      this.idleChecks = 0;
      return;
    }

    this.idleChecks++;

    final Mcv2Pacer.Preset preset;
    synchronized (this.lock) {
      if (this.ditheredForAll) {
        return;
      }

      preset = this.currentPreset();
    }

    final Screen current = this.screen;
    this.selectFallbackViewers(current, false);

    final @Nullable Arrival last = this.lastArrival;
    if (last != null && this.isRepeatWanted(current, last)) {
      this.repeat(current.channel(), last, preset);
    }
  }

  private Mcv2Pacer.Preset currentPreset() {
    final Mcv2Pacer screenPacer = this.pacer;
    if (screenPacer == null) {
      return this.presets.getFirst();
    }

    final Mcv2Pacer.Rung rung = screenPacer.getRung();
    return rung.getPreset();
  }

  private boolean isRepeatWanted(final Screen current, final Arrival last) {
    final Mcv2Configuration configuration = current.configuration();
    if (last.width != configuration.getVideoWidth() || last.height != configuration.getVideoHeight()) {
      return false;
    }

    final Mcv2Channel channel = current.channel();
    final Set<UUID> recipients = channel.getRecipients();
    if (recipients.isEmpty()) {
      return false;
    }

    final boolean stopped = this.idleChecks >= IDLE_CHECKS_BEFORE_REPEAT;
    return (stopped && !this.repeated) || channel.isKeyframeRequested();
  }

  private void repeat(final Mcv2Channel channel, final Arrival last, final Mcv2Pacer.Preset preset) {
    channel.requestKeyframe();

    final long arrived = System.currentTimeMillis();
    final Arrival again = new Arrival(last.pictureBytes, last.width, last.height, arrived, preset);
    this.pending.offer(again);
    this.repeated = true;
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

  static byte[] rgbFromBgr(final ByteBuffer sourceBuffer, final int pixels) {
    final byte[] pictureBytes = new byte[pixels * Mcv2Decoder.CHANNELS];
    sourceBuffer.get(sourceBuffer.position(), pictureBytes);
    for (int pixelOffset = 0; pixelOffset < pictureBytes.length; pixelOffset += Mcv2Decoder.CHANNELS) {
      final byte blue = pictureBytes[pixelOffset];
      pictureBytes[pixelOffset] = pictureBytes[pixelOffset + LAST_CHANNEL_OFFSET];
      pictureBytes[pixelOffset + LAST_CHANNEL_OFFSET] = blue;
    }

    return pictureBytes;
  }

  private Optional<Mcv2Pacer.Change> followArrival(final Mcv2Pacer screenPacer) {
    final Optional<Mcv2Pacer.Change> change = screenPacer.arrive(this.clock.getAsLong());
    change.ifPresent(this::follow);

    return change;
  }

  void encodeLoop(final Mcv2 first) {
    final Pipeline own;
    synchronized (this.lock) {
      own = this.pipeline;
    }

    try {
      this.encodeArrivals(first);
    } catch (final InterruptedException exception) {
      final Thread current = Thread.currentThread();
      current.interrupt();
    } catch (final RuntimeException exception) {
      LOGGER.error("The MCV2 screen stops: encoding a frame failed.", exception);
    } finally {
      this.stopPipeline(own);
    }
  }

  private void encodeArrivals(final Mcv2 first) throws InterruptedException {
    long frameId = this.requested.getFirstFrameId();
    Mcv2 encoder = first;
    for (Arrival arrival = this.take(); arrival != null; arrival = this.take()) {
      final Settings settings = this.settingsFor(arrival);
      if (!settings.equals(encoder.getSettings())) {
        this.drain();
        encoder = this.switchEncoder(settings, encoder);
      }
      this.send(encoder, arrival, frameId);
      frameId = (frameId + 1) & Mcv2Decoder.MAXIMUM_UNSIGNED_INT;
    }
  }

  Settings settingsFor(final Arrival arrival) {
    return this.ladder.get(this.presets.indexOf(arrival.preset));
  }

  Mcv2 switchEncoder(final Settings settings, final Mcv2 encoder) {
    encoder.setSettings(settings);
    return encoder;
  }

  void resetPacer() {
    final List<int[]> sizes = new ArrayList<>();
    sizes.add(new int[] { this.requested.getVideoWidth(), this.requested.getVideoHeight() });
    sizes.addAll(this.smaller);
    final Mcv2Pacer.Floor floor = this.fallback != null ? Mcv2Pacer.Floor.DITHERED_MAPS : Mcv2Pacer.Floor.LOWEST_ENCODED_RUNG;
    final Mcv2Pacer screenPacer = new Mcv2Pacer(sizes, this.presets, floor);
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

  void send(final Mcv2 encoder, final Arrival arrival, final long frameId) throws InterruptedException {
    final Screen current = this.screen;
    final long now = this.clock.getAsLong();
    final boolean requested = current.channel().takeKeyframeRequest();
    if (requested || this.isKeyframeDue(now)) {
      encoder.requestKeyframe();
    }

    encoder.setFrameLimit(current.configuration().getPageSlots() * TransportPages.capacity());
    final Pipeline running;
    synchronized (this.lock) {
      running = this.pipeline;
    }

    final long started = this.clock.getAsLong();
    final boolean isKeyframe;
    Delivery delivery = null;
    if (running == null) {
      delivery = this.encodeSynchronously(encoder, arrival, frameId);
      isKeyframe = delivery.statistics.isKeyframe();
    } else {
      isKeyframe = this.handoff(running, encoder, arrival, frameId);
    }

    if (isKeyframe) {
      this.lastKeyframe = now;
    }

    this.followEncodeTime(started, isKeyframe);

    if (delivery != null) {
      this.deliver(delivery);
    }
  }

  private boolean isKeyframeDue(final long now) {
    final long sinceKeyframe = now - this.lastKeyframe;
    return sinceKeyframe >= KEYFRAME_PERIOD_NANOSECONDS;
  }

  private Delivery encodeSynchronously(final Mcv2 encoder, final Arrival arrival, final long frameId) {
    final int width = arrival.width;
    final int height = arrival.height;
    final byte[] frame = encoder.encode(arrival.pictureBytes, width, height, frameId);
    final Mcv2.Statistics statistics = encoder.getStatistics().orElseThrow();
    return new Delivery(frame, statistics, frameId, arrival);
  }

  private boolean handoff(final Pipeline running, final Mcv2 encoder, final Arrival arrival, final long frameId)
    throws InterruptedException {
    final int width = arrival.width;
    final int height = arrival.height;
    final Pool budget = running.budget();
    final Mcv2.Pending pending = budget.runInterruptibly(() -> encoder.begin(arrival.pictureBytes, width, height, frameId));
    final boolean isKeyframe = pending.isKeyframe();
    final Mcv2.Encoded encoded = budget.runInterruptibly(() -> encoder.finish(pending));
    final Delivery delivery = new Delivery(encoded.getData(), encoded.getStatistics(), frameId, arrival);
    // Count before handoff so drain cannot overlook a frame the sender already owns.
    synchronized (this.lock) {
      this.handed++;
    }

    running.queue().put(delivery);

    return isKeyframe;
  }

  private void followEncodeTime(final long started, final boolean isKeyframe) {
    final long finished = this.clock.getAsLong();
    final double encodeMilliseconds = (finished - started) / NANOSECONDS_PER_MILLISECOND;
    final Mcv2FrameKind kind = isKeyframe ? Mcv2FrameKind.KEYFRAME : Mcv2FrameKind.PREDICTED;
    Optional<Mcv2Pacer.Change> change = Optional.empty();
    synchronized (this.lock) {
      final Mcv2Pacer screenPacer = this.pacer;
      if (screenPacer != null) {
        change = this.followEncoding(screenPacer, encodeMilliseconds, kind, finished);
      }
    }

    change.ifPresent(this::announce);
  }

  private Optional<Mcv2Pacer.Change> followEncoding(
    final Mcv2Pacer screenPacer,
    final double encodeMilliseconds,
    final Mcv2FrameKind kind,
    final long finished
  ) {
    final Optional<Mcv2Pacer.Change> change = screenPacer.reportEncodedFrame(encodeMilliseconds, kind, finished);
    change.ifPresent(this::follow);

    return change;
  }

  void drain() throws InterruptedException {
    synchronized (this.lock) {
      while (this.running && this.delivered < this.handed) {
        this.lock.wait();
      }
    }
  }

  private void stopPipeline(final @Nullable Pipeline stopped) {
    final Thread worker;
    final Thread sender;
    synchronized (this.lock) {
      final Pipeline current = this.pipeline;
      if (stopped == null || !Objects.equals(stopped, current)) {
        return;
      }

      this.running = false;
      this.pending.close();
      this.lock.notifyAll();

      worker = requireNonNull(this.worker, "A running pipeline has its screen's thread!");
      sender = requireNonNull(this.sender, "A running pipeline has its sender!");
    }

    worker.interrupt();
    sender.interrupt();
  }

  private void deliverLoop(final Pipeline running, final Thread worker) {
    try {
      while (true) {
        this.deliverNext(running);
      }
    } catch (final InterruptedException exception) {
      final Thread current = Thread.currentThread();
      current.interrupt();
    } catch (final RuntimeException exception) {
      LOGGER.error("The MCV2 screen stops: sending a frame failed.", exception);
    } finally {
      this.stopPipeline(running);
      // Interrupt even after release clears the pipeline, or the screen thread can wait forever.
      worker.interrupt();
    }
  }

  private void deliverNext(final Pipeline running) throws InterruptedException {
    final Delivery delivery = running.queue().take();
    this.deliver(delivery);

    synchronized (this.lock) {
      this.delivered++;
      this.lock.notifyAll();
    }
  }

  private void deliver(final Delivery delivery) {
    final byte[] frame = delivery.frame;
    final long frameId = delivery.frameId;
    final Mcv2.Statistics statistics = delivery.statistics;
    final Mcv2FrameEvent event = new Mcv2FrameEvent();
    final boolean recorded = event.isEnabled();
    final long[] before = recorded ? this.linkCounts() : new long[LINK_COUNTS];
    final Screen current = this.screen;
    final OptionalInt sent = current.channel().send(frame);
    if (sent.isPresent()) {
      this.frameListener.accept(frame);
    }

    final int colors = sent.orElse(Mcv2Channel.NOT_SENT);
    if (recorded) {
      this.record(event, delivery, colors, before);
    }

    if (sent.isEmpty()) {
      LOGGER.warn(
        "MCV2 frame {} of {} bytes needs more than {} pages; it is not sent.",
        frameId,
        frame.length,
        current.configuration().getPageSlots()
      );
      this.statistics.drop();
      return;
    }

    this.statistics.add(statistics, colors);
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
    event.keyframe = delivery.statistics.isKeyframe();
    event.bytes = delivery.frame.length;
    event.colors = colors;
    event.arrived = delivery.source.arrived;
    event.sent = System.currentTimeMillis();
    event.encodeTime = delivery.statistics.getNanoseconds();
    event.sentTo = (int) (after[SENT] - before[SENT]);
    event.behind = (int) (after[BEHIND] - before[BEHIND]);
    event.waiting = (int) (after[UNDECODABLE] - before[UNDECODABLE]);
    event.backlog = backlog;
    event.fingerprint = Mcv2FrameEvent.fingerprint(delivery.source.pictureBytes, delivery.source.width, delivery.source.height);
    event.commit();
  }

  private void follow(final Mcv2Pacer.Change change) {
    this.ditheredForAll = change.getTo().isDithered();

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
    final boolean sameSize = rung.getWidth() == configuration.getVideoWidth() && rung.getHeight() == configuration.getVideoHeight();
    if (!sameSize) {
      current.channel().close();
      this.opened = false;
      final Mcv2Configuration resized = this.requested.withVideo(rung.getWidth(), rung.getHeight());
      final Resizer resizer = requireNonNull(this.resizer);
      target = new Screen(resized, resizer.resize(resized));
      this.screen = target;
    }

    if (!this.opened) {
      target.channel().open();
      this.opened = true;
    }
  }

  private void announce(final Mcv2Pacer.Change change) {
    if (change.isDown()) {
      LOGGER.warn(PACING_STEP, change.describe());
    } else {
      LOGGER.debug(PACING_STEP, change.describe());
    }

    this.pacingListener.accept(change);
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
   * @since 1.0.0
   */
  public void setSmallerSizes(final List<int[]> sizes, final Resizer resizer) {
    Preconditions.checkNotNull(sizes, "Sizes must not be null!");
    Preconditions.checkNotNull(resizer, "Resizer must not be null!");

    this.smaller = List.copyOf(sizes);
    this.resizer = resizer;
  }

  /**
   * Returns the rung of the ladder the screen is on.
   *
   * @return the rung, or empty before the result is started
   * @since 1.0.0
   */
  public Optional<Mcv2Pacer.Rung> getRung() {
    synchronized (this.lock) {
      final @Nullable Mcv2Pacer screenPacer = this.pacer;
      return Optional.ofNullable(screenPacer).map(Mcv2Pacer::getRung);
    }
  }

  /**
   * Returns what the result has done so far.
   *
   * @return the statistics, updated as frames are sent
   * @since 1.0.0
   */
  public Statistics getStatistics() {
    return this.statistics;
  }

  /**
   * Returns the channel that delivers the frames.
   *
   * @return the channel
   * @since 1.0.0
   */
  public Mcv2Channel getChannel() {
    return this.screen.channel();
  }

  /**
   * Returns the screen's configuration now: the one it was created with, or a copy at the smaller video size it stepped
   * down to.
   *
   * @return the configuration
   * @since 1.0.0
   */
  public Mcv2Configuration getConfiguration() {
    return this.screen.configuration();
  }

  /**
   * Sets who is told of the pacer's steps besides the server log, for example the operator who started the screen.
   *
   * <p>The callback may run on different threads on different calls; it must be thread-safe, nonblocking and
   * must schedule Bukkit world changes on the main thread. Callback exceptions are not swallowed at the call site.
   *
   * @param listener called with every step, on the thread that caused it
   * @throws NullPointerException if {@code listener} is null
   * @since 1.0.0
   */
  public void setPacingListener(final Consumer<Mcv2Pacer.Change> listener) {
    Preconditions.checkNotNull(listener, LISTENER_NULL_MESSAGE);

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
   * @since 1.0.0
   */
  public void setFrameListener(final Consumer<byte[]> listener) {
    Preconditions.checkNotNull(listener, LISTENER_NULL_MESSAGE);

    this.frameListener = listener;
  }

  private record Screen(Mcv2Configuration configuration, Mcv2Channel channel) {}

  /**
   * What a screen's owner does to show the video at another size: the pack decodes one video size, so a smaller video
   * needs its pack offered to the viewers, and a channel of its own.
   *
   * <p>The result calls it on the server main thread only, so implementations need not be thread-safe.
   *
   * @since 1.0.0
   */
  @FunctionalInterface
  public interface Resizer {
    /**
     * Offers the viewers the pack of the screen at another video size and creates its channel, not opened yet. Called
     * on the main thread.
     *
     * @param configuration the screen at the new size
     * @return the channel that sends the video at that size
     * @since 1.0.0
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

  private record Pipeline(Pool budget, BlockingQueue<Delivery> queue) {}

  private static final class Delivery {

    private final byte[] frame;
    private final Mcv2.Statistics statistics;
    private final long frameId;
    private final Arrival source;

    private Delivery(final byte[] frame, final Mcv2.Statistics statistics, final long frameId, final Arrival source) {
      this.frame = frame;
      this.statistics = statistics;
      this.frameId = frameId;
      this.source = source;
    }
  }

  /**
   * What the result has done so far.
   *
   * <p>This class is thread-safe: each getter is synchronized, but several getter calls are not one atomic snapshot.
   * Counts describe frames accepted by the channel, not multiplied by recipients or acknowledged by clients.
   *
   * @since 1.0.0
   */
  public static final class Statistics {

    private long frames;
    private long keyframes;
    private long dropped;
    private long bytes;
    private long mapBytes;
    private long nanoseconds;

    Statistics() {}

    synchronized void add(final Mcv2.Statistics statistics, final int mapColors) {
      this.frames++;
      this.keyframes += statistics.isKeyframe() ? 1 : 0;
      this.bytes += statistics.getBytes();
      this.mapBytes += mapColors;
      this.nanoseconds += statistics.getNanoseconds();
    }

    private synchronized void drop() {
      this.dropped++;
    }

    /**
     * Returns the number of frames sent.
     *
     * @return the frames
     * @since 1.0.0
     */
    public synchronized long getFrames() {
      return this.frames;
    }

    /**
     * Returns the number of keyframes sent.
     *
     * @return the keyframes
     * @since 1.0.0
     */
    public synchronized long getKeyframes() {
      return this.keyframes;
    }

    /**
     * Returns the number of frames not sent because they had more pages than the screen has page slots.
     *
     * @return the dropped frames
     * @since 1.0.0
     */
    public synchronized long getDropped() {
      return this.dropped;
    }

    /**
     * Returns the MCV2 bytes of the frames sent.
     *
     * @return the frame bytes
     * @since 1.0.0
     */
    public synchronized long getBytes() {
      return this.bytes;
    }

    /**
     * Returns accumulated page-map color bytes per potential recipient: every page as whole 128-color rows.
     * Anchors, packet overhead and actual recipient counts are excluded.
     *
     * @return the map bytes per viewer
     * @since 1.0.0
     */
    public synchronized long getMapBytes() {
      return this.mapBytes;
    }

    /**
     * Returns the time spent encoding the frames sent.
     *
     * @return the encode time in nanoseconds
     * @since 1.0.0
     */
    public synchronized long getNanoseconds() {
      return this.nanoseconds;
    }
  }
}
