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
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import me.brandonli.mcav.bukkit.BukkitModule;
import me.brandonli.mcav.bukkit.media.config.MapConfiguration;
import me.brandonli.mcav.bukkit.media.result.CompressedMapResult;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.mcv2.encode.EncoderPool;
import me.brandonli.mcav.media.mcv2.encode.Mcv2Encoder;
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
 * <p>Frames are encoded one at a time, inside the screen's encoder budget ({@link Mcv2Configuration#getEncoderPool()}),
 * which every screen of the server shares unless it was given its own; a thread of the screen only waits for the
 * budget. When the encoder is slower than the video, frames that arrive while it works replace each other and only the
 * newest is encoded, which the encoder's previous-frame reference allows. A frame with more pages than the screen has
 * page slots is not sent, and the next is a keyframe.
 *
 * <p>A {@link Mcv2Pacer} keeps the screen within what its budget sustains: when the frames take longer than the video
 * gives them, it encodes fewer of them, down to {@link Mcv2Pacer#MIN_FPS} a second, then shows a smaller video when the
 * owner offers smaller sizes ({@link #setSmallerSizes}: each size has its own pack), and when even that is too much,
 * every viewer is shown the dithered maps, which need no encoder, until a later try finds room again. Every step is
 * logged, a step down as a warning, and handed to the {@linkplain #setPacingListener(Consumer) pacing listener}, so
 * the operator learns what was chosen and why. A result without dithered maps stays at its lowest frame rate instead.
 *
 * <p>Call {@link #start()} and {@link #release()} on the main thread.
 */
public final class Mcv2Result implements FunctionalVideoFilter {

  private static final Logger LOGGER = LoggerFactory.getLogger(Mcv2Result.class);

  private final Mcv2Configuration requested;
  private final @Nullable Fallback fallback;
  private final Set<UUID> fallbackViewers;
  private final Executor dithering;
  private final @Nullable ExecutorService ditheringThread;
  private final AtomicBoolean ditheringBusy = new AtomicBoolean();
  private final Object lock;
  private final Statistics statistics;
  private final LongSupplier clock;

  private @Nullable Arrival pending;
  private boolean running;
  private @Nullable Thread worker;
  private @Nullable Thread sender;
  private @Nullable BlockingQueue<Delivery> deliveries;
  private @Nullable EncoderPool budget;
  private @Nullable Mcv2Pacer pacer;
  private boolean ditheredForAll;
  private Consumer<Mcv2Pacer.Change> pacingListener = _ -> {};
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

  /** A frame the video handed over, with the wall-clock time it arrived. */
  static final class Arrival {

    private final byte[] rgb;
    private final int width;
    private final int height;
    private final long arrived;

    Arrival(final byte[] rgb, final int width, final int height, final long arrived) {
      this.rgb = rgb;
      this.width = width;
      this.height = height;
      this.arrived = arrived;
    }
  }

  /** An encoded frame on its way to the viewers, with its encoder statistics and where it came from. */
  private static final class Delivery {

    private final byte[] frame;
    private final Mcv2Encoder.Stats stats;
    private final long frameId;
    private final Arrival source;

    Delivery(final byte[] frame, final Mcv2Encoder.Stats stats, final long frameId, final Arrival source) {
      this.frame = frame;
      this.stats = stats;
      this.frameId = frameId;
      this.source = source;
    }
  }

  /**
   * What the result has done so far.
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

    synchronized void drop() {
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
     * Gets the map colours sent to every viewer with the pack: every page as whole 128-colour rows.
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
    Preconditions.checkNotNull(configuration, "Configuration must not be null");
    Preconditions.checkNotNull(channel, "Channel must not be null");
    this.requested = configuration;
    this.screen = new Screen(configuration, channel);
    this.fallbackViewers = ConcurrentHashMap.newKeySet();
    this.fallback = fallbackAlgorithm == null
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
  }

  private static MapConfiguration fallbackConfiguration(final Mcv2Configuration configuration, final Set<UUID> viewers) {
    return MapConfiguration.builder()
      .map(configuration.getMap())
      .mapBlockWidth(configuration.getColumns())
      .mapBlockHeight(configuration.getRows())
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
   * @param sizes   the smaller sizes, largest first, each a width and a height
   * @param resizer offers a size's pack and creates its channel
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
   * @param listener called with every step, on the thread that caused it
   */
  public void setPacingListener(final Consumer<Mcv2Pacer.Change> listener) {
    Preconditions.checkNotNull(listener, "Listener must not be null");
    this.pacingListener = listener;
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
    final Mcv2Encoder encoder = encoderPool.encoder(this.requested.getSettings(), true);
    // the screen's own thread only hands frames to the budget and waits for them
    final Thread thread = Thread.ofPlatform().daemon().name("mcav-mcv2-screen").unstarted(() -> this.encodeLoop(encoder));
    // the frames go out on their own thread, so the encoder starts the next frame while the last is being sent; one
    // frame waits at most, and the encoder waits for its turn when sending falls behind
    final BlockingQueue<Delivery> queue = new ArrayBlockingQueue<>(1);
    final Thread delivery = Thread.ofPlatform().daemon().name("mcav-mcv2-sender").unstarted(() -> this.deliverLoop(queue));
    this.pace();
    synchronized (this.lock) {
      this.budget = encoderPool;
      this.worker = thread;
      this.sender = delivery;
      this.deliveries = queue;
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
   * @return true, because the frame may have been resized
   */
  @Override
  public boolean applyFilter(final ImageBuffer data, final OriginalVideoMetadata metadata) {
    Preconditions.checkNotNull(data, "Frame must not be null");
    Preconditions.checkNotNull(metadata, "Metadata must not be null");
    final boolean encode;
    final boolean everyoneDithered;
    Mcv2Pacer.Change tried = null;
    synchronized (this.lock) {
      final Mcv2Pacer screenPacer = this.pacer;
      if (screenPacer != null) {
        tried = screenPacer.arrive(this.clock.getAsLong());
        if (tried != null) {
          this.follow(tried);
        }
        encode = screenPacer.isEncoded();
      } else {
        encode = true;
      }
      everyoneDithered = this.ditheredForAll;
    }
    if (tried != null) {
      this.announce(tried);
    }
    final Screen current = this.screen;
    final Set<UUID> others = everyoneDithered ? Set.copyOf(this.requested.getViewers()) : current.channel().update();
    this.fallbackViewers.retainAll(others);
    this.fallbackViewers.addAll(others);
    final int width = current.configuration().getVideoWidth();
    final int height = current.configuration().getVideoHeight();
    if (data.getWidth() != width || data.getHeight() != height) {
      new ResizeFilter(width, height).applyFilter(data);
    }
    // on the dithered maps the pacer encodes no frame
    if (encode && !current.channel().getRecipients().isEmpty()) {
      final Arrival arrival = new Arrival(rgb(data.getPixels(), width * height), width, height, System.currentTimeMillis());
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

  /** Dithers one frame onto the maps of the viewers without the pack, then lets the next frame be dithered. */
  private void dither(final Fallback dithered, final int[] argb, final int width, final int height) {
    try (ImageBuffer frame = ImageBuffer.buffer(argb, width, height)) {
      dithered.result().process(frame, dithered.algorithm());
    } finally {
      this.ditheringBusy.set(false);
    }
  }

  static byte[] rgb(final int[] argb, final int pixels) {
    final byte[] rgb = new byte[pixels * 3];
    for (int i = 0; i < pixels; i++) {
      final int pixel = argb[i];
      rgb[i * 3] = (byte) (pixel >> 16);
      rgb[i * 3 + 1] = (byte) (pixel >> 8);
      rgb[i * 3 + 2] = (byte) pixel;
    }
    return rgb;
  }

  /** The frames the viewers' links sent, held back for the backlog and held back for a reference, summed. */
  private long[] linkCounts() {
    final long[] counts = new long[3];
    for (final Mcv2Link link : this.screen.channel().getLinks().values()) {
      counts[0] += link.getSent();
      counts[1] += link.getBehind();
      counts[2] += link.getUndecodable();
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
    event.sentTo = (int) (after[0] - before[0]);
    event.behind = (int) (after[1] - before[1]);
    event.waiting = (int) (after[2] - before[2]);
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
      thread.join(TimeUnit.SECONDS.toMillis(10));
    } catch (final InterruptedException exception) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Encodes frames until the result is released.
   *
   * @param encoder the encoder
   */
  void encodeLoop(final Mcv2Encoder encoder) {
    long frameId = 0;
    try {
      for (Arrival arrival = this.take(); arrival != null; arrival = this.take()) {
        this.send(encoder, arrival, frameId);
        frameId = (frameId + 1) & 0xFFFFFFFFL;
      }
    } catch (final InterruptedException exception) {
      Thread.currentThread().interrupt();
    }
  }

  /** Starts pacing the screen's frames from the top of its ladder, which {@link #start()} does. */
  void pace() {
    final List<int[]> sizes = new ArrayList<>();
    sizes.add(new int[] { this.requested.getVideoWidth(), this.requested.getVideoHeight() });
    sizes.addAll(this.smaller);
    final Mcv2Pacer screenPacer = new Mcv2Pacer(sizes, this.fallback != null);
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
   * Encodes one frame and hands it to the sender thread, or sends it on this thread when the result was not started.
   *
   * @param encoder the encoder
   * @param arrival the frame and when it arrived
   * @param frameId the frame's id
   * @throws InterruptedException if the thread is interrupted while it waits for the sender to take the frame
   */
  void send(final Mcv2Encoder encoder, final Arrival arrival, final long frameId) throws InterruptedException {
    if (this.screen.channel().takeKeyframeRequest()) {
      encoder.requestKeyframe();
    }
    final int width = arrival.width;
    final int height = arrival.height;
    final EncoderPool encoderPool;
    synchronized (this.lock) {
      encoderPool = this.budget;
    }
    final long started = this.clock.getAsLong();
    final byte[] frame = encoderPool == null
      ? encoder.encode(arrival.rgb, width, height, frameId)
      : encoderPool.run(() -> encoder.encode(arrival.rgb, width, height, frameId));
    final long finished = this.clock.getAsLong();
    final Mcv2Encoder.Stats stats = Preconditions.checkNotNull(encoder.getStats());
    final Delivery delivery = new Delivery(frame, stats, frameId, arrival);
    final BlockingQueue<Delivery> queue;
    Mcv2Pacer.Change change = null;
    synchronized (this.lock) {
      queue = this.deliveries;
      final Mcv2Pacer screenPacer = this.pacer;
      if (screenPacer != null) {
        change = screenPacer.encoded((finished - started) / 1e6, stats.keyframe(), finished);
        if (change != null) {
          this.follow(change);
        }
      }
    }
    if (change != null) {
      this.announce(change);
    }
    if (queue == null) {
      this.deliver(delivery);
    } else {
      queue.put(delivery);
    }
  }

  /**
   * Sends the frames the encoder hands over, in order, until the result is released.
   *
   * @param queue where the encoder hands them over
   */
  private void deliverLoop(final BlockingQueue<Delivery> queue) {
    try {
      while (true) {
        this.deliver(queue.take());
      }
    } catch (final InterruptedException exception) {
      Thread.currentThread().interrupt();
    }
  }

  /** Sends one encoded frame to the viewers and counts it. */
  private void deliver(final Delivery delivery) {
    final byte[] frame = delivery.frame;
    final long frameId = delivery.frameId;
    final Mcv2Encoder.Stats stats = delivery.stats;
    final Mcv2FrameEvent event = new Mcv2FrameEvent();
    final boolean recorded = event.isEnabled();
    final long[] before = recorded ? this.linkCounts() : new long[3];
    final Screen current = this.screen;
    final int colors = current.channel().send(frame);
    if (recorded) {
      this.record(event, delivery, colors, before);
    }
    if (colors < 0) {
      LOGGER.warn(
        "MCV2 frame {} of {} bytes needs more than {} pages; it is not sent",
        frameId,
        frame.length,
        current.configuration().getPageSlots()
      );
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
   * on the dithered maps the page frames go; on an encoded rung of another video size the screen is replaced by one at
   * that size (the owner offers its pack), and the page frames of an encoded rung are there.
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
    final long shown = ((long) configuration.getVideoWidth() << 32) | configuration.getVideoHeight();
    final boolean resized = !rung.isDithered() && (((long) rung.width() << 32) | rung.height()) != shown;
    if (this.opened && (rung.isDithered() || resized)) {
      current.channel().close();
      this.opened = false;
    }
    if (rung.isDithered()) {
      return;
    }
    Screen target = current;
    if (resized) {
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
      LOGGER.warn(change.describe());
    } else {
      LOGGER.info(change.describe());
    }
    this.pacingListener.accept(change);
  }

  /**
   * Stops the encoder, removes the page frames and clears the dithered maps. Call on the main thread.
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
      this.deliveries = null;
      this.budget = null;
      this.pacer = null;
    }
    stop(thread);
    stop(delivery);
    this.screen.channel().close();
    this.opened = false;
    final ExecutorService ditheringOwn = this.ditheringThread;
    if (ditheringOwn != null) {
      ditheringOwn.shutdown();
    }
    final Fallback dithered = this.fallback;
    if (dithered != null) {
      dithered.result().release();
    }
  }
}
