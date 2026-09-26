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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import me.brandonli.mcav.bukkit.media.config.MapConfiguration;
import me.brandonli.mcav.bukkit.media.result.CompressedMapResult;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.mcv2.encode.Mcv2Encoder;
import me.brandonli.mcav.media.player.metadata.OriginalVideoMetadata;
import me.brandonli.mcav.media.player.pipeline.filter.video.FunctionalVideoFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.ResizeFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.DitherAlgorithm;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Displays video on an MCV2 screen: players with the MCV2 resource pack receive every frame encoded as MCV2 pages,
 * which their shader decodes; every other viewer receives the video dithered onto the wall's maps, like
 * {@link CompressedMapResult}, so a player without the pack never sees a screen their client cannot show. Which
 * players have the pack, and when they start receiving frames, is decided by the {@link Mcv2Channel}.
 *
 * <p>Frames are encoded on a dedicated thread, one at a time. When the encoder is slower than the video, frames that
 * arrive while it works replace each other and only the newest is encoded, which the encoder's previous-frame
 * reference allows. A frame with more pages than the screen has page slots is not sent, and the next is a keyframe.
 *
 * <p>Call {@link #start()} and {@link #release()} on the main thread.
 */
public final class Mcv2Result implements FunctionalVideoFilter {

  private static final Logger LOGGER = LoggerFactory.getLogger(Mcv2Result.class);

  private final Mcv2Configuration configuration;
  private final Mcv2Channel channel;
  private final @Nullable Fallback fallback;
  private final Set<UUID> fallbackViewers;
  private final Object lock;
  private final Statistics statistics;

  private @Nullable Arrival pending;
  private boolean running;
  private @Nullable Thread worker;
  private @Nullable Thread sender;
  private @Nullable BlockingQueue<Delivery> deliveries;
  private @Nullable ForkJoinPool pool;

  /** The dithered maps of the viewers without the pack, and how they are dithered. */
  private record Fallback(CompressedMapResult result, DitherAlgorithm algorithm) {}

  /** A frame the video handed over, with the wall-clock time it arrived. */
  static final class Arrival {

    private final byte[] rgb;
    private final long arrived;

    Arrival(final byte[] rgb, final long arrived) {
      this.rgb = rgb;
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
    Preconditions.checkNotNull(configuration, "Configuration must not be null");
    Preconditions.checkNotNull(channel, "Channel must not be null");
    this.configuration = configuration;
    this.channel = channel;
    this.fallbackViewers = ConcurrentHashMap.newKeySet();
    this.fallback = fallbackAlgorithm == null
      ? null
      : new Fallback(new CompressedMapResult(fallbackConfiguration(configuration, this.fallbackViewers)), fallbackAlgorithm);
    this.lock = new Object();
    this.statistics = new Statistics();
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
    return this.channel;
  }

  /**
   * Spawns the screen's page frames and starts the encoder. Call on the main thread.
   */
  @Override
  public void start() {
    this.channel.open();
    final Fallback dithered = this.fallback;
    if (dithered != null) {
      dithered.result().start();
    }
    final ForkJoinPool encoderPool = new ForkJoinPool(Math.max(1, Runtime.getRuntime().availableProcessors() - 2));
    final Mcv2Encoder encoder = new Mcv2Encoder(this.configuration.getSettings(), encoderPool, encoderPool.getParallelism(), true);
    final Thread thread = Thread.ofPlatform().daemon().name("mcav-mcv2-encoder").unstarted(() -> this.encodeLoop(encoder));
    // the frames go out on their own thread, so the encoder starts the next frame while the last is being sent; one
    // frame waits at most, and the encoder waits for its turn when sending falls behind
    final BlockingQueue<Delivery> queue = new ArrayBlockingQueue<>(1);
    final Thread delivery = Thread.ofPlatform().daemon().name("mcav-mcv2-sender").unstarted(() -> this.deliverLoop(queue));
    synchronized (this.lock) {
      this.pool = encoderPool;
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
    final Set<UUID> others = this.channel.update();
    this.fallbackViewers.retainAll(others);
    this.fallbackViewers.addAll(others);
    final int width = this.configuration.getVideoWidth();
    final int height = this.configuration.getVideoHeight();
    if (data.getWidth() != width || data.getHeight() != height) {
      new ResizeFilter(width, height).applyFilter(data);
    }
    if (!this.channel.getRecipients().isEmpty()) {
      final Arrival arrival = new Arrival(rgb(data.getPixels(), width * height), System.currentTimeMillis());
      synchronized (this.lock) {
        this.pending = arrival;
        this.lock.notifyAll();
      }
    }
    final Fallback dithered = this.fallback;
    if (dithered != null && !others.isEmpty()) {
      dithered.result().process(data, dithered.algorithm());
    }
    return true;
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
    for (final Mcv2Link link : this.channel.getLinks().values()) {
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
    for (final Mcv2Link link : this.channel.getLinks().values()) {
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
    event.fingerprint = Mcv2FrameEvent.fingerprint(
      delivery.source.rgb,
      this.configuration.getVideoWidth(),
      this.configuration.getVideoHeight()
    );
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
    if (this.channel.takeKeyframeRequest()) {
      encoder.requestKeyframe();
    }
    final byte[] frame = encoder.encode(arrival.rgb, this.configuration.getVideoWidth(), this.configuration.getVideoHeight(), frameId);
    final Delivery delivery = new Delivery(frame, Preconditions.checkNotNull(encoder.getStats()), frameId, arrival);
    final BlockingQueue<Delivery> queue;
    synchronized (this.lock) {
      queue = this.deliveries;
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
    final int colors = this.channel.send(frame);
    if (recorded) {
      this.record(event, delivery, colors, before);
    }
    if (colors < 0) {
      LOGGER.warn(
        "MCV2 frame {} of {} bytes needs more than {} pages; it is not sent",
        frameId,
        frame.length,
        this.configuration.getPageSlots()
      );
      this.statistics.drop();
      return;
    }
    this.statistics.add(stats, colors);
  }

  /**
   * Stops the encoder, removes the page frames and clears the dithered maps. Call on the main thread.
   */
  @Override
  public void release() {
    final Thread thread;
    final Thread delivery;
    final ForkJoinPool encoderPool;
    synchronized (this.lock) {
      this.running = false;
      this.lock.notifyAll();
      thread = this.worker;
      delivery = this.sender;
      encoderPool = this.pool;
      this.worker = null;
      this.sender = null;
      this.deliveries = null;
      this.pool = null;
    }
    stop(thread);
    stop(delivery);
    if (encoderPool != null) {
      encoderPool.shutdownNow();
    }
    this.channel.close();
    final Fallback dithered = this.fallback;
    if (dithered != null) {
      dithered.result().release();
    }
  }
}
