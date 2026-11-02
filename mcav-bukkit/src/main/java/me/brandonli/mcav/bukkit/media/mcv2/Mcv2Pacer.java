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

import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Keeps an MCV2 screen within what its encoder budget sustains, on purpose, instead of falling behind.
 *
 * <p>The screen's ladder has, from the top: the video size it was asked for at every frame of the video with each of
 * its {@linkplain Preset presets}, from the one it was asked for down to the fastest, then with the fastest at every
 * second, third, fourth and sixth frame while at least {@link #MINIMUM_FRAMES_PER_SECOND} frames a second remain; then the same for each
 * smaller size the screen can switch to; and last the dithered maps, which need no encoder. The screen reports how long
 * every encoded P frame held its pipeline - the frame's search, and the wait for the frame before it to be verified
 * and sent, which is what bounds the frames it keeps up with; keyframes, which come every few seconds and cost more,
 * are left out. When the smoothed
 * time has been over the time a frame has, {@link #HIGH} of it, for {@link #DOWN_SECONDS}, the pacer steps down to the
 * first rung below that the measured time predicts to fit in {@link #FIT} of its frame time - the encode time grows
 * with the pixels and with the preset's cost, the frame time with the frames skipped - else to the first that keeps up
 * at all, and to the dithered maps when none does; a faster preset at the same size and frame rate, which keeps every
 * frame the viewers see, only has to keep up. It judges a rung only after {@link #MINIMUM_SAMPLES} P frames on it, and does
 * not step down in the first {@link #STARTUP_SECONDS} of the video, while a new encoder warms up. It steps back up
 * when a rung above has been predicted to fit in {@link #ROOM} of its frame time for {@link #UP_SECONDS}, to the
 * highest such rung, and keeps off a rung it had to leave for {@link #BLOCK_SECONDS}, longer each time.
 * From the dithered maps it tries the lowest encoded rung again after {@link #RETRY_SECONDS}, and twice as long after
 * every try that failed, up to {@link #MAXIMUM_RETRY_SECONDS}; on the dithered maps it does not measure the video's rate,
 * since the frames then come as fast as the dithering allows. The thresholds guide adaptation after startup and sample-count gates; they are predictions, not a hard
 * latency guarantee. Changing content or external load can invalidate an earlier timing estimate.
 *
 * <p>Times are {@link System#nanoTime()} values passed in by the caller. Not thread-safe: the screen calls it from
 * one thread at a time.
 *
 * @since 1.0.0
 */
public final class Mcv2Pacer {

  /**
   * The lowest frame rate a rung may have, in frames per second.
   *
   * @since 1.0.0
   */
  public static final double MINIMUM_FRAMES_PER_SECOND = 10;

  private static final double HIGH = 1.0;
  static final double FIT = 0.85;
  static final double ROOM = 0.7;

  private static final double[] STEP_DOWN_SHARES = { FIT, HIGH };

  static final double DOWN_SECONDS = 1.0;
  static final double STARTUP_SECONDS = 5.0;
  private static final double UP_SECONDS = 5.0;
  private static final double BLOCK_SECONDS = 10.0;
  private static final double MAXIMUM_BLOCK_SECONDS = 600.0;
  private static final double RETRY_SECONDS = 30.0;
  private static final double MAXIMUM_RETRY_SECONDS = 600.0;

  static final int MINIMUM_SAMPLES = 10;

  private static final double SMOOTHING = 0.2;

  private static final int[] DIVISORS = { 1, 2, 3, 4, 6 };
  private static final int FULL_RATE_DIVISOR = 1;

  private static final int SIZE_COMPONENTS = 2;

  private static final int BACKOFF_FACTOR = 2;

  private static final long NANOSECONDS_PER_SECOND = 1_000_000_000L;
  private static final double NANOSECONDS_PER_MILLISECOND = 1e6;

  private static final double WHOLE_RATE = 0.05;

  // A measured frame rate wavers around the nominal one, so a rung at the lowest rate may measure just below it.
  private static final double MINIMUM_FRAMES_PER_SECOND_TOLERANCE = 0.95;

  // System.nanoTime() may be negative, so zero cannot mark an absent timestamp.
  private static final long NEVER = Long.MIN_VALUE;

  private static final int NO_RUNG = -1;

  private static final double UNMEASURED_MILLISECONDS = 0.0;

  private final List<Rung> ladder;

  private final long[] blockedUntil;

  private final double[] blockSeconds;

  private int current;

  private double videoInterval = Double.NaN;

  private long lastArrival = NEVER;

  private long arrivals;

  private long firstArrival = NEVER;

  private int samples;

  private double smoothed = Double.NaN;

  private long overSince = NEVER;

  private long roomSince = NEVER;

  private long retryAt = NEVER;

  private double retrySeconds = RETRY_SECONDS;

  private long settledSince = NEVER;

  /**
   * Creates a pacer that starts at the top of the ladder and may fall back to the dithered maps.
   *
   * <p>Each size array must contain exactly two positive pixel dimensions. Dimensions are copied into
   * immutable rungs, so later changes to the lists or arrays do not change this pacer.
   *
   * @param sizes the video sizes the screen can show, largest first: the size it was asked for, then smaller ones it
   *              can switch to
   * @throws IllegalArgumentException if there is no size, or a size is not positive
   * @throws NullPointerException     if the sizes or one of its arrays is null
   * @since 1.0.0
   */
  public Mcv2Pacer(final List<int[]> sizes) {
    this(sizes, Floor.DITHERED_MAPS);
  }

  /**
   * Creates a pacer that starts at the top of the ladder.
   *
   * <p>Each size array must contain exactly two positive pixel dimensions. Dimensions are copied into
   * immutable rungs, so later changes to the lists or arrays do not change this pacer.
   *
   * @param sizes the video sizes the screen can show, largest first: the size it was asked for, then smaller ones it
   *              can switch to
   * @param floor how low the ladder goes: down to the dithered maps, or only to the lowest encoded rung, even when
   *              its frames take longer than they have
   * @throws IllegalArgumentException if there is no size, or a size is not positive
   * @throws NullPointerException     if the sizes, one of its arrays or the floor is null
   * @since 1.0.0
   */
  public Mcv2Pacer(final List<int[]> sizes, final Floor floor) {
    this(sizes, List.of(Preset.ONLY), floor);
  }

  /**
   * Creates a pacer that starts at the top of the ladder and steps down through presets before frame rates.
   *
   * <p>Each size array must contain exactly two positive pixel dimensions. Dimensions are copied into
   * immutable rungs, so later changes to the lists or arrays do not change this pacer.
   *
   * @param sizes   the video sizes the screen can show, largest first: the size it was asked for, then smaller ones it
   *                can switch to
   * @param presets how hard the encoder can search, from the preset the screen was asked for down to the fastest
   * @param floor   how low the ladder goes: down to the dithered maps, or only to the lowest encoded rung, even when
   *                its frames take longer than they have
   * @throws IllegalArgumentException if there is no size or no preset, a size is not positive, or a preset does not
   *                                  cost less than the one before it
   * @throws NullPointerException     if the sizes, one of its arrays, the presets or the floor is null
   * @since 1.0.0
   */
  public Mcv2Pacer(final List<int[]> sizes, final List<Preset> presets, final Floor floor) {
    Preconditions.checkNotNull(floor, "Floor must not be null!");
    Preconditions.checkArgument(!sizes.isEmpty(), "At least one size is needed!");
    Preconditions.checkArgument(!presets.isEmpty(), "At least one preset is needed!");

    for (int index = 1; index < presets.size(); index++) {
      Preconditions.checkArgument(
        presets.get(index).getCost() < presets.get(index - 1).getCost(),
        "Each preset must cost less than the one before!"
      );
    }

    final Preset fastest = presets.getLast();
    final List<Rung> rungs = new ArrayList<>();
    for (final int[] size : sizes) {
      Preconditions.checkArgument(size.length == SIZE_COMPONENTS && size[0] > 0 && size[1] > 0, "Sizes must be positive width and height!");
      for (final Preset preset : presets.subList(0, presets.size() - 1)) {
        rungs.add(Rung.of(size[0], size[1], FULL_RATE_DIVISOR, preset));
      }
      for (final int divisor : DIVISORS) {
        rungs.add(Rung.of(size[0], size[1], divisor, fastest));
      }
    }

    if (floor == Floor.DITHERED_MAPS) {
      rungs.add(Rung.DITHERED);
    }

    this.ladder = List.copyOf(rungs);
    this.blockedUntil = new long[rungs.size()];
    this.blockSeconds = new double[rungs.size()];
    Arrays.fill(this.blockedUntil, NEVER);
    Arrays.fill(this.blockSeconds, BLOCK_SECONDS);
  }

  static String rate(final double frameRate) {
    final long whole = Math.round(frameRate);
    return Math.abs(frameRate - whole) < WHOLE_RATE ? Long.toString(whole) : String.format(Locale.ROOT, "%.1f", frameRate);
  }

  /**
   * Takes a frame of the video and decides whether it is encoded. On the dithered maps it is not, until the time to
   * try again comes, which moves the pacer to the lowest encoded rung.
   *
   * @param now the arrival time in monotonic nanoseconds, from the same clock used for other calls
   * @return the step a try from the dithered maps takes, which the caller carries out, or empty
   * @since 1.0.0
   */
  public Optional<Change> arrive(final long now) {
    if (this.firstArrival == NEVER) {
      this.firstArrival = now;
    }

    // Dithering throughput does not measure the video's frame rate.
    if (this.lastArrival != NEVER && now > this.lastArrival && !this.getRung().isDithered()) {
      final double interval = now - this.lastArrival;
      this.videoInterval = Double.isNaN(this.videoInterval) ? interval : this.videoInterval + SMOOTHING * (interval - this.videoInterval);
    }

    this.lastArrival = now;
    Optional<Change> change = Optional.empty();
    if (this.getRung().isDithered() && now >= this.retryAt) {
      change = Optional.of(this.move(this.lowestEncoded(), Direction.UP, UNMEASURED_MILLISECONDS, UNMEASURED_MILLISECONDS));
    }

    this.arrivals++;

    return change;
  }

  /**
   * Returns whether the frame that arrived last is to be encoded on the current rung.
   *
   * @return true to encode it
   * @since 1.0.0
   */
  public boolean isEncoded() {
    final Rung rung = this.getRung();
    return !rung.isDithered() && (this.arrivals - 1) % rung.getDivisor() == 0;
  }

  /**
   * Reports an encoded frame.
   *
   * @param milliseconds how long the frame took to encode, waiting for the budget's threads included
   * @param kind         whether it was a keyframe, which is left out, or a P frame
   * @param now          the completion time in monotonic nanoseconds, from the same clock used for other calls
   * @return the step it causes, which the caller carries out, or empty
   * @throws NullPointerException     if kind is null
   * @throws IllegalArgumentException if milliseconds is negative, NaN or infinite
   * @since 1.0.0
   */
  public Optional<Change> reportEncodedFrame(final double milliseconds, final Mcv2FrameKind kind, final long now) {
    Preconditions.checkNotNull(kind, "Kind must not be null!");
    Preconditions.checkArgument(milliseconds >= 0 && Double.isFinite(milliseconds), "Time must be finite and non-negative!");

    final Rung rung = this.getRung();
    if (kind == Mcv2FrameKind.KEYFRAME || rung.isDithered() || Double.isNaN(this.videoInterval)) {
      return Optional.empty();
    }

    this.smoothed = Double.isNaN(this.smoothed) ? milliseconds : this.smoothed + SMOOTHING * (milliseconds - this.smoothed);
    this.samples++;

    if (this.settledSince == NEVER) {
      this.settledSince = now;
    }

    if (this.samples < MINIMUM_SAMPLES || now - this.firstArrival < this.toNanoseconds(STARTUP_SECONDS)) {
      return Optional.empty();
    }

    final double frameMilliseconds = this.frameMilliseconds(rung);
    if (this.smoothed > HIGH * frameMilliseconds) {
      this.roomSince = NEVER;

      if (this.overSince == NEVER) {
        this.overSince = now;
      }

      if (now - this.overSince >= this.toNanoseconds(DOWN_SECONDS)) {
        return Optional.ofNullable(this.stepDown(now, frameMilliseconds));
      }

      return Optional.empty();
    }

    this.overSince = NEVER;
    // Resetting backoff before the next retry interval permits repeated rung oscillation.
    final long held = now - this.settledSince;
    if (held >= this.toNanoseconds(Math.max(UP_SECONDS, this.blockSeconds[this.current]))) {
      this.blockSeconds[this.current] = BLOCK_SECONDS;
    }

    if (held >= this.toNanoseconds(Math.max(UP_SECONDS, this.retrySeconds))) {
      this.retrySeconds = RETRY_SECONDS;
    }

    final int above = this.bestAbove(now);
    if (above == NO_RUNG) {
      this.roomSince = NEVER;

      return Optional.empty();
    }

    if (this.roomSince == NEVER) {
      this.roomSince = now;
    }

    if (now - this.roomSince < this.toNanoseconds(UP_SECONDS)) {
      return Optional.empty();
    }

    final Change change = this.move(above, Direction.UP, this.smoothed, frameMilliseconds);
    return Optional.of(change);
  }

  private @Nullable Change stepDown(final long now, final double frameMilliseconds) {
    final int left = this.current;
    this.blockedUntil[left] = now + this.toNanoseconds(this.blockSeconds[left]);
    this.blockSeconds[left] = Math.min(MAXIMUM_BLOCK_SECONDS, this.blockSeconds[left] * BACKOFF_FACTOR);
    final int encoded = this.countEncodedRungs();

    final Rung from = this.getRung();
    for (final double share : STEP_DOWN_SHARES) {
      final int next = this.findFittingRungBelow(from, share, encoded);
      if (next != NO_RUNG) {
        return this.move(next, Direction.DOWN, this.smoothed, frameMilliseconds);
      }
    }

    if (encoded == this.ladder.size()) {
      final int lowest = this.lowestEncoded();
      this.overSince = NEVER;

      return lowest > this.current ? this.move(lowest, Direction.DOWN, this.smoothed, frameMilliseconds) : null;
    }

    final Change change = this.move(this.ladder.size() - 1, Direction.DOWN, this.smoothed, frameMilliseconds);
    this.retryAt = now + this.toNanoseconds(this.retrySeconds);
    this.retrySeconds = Math.min(MAXIMUM_RETRY_SECONDS, this.retrySeconds * BACKOFF_FACTOR);

    return change;
  }

  private int findFittingRungBelow(final Rung from, final double share, final int encoded) {
    for (int next = this.current + 1; next < encoded; next++) {
      final Rung rung = this.ladder.get(next);
      final boolean sameFrames = rung.pixels() == from.pixels() && rung.getDivisor() == from.getDivisor();
      if (this.isAllowed(rung) && this.predictEncodeMilliseconds(next) <= (sameFrames ? HIGH : share) * this.frameMilliseconds(rung)) {
        return next;
      }
    }

    return NO_RUNG;
  }

  private int countEncodedRungs() {
    return this.ladder.getLast().isDithered() ? this.ladder.size() - 1 : this.ladder.size();
  }

  private Change move(final int next, final Direction direction, final double encodeMilliseconds, final double frameMilliseconds) {
    final Change change = Change.of(
      this.getRung(),
      this.ladder.get(next),
      direction == Direction.DOWN,
      encodeMilliseconds,
      frameMilliseconds,
      this.getVideoFramesPerSecond()
    );
    this.current = next;
    this.smoothed = Double.NaN;
    this.overSince = NEVER;
    this.roomSince = NEVER;
    this.settledSince = NEVER;
    this.arrivals = 0;
    this.samples = 0;

    return change;
  }

  private double frameMilliseconds(final Rung rung) {
    return (rung.getDivisor() * this.videoInterval) / NANOSECONDS_PER_MILLISECOND;
  }

  private double predictEncodeMilliseconds(final int index) {
    final Rung target = this.ladder.get(index);
    final Rung rung = this.getRung();
    return (this.smoothed * target.pixels() * target.getPreset().getCost()) / (rung.pixels() * rung.getPreset().getCost());
  }

  private boolean isAllowed(final Rung rung) {
    return (
      rung.getDivisor() == FULL_RATE_DIVISOR ||
      rung.framesPerSecond(this.getVideoFramesPerSecond()) >= MINIMUM_FRAMES_PER_SECOND * MINIMUM_FRAMES_PER_SECOND_TOLERANCE
    );
  }

  private int bestAbove(final long now) {
    for (int index = 0; index < this.current; index++) {
      final Rung rung = this.ladder.get(index);
      if (
        this.isAllowed(rung) &&
        now >= this.blockedUntil[index] &&
        this.predictEncodeMilliseconds(index) <= ROOM * this.frameMilliseconds(rung)
      ) {
        return index;
      }
    }

    return NO_RUNG;
  }

  private int lowestEncoded() {
    int index = this.countEncodedRungs() - 1;
    while (!this.isAllowed(this.ladder.get(index))) {
      index--;
    }

    return index;
  }

  private long toNanoseconds(final double seconds) {
    return (long) (seconds * NANOSECONDS_PER_SECOND);
  }

  /**
   * Returns the rungs of the ladder, top first; the last is {@link Rung#DITHERED} when the screen can fall back to it.
   *
   * @return the unmodifiable ladder, whose immutable rungs may be shared
   * @since 1.0.0
   */
  public List<Rung> getLadder() {
    return this.ladder;
  }

  /**
   * Returns the rung the screen is on.
   *
   * @return the rung
   * @since 1.0.0
   */
  public Rung getRung() {
    return this.ladder.get(this.current);
  }

  /**
   * Returns the video's frames per second, as measured from the frames it hands over.
   *
   * @return the frame rate, or NaN before two frames arrived
   * @since 1.0.0
   */
  public double getVideoFramesPerSecond() {
    return NANOSECONDS_PER_SECOND / this.videoInterval;
  }

  /**
   * A preset of the ladder: how hard the screen's encoder searches.
   *
   * <p>This class is immutable and thread-safe.
   *
   * @since 1.0.0
   */
  public static final class Preset {

    /**
     * The one preset of a screen that does not step through presets.
     *
     * @since 1.0.0
     */
    public static final Preset ONLY = of("", 1);

    private final String name;
    private final double cost;

    private Preset(final String name, final double cost) {
      this.name = name;
      this.cost = cost;
    }

    /**
     * Returns a preset used to compare expected search costs.
     *
     * @param name the preset's name in messages, for example {@code FAST}; empty for the one preset of a screen that
     *             does not step through presets
     * @param cost its encode time relative to the other presets of the ladder, which the pacer's predictions scale by
     * @return the preset
     * @throws NullPointerException     if {@code name} is null
     * @throws IllegalArgumentException if {@code cost} is not positive and finite
     * @since 1.0.0
     */
    public static Preset of(final String name, final double cost) {
      Preconditions.checkNotNull(name, "Name must not be null!");
      Preconditions.checkArgument(cost > 0 && Double.isFinite(cost), "Cost must be positive and finite!");

      return new Preset(name, cost);
    }

    private String suffix() {
      return this.name.isEmpty() ? "" : " with the %s search".formatted(this.name);
    }

    /**
     * Returns the preset's name in messages.
     *
     * @return the preset's name in messages, for example {@code FAST}; empty for the one preset of a screen that does not
     *         step through presets
     * @since 1.0.0
     */
    public String getName() {
      return this.name;
    }

    /**
     * Returns the preset's encode time relative to the other presets of the ladder.
     *
     * @return its encode time relative to the other presets of the ladder, which the pacer's predictions scale by
     * @since 1.0.0
     */
    public double getCost() {
      return this.cost;
    }

    /**
     * Returns whether {@code other} is a preset with the same parts.
     *
     * @param other the object to compare with
     * @return whether every part matches
     * @since 1.0.0
     */
    @Override
    public boolean equals(final @Nullable Object other) {
      if (this == other) {
        return true;
      }

      if (!(other instanceof final Preset preset)) {
        return false;
      }

      return this.name.equals(preset.name) && Double.compare(this.cost, preset.cost) == 0;
    }

    /**
     * Returns a hash code built from every part.
     *
     * @return the hash code
     * @since 1.0.0
     */
    @Override
    public int hashCode() {
      return Objects.hash(this.name, this.cost);
    }

    /**
     * Returns a debugging representation with every part.
     *
     * @return the representation
     * @since 1.0.0
     */
    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this).add("name", this.name).add("cost", this.cost).toString();
    }
  }

  /**
   * A rung of the ladder.
   *
   * <p>Construction does not validate the parts. Use positive pixel dimensions, a positive frame divisor and a
   * non-null preset for encoded rungs; {@link #DITHERED} is the canonical zero-divisor sentinel.
   *
   * <p>This class is immutable and thread-safe.
   *
   * @since 1.0.0
   */
  public static final class Rung {

    /**
     * The dithered maps, the lowest rung.
     *
     * @since 1.0.0
     */
    public static final Rung DITHERED = of(0, 0, 0);

    private final int width;
    private final int height;
    private final int divisor;
    private final Preset preset;

    private Rung(final int width, final int height, final int divisor, final Preset preset) {
      this.width = width;
      this.height = height;
      this.divisor = divisor;
      this.preset = preset;
    }

    /**
     * Returns a rung of the given size, frame divisor and preset.
     *
     * @param width   the video width, or 0 for the dithered maps
     * @param height  the video height, or 0 for the dithered maps
     * @param divisor every how many frames of the video one is encoded, or 0 for the dithered maps
     * @param preset  how hard the encoder searches; {@link Preset#ONLY} on the dithered maps
     * @return the rung
     * @since 1.0.0
     */
    public static Rung of(final int width, final int height, final int divisor, final Preset preset) {
      return new Rung(width, height, divisor, preset);
    }

    /**
     * Returns a rung of a screen that does not step through presets.
     *
     * @param width   the video width, or 0 for the dithered maps
     * @param height  the video height, or 0 for the dithered maps
     * @param divisor every how many frames of the video one is encoded, or 0 for the dithered maps
     * @return the rung
     * @since 1.0.0
     */
    public static Rung of(final int width, final int height, final int divisor) {
      return of(width, height, divisor, Preset.ONLY);
    }

    /**
     * Returns whether this is the dithered maps.
     *
     * @return true for the dithered maps
     * @since 1.0.0
     */
    public boolean isDithered() {
      return this.divisor == 0;
    }

    /**
     * Returns the frames per second this rung encodes of a video.
     *
     * @param videoFramesPerSecond the video's frames per second
     * @return the encoded frames per second, 0 for the dithered maps
     * @since 1.0.0
     */
    public double framesPerSecond(final double videoFramesPerSecond) {
      return this.isDithered() ? 0 : videoFramesPerSecond / this.divisor;
    }

    private long pixels() {
      return (long) this.width * this.height;
    }

    /**
     * Describes the rung for a message.
     *
     * @param videoFramesPerSecond the video's frames per second
     * @return for example {@code 1920x1080 at 30 fps}, {@code 1920x1080 at 30 fps with the FAST search} on a
     *         ladder of presets, or {@code the dithered maps}
     * @since 1.0.0
     */
    public String describe(final double videoFramesPerSecond) {
      return this.isDithered()
        ? "the dithered maps"
        : String.format(
            Locale.getDefault(Locale.Category.FORMAT),
            "%dx%d at %s fps%s",
            this.width,
            this.height,
            rate(this.framesPerSecond(videoFramesPerSecond)),
            this.preset.suffix()
          );
    }

    /**
     * Returns the video width.
     *
     * @return the video width, or 0 for the dithered maps
     * @since 1.0.0
     */
    public int getWidth() {
      return this.width;
    }

    /**
     * Returns the video height.
     *
     * @return the video height, or 0 for the dithered maps
     * @since 1.0.0
     */
    public int getHeight() {
      return this.height;
    }

    /**
     * Returns every how many frames of the video one is encoded.
     *
     * @return every how many frames of the video one is encoded, or 0 for the dithered maps
     * @since 1.0.0
     */
    public int getDivisor() {
      return this.divisor;
    }

    /**
     * Returns how hard the encoder searches.
     *
     * @return how hard the encoder searches; {@link Preset#ONLY} on the dithered maps
     * @since 1.0.0
     */
    public Preset getPreset() {
      return this.preset;
    }

    /**
     * Returns whether {@code other} is a rung with the same parts.
     *
     * @param other the object to compare with
     * @return whether every part matches
     * @since 1.0.0
     */
    @Override
    public boolean equals(final @Nullable Object other) {
      if (this == other) {
        return true;
      }

      if (!(other instanceof final Rung rung)) {
        return false;
      }

      return this.width == rung.width && this.height == rung.height && this.divisor == rung.divisor && this.preset.equals(rung.preset);
    }

    /**
     * Returns a hash code built from every part.
     *
     * @return the hash code
     * @since 1.0.0
     */
    @Override
    public int hashCode() {
      return Objects.hash(this.width, this.height, this.divisor, this.preset);
    }

    /**
     * Returns a debugging representation with every part.
     *
     * @return the representation
     * @since 1.0.0
     */
    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
        .add("width", this.width)
        .add("height", this.height)
        .add("divisor", this.divisor)
        .add("preset", this.preset)
        .toString();
    }
  }

  /**
   * A step of the pacer.
   *
   * <p>This class is immutable and thread-safe.
   *
   * @since 1.0.0
   */
  public static final class Change {

    private final Rung from;
    private final Rung to;
    private final boolean down;
    private final double encodeMilliseconds;
    private final double frameMilliseconds;
    private final double videoFramesPerSecond;

    private Change(
      final Rung from,
      final Rung to,
      final boolean down,
      final double encodeMilliseconds,
      final double frameMilliseconds,
      final double videoFramesPerSecond
    ) {
      this.from = from;
      this.to = to;
      this.down = down;
      this.encodeMilliseconds = encodeMilliseconds;
      this.frameMilliseconds = frameMilliseconds;
      this.videoFramesPerSecond = videoFramesPerSecond;
    }

    /**
     * Returns a step between two rungs.
     *
     * @param from               the rung before
     * @param to                 the rung now
     * @param down               whether the step went down the ladder
     * @param encodeMilliseconds the smoothed encode time per frame that decided the step, 0 for a try from the dithered maps
     * @param frameMilliseconds  the time a frame had on the rung before, 0 on the dithered maps
     * @param videoFramesPerSecond     the video's frames per second as measured
     * @return the change
     * @since 1.0.0
     */
    public static Change of(
      final Rung from,
      final Rung to,
      final boolean down,
      final double encodeMilliseconds,
      final double frameMilliseconds,
      final double videoFramesPerSecond
    ) {
      return new Change(from, to, down, encodeMilliseconds, frameMilliseconds, videoFramesPerSecond);
    }

    /**
     * Describes the step for the server's operator: what was chosen and why.
     *
     * @return the message
     * @since 1.0.0
     */
    public String describe() {
      if (this.from.isDithered()) {
        return "MCV2 screen tries encoding again at %s after showing the dithered maps".formatted(
          this.to.describe(this.videoFramesPerSecond)
        );
      }

      final String timing = String.format(
        Locale.ROOT,
        "encoding %dx%d%s takes %.1f ms per frame, %s the %.1f ms a frame has at %s fps",
        this.from.getWidth(),
        this.from.getHeight(),
        this.from.getPreset().suffix(),
        this.encodeMilliseconds,
        this.down ? "more than" : "well within",
        this.frameMilliseconds,
        rate(this.from.framesPerSecond(this.videoFramesPerSecond))
      );
      return this.down
        ? "MCV2 screen steps down to %s: %s with the encoder threads it has".formatted(this.to.describe(this.videoFramesPerSecond), timing)
        : "MCV2 screen steps back up to %s: %s".formatted(this.to.describe(this.videoFramesPerSecond), timing);
    }

    /**
     * Returns the rung before the step.
     *
     * @return the rung before
     * @since 1.0.0
     */
    public Rung getFrom() {
      return this.from;
    }

    /**
     * Returns the rung after the step.
     *
     * @return the rung now
     * @since 1.0.0
     */
    public Rung getTo() {
      return this.to;
    }

    /**
     * Returns whether the step went down the ladder.
     *
     * @return whether the step went down the ladder
     * @since 1.0.0
     */
    public boolean isDown() {
      return this.down;
    }

    /**
     * Returns the smoothed encode time per frame that decided the step.
     *
     * @return the smoothed encode time per frame that decided the step, 0 for a try from the dithered maps
     * @since 1.0.0
     */
    public double getEncodeMilliseconds() {
      return this.encodeMilliseconds;
    }

    /**
     * Returns the time a frame had on the rung before.
     *
     * @return the time a frame had on the rung before, 0 on the dithered maps
     * @since 1.0.0
     */
    public double getFrameMilliseconds() {
      return this.frameMilliseconds;
    }

    /**
     * Returns the video's frames per second as measured.
     *
     * @return the video's frames per second as measured
     * @since 1.0.0
     */
    public double getVideoFramesPerSecond() {
      return this.videoFramesPerSecond;
    }

    /**
     * Returns whether {@code other} is a change with the same parts.
     *
     * @param other the object to compare with
     * @return whether every part matches
     * @since 1.0.0
     */
    @Override
    public boolean equals(final @Nullable Object other) {
      if (this == other) {
        return true;
      }

      if (!(other instanceof final Change change)) {
        return false;
      }

      return (
        this.from.equals(change.from) &&
        this.to.equals(change.to) &&
        this.down == change.down &&
        Double.compare(this.encodeMilliseconds, change.encodeMilliseconds) == 0 &&
        Double.compare(this.frameMilliseconds, change.frameMilliseconds) == 0 &&
        Double.compare(this.videoFramesPerSecond, change.videoFramesPerSecond) == 0
      );
    }

    /**
     * Returns a hash code built from every part.
     *
     * @return the hash code
     * @since 1.0.0
     */
    @Override
    public int hashCode() {
      return Objects.hash(this.from, this.to, this.down, this.encodeMilliseconds, this.frameMilliseconds, this.videoFramesPerSecond);
    }

    /**
     * Returns a debugging representation with every part.
     *
     * @return the representation
     * @since 1.0.0
     */
    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
        .add("from", this.from)
        .add("to", this.to)
        .add("down", this.down)
        .add("encodeMilliseconds", this.encodeMilliseconds)
        .add("frameMilliseconds", this.frameMilliseconds)
        .add("videoFramesPerSecond", this.videoFramesPerSecond)
        .toString();
    }
  }

  /**
   * How low a pacer's ladder goes when even its lowest encoded rung takes longer than its frames have.
   *
   * <p>This enum is immutable and thread-safe.
   *
   * @since 1.0.0
   */
  public enum Floor {
    /**
     * The ladder ends at the dithered maps, which the screen falls back to.
     *
     * @since 1.0.0
     */
    DITHERED_MAPS,

    /**
     * The ladder ends at its lowest encoded rung, because the screen has no dithered maps to fall back to.
     *
     * @since 1.0.0
     */
    LOWEST_ENCODED_RUNG,
  }

  private enum Direction {
    UP,
    DOWN,
  }
}
