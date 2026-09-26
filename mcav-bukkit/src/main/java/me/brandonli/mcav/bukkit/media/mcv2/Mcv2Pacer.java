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
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Keeps an MCV2 screen within what its encoder budget sustains, on purpose, instead of falling behind.
 *
 * <p>The screen's ladder has, from the top: the video size it was asked for at every frame of the video, then every
 * second, third, fourth and sixth frame while at least {@link #MIN_FPS} frames a second remain; then the same for each
 * smaller size the screen can switch to; and last the dithered maps, which need no encoder. The screen reports how long
 * every encoded P frame took; keyframes, which come every few seconds and cost more, are left out. When the smoothed
 * time has been over the time a frame has, {@link #HIGH} of it, for {@link #DOWN_SECONDS}, the pacer steps down to the
 * first rung below that the measured time predicts to fit in {@link #FIT} of its frame time - the encode time grows
 * with the pixels, the frame time with the frames skipped - or to the dithered maps when none does. It steps back up
 * when a rung above has been predicted to fit in {@link #ROOM} of its frame time for {@link #UP_SECONDS}, to the
 * highest such rung, and keeps off a rung it had to leave for {@link #BLOCK_SECONDS}, longer each time.
 * From the dithered maps it tries the lowest encoded rung again after {@link #RETRY_SECONDS}, and twice as long after
 * every try that failed, up to {@link #MAX_RETRY_SECONDS}. So the frames it asks for never take more than the budget
 * gives them for longer than {@link #DOWN_SECONDS}, and it climbs back when the budget frees up.
 *
 * <p>Times are {@link System#nanoTime()} values passed in by the caller. Not thread-safe: the screen calls it from
 * one thread at a time.
 */
public final class Mcv2Pacer {

  /** The lowest frame rate a rung may have, in frames per second. */
  public static final double MIN_FPS = 10;

  /** The share of its frame time over which a rung does not keep up. */
  static final double HIGH = 1.0;

  /** The share of its frame time a rung stepped down to must be predicted to take at most. */
  static final double FIT = 0.85;

  /** The share of its frame time a rung stepped up to must be predicted to take at most. */
  static final double ROOM = 0.7;

  /** How long a rung may be over its frame time before the pacer steps down. */
  static final double DOWN_SECONDS = 1.0;

  /** How long the rung above must fit with room before the pacer steps up. */
  static final double UP_SECONDS = 5.0;

  /** How long the pacer waits on the dithered maps before it tries encoding again. */
  static final double RETRY_SECONDS = 30.0;

  /** The longest wait on the dithered maps between tries. */
  static final double MAX_RETRY_SECONDS = 600.0;

  /**
   * How long the pacer keeps off a rung it had to leave downwards, twice as long every time it has to leave it again,
   * up to {@link #MAX_RETRY_SECONDS}; a rung that holds for {@link #UP_SECONDS} is free again.
   */
  static final double BLOCK_SECONDS = 10.0;

  /** The weight of the newest measurement in the smoothed times. */
  static final double SMOOTHING = 0.2;

  /** The divisors of the video's frame rate a size is tried at, in order. */
  private static final int[] DIVISORS = { 1, 2, 3, 4, 6 };

  private static final long NANOS_PER_SECOND = 1_000_000_000L;

  /** A time that never came: {@link System#nanoTime()} may be negative, so no other value can mark it. */
  private static final long NEVER = Long.MIN_VALUE;

  /**
   * A rung of the ladder.
   *
   * @param width   the video width, or 0 for the dithered maps
   * @param height  the video height, or 0 for the dithered maps
   * @param divisor every how many frames of the video one is encoded, or 0 for the dithered maps
   */
  public record Rung(int width, int height, int divisor) {
    /** The dithered maps, the lowest rung. */
    public static final Rung DITHERED = new Rung(0, 0, 0);

    /**
     * Checks whether this is the dithered maps.
     *
     * @return true for the dithered maps
     */
    public boolean isDithered() {
      return this.divisor == 0;
    }

    /**
     * Gets the frames per second this rung encodes of a video.
     *
     * @param videoFps the video's frames per second
     * @return the encoded frames per second, 0 for the dithered maps
     */
    public double fps(final double videoFps) {
      return this.isDithered() ? 0 : videoFps / this.divisor;
    }

    long pixels() {
      return (long) this.width * this.height;
    }

    /**
     * Describes the rung for a message.
     *
     * @param videoFps the video's frames per second
     * @return for example {@code 1920x1080 at 30 fps}, or {@code the dithered maps}
     */
    public String describe(final double videoFps) {
      return this.isDithered() ? "the dithered maps" : "%dx%d at %s fps".formatted(this.width, this.height, rate(this.fps(videoFps)));
    }
  }

  /** A frame rate for a message: whole when it is within a twentieth of a whole number, else to a tenth. */
  static String rate(final double fps) {
    final long whole = Math.round(fps);
    return Math.abs(fps - whole) < 0.05 ? Long.toString(whole) : String.format(Locale.ROOT, "%.1f", fps);
  }

  /**
   * A step of the pacer.
   *
   * @param from     the rung before
   * @param to       the rung now
   * @param down     whether the step went down the ladder
   * @param encodeMs the smoothed encode time per frame that decided the step, 0 for a try from the dithered maps
   * @param frameMs  the time a frame had on the rung before, 0 on the dithered maps
   * @param videoFps the video's frames per second as measured
   */
  public record Change(Rung from, Rung to, boolean down, double encodeMs, double frameMs, double videoFps) {
    /**
     * Describes the step for the server's operator: what was chosen and why.
     *
     * @return the message
     */
    public String describe() {
      if (this.from.isDithered()) {
        return "MCV2 screen tries encoding again at %s after showing the dithered maps".formatted(this.to.describe(this.videoFps));
      }
      final String timing = String.format(
        Locale.ROOT,
        "encoding %dx%d takes %.1f ms per frame, %s the %.1f ms a frame has at %s fps",
        this.from.width(),
        this.from.height(),
        this.encodeMs,
        this.down ? "more than" : "well within",
        this.frameMs,
        rate(this.from.fps(this.videoFps))
      );
      return this.down
        ? "MCV2 screen steps down to %s: %s with the encoder threads it has".formatted(this.to.describe(this.videoFps), timing)
        : "MCV2 screen steps back up to %s: %s".formatted(this.to.describe(this.videoFps), timing);
    }
  }

  private final List<Rung> ladder;
  private final long[] blockedUntil;
  private final double[] blockSeconds;
  private int current;
  private double videoInterval = Double.NaN;
  private long lastArrival = NEVER;
  private long arrivals;
  private double smoothed = Double.NaN;
  private long overSince = NEVER;
  private long roomSince = NEVER;
  private long retryAt = NEVER;
  private double retrySeconds = RETRY_SECONDS;
  private long settledSince = NEVER;

  /**
   * Creates a pacer that starts at the top of the ladder and may fall back to the dithered maps.
   *
   * @param sizes the video sizes the screen can show, largest first: the size it was asked for, then smaller ones it
   *              can switch to
   * @throws IllegalArgumentException if there is no size, or a size is not positive
   */
  public Mcv2Pacer(final List<int[]> sizes) {
    this(sizes, true);
  }

  /**
   * Creates a pacer that starts at the top of the ladder.
   *
   * @param sizes    the video sizes the screen can show, largest first: the size it was asked for, then smaller ones it
   *                 can switch to
   * @param dithered whether the screen can fall back to the dithered maps; without them, the lowest encoded rung is as
   *                 low as the pacer goes, even when its frames take longer than they have
   * @throws IllegalArgumentException if there is no size, or a size is not positive
   */
  public Mcv2Pacer(final List<int[]> sizes, final boolean dithered) {
    Preconditions.checkArgument(!sizes.isEmpty(), "At least one size is needed");
    final List<Rung> rungs = new ArrayList<>();
    for (final int[] size : sizes) {
      Preconditions.checkArgument(size.length == 2 && size[0] > 0 && size[1] > 0, "Sizes must be positive width and height");
      for (final int divisor : DIVISORS) {
        rungs.add(new Rung(size[0], size[1], divisor));
      }
    }
    if (dithered) {
      rungs.add(Rung.DITHERED);
    }
    this.ladder = List.copyOf(rungs);
    this.blockedUntil = new long[rungs.size()];
    this.blockSeconds = new double[rungs.size()];
    Arrays.fill(this.blockedUntil, NEVER);
    Arrays.fill(this.blockSeconds, BLOCK_SECONDS);
  }

  /**
   * Gets the rungs of the ladder, top first; the last is {@link Rung#DITHERED} when the screen can fall back to it.
   *
   * @return the ladder
   */
  public List<Rung> getLadder() {
    return this.ladder;
  }

  /**
   * Gets the rung the screen is on.
   *
   * @return the rung
   */
  public Rung getRung() {
    return this.ladder.get(this.current);
  }

  /**
   * Gets the video's frames per second, as measured from the frames it hands over.
   *
   * @return the frame rate, or NaN before two frames arrived
   */
  public double getVideoFps() {
    return NANOS_PER_SECOND / this.videoInterval;
  }

  /**
   * Takes a frame of the video and decides whether it is encoded. On the dithered maps it is not, until the time to
   * try again comes, which moves the pacer to the lowest encoded rung.
   *
   * @param now the time the frame arrived
   * @return the step a try from the dithered maps takes, which the caller carries out, or null
   */
  public @Nullable Change arrive(final long now) {
    if (this.lastArrival != NEVER && now > this.lastArrival) {
      final double interval = now - this.lastArrival;
      this.videoInterval = Double.isNaN(this.videoInterval) ? interval : this.videoInterval + SMOOTHING * (interval - this.videoInterval);
    }
    this.lastArrival = now;
    Change change = null;
    if (this.getRung().isDithered() && now >= this.retryAt) {
      change = this.move(this.lowestEncoded(), false, 0, 0);
    }
    this.arrivals++;
    return change;
  }

  /**
   * Checks whether the frame that arrived last is to be encoded on the current rung.
   *
   * @return true to encode it
   */
  public boolean isEncoded() {
    final Rung rung = this.getRung();
    return !rung.isDithered() && (this.arrivals - 1) % rung.divisor() == 0;
  }

  /**
   * Reports an encoded frame.
   *
   * @param milliseconds how long the frame took to encode, waiting for the budget's threads included
   * @param keyframe     whether it was a keyframe, which is left out
   * @param now          the time the encode finished
   * @return the step it causes, which the caller carries out, or null
   */
  public @Nullable Change encoded(final double milliseconds, final boolean keyframe, final long now) {
    Preconditions.checkArgument(milliseconds >= 0 && Double.isFinite(milliseconds), "Time must be finite and non-negative");
    final Rung rung = this.getRung();
    if (keyframe || rung.isDithered() || Double.isNaN(this.videoInterval)) {
      return null;
    }
    this.smoothed = Double.isNaN(this.smoothed) ? milliseconds : this.smoothed + SMOOTHING * (milliseconds - this.smoothed);
    if (this.settledSince == NEVER) {
      this.settledSince = now;
    }
    final double frameMs = this.frameMs(rung);
    if (this.smoothed > HIGH * frameMs) {
      this.roomSince = NEVER;
      if (this.overSince == NEVER) {
        this.overSince = now;
      }
      if (now - this.overSince >= seconds(DOWN_SECONDS)) {
        return this.stepDown(now, frameMs);
      }
      return null;
    }
    this.overSince = NEVER;
    if (now - this.settledSince >= seconds(UP_SECONDS)) {
      // a rung that held long enough ends the waits that failed tries left behind
      this.retrySeconds = RETRY_SECONDS;
      this.blockSeconds[this.current] = BLOCK_SECONDS;
    }
    final int above = this.bestAbove(now);
    if (above < 0) {
      this.roomSince = NEVER;
      return null;
    }
    if (this.roomSince == NEVER) {
      this.roomSince = now;
    }
    return now - this.roomSince >= seconds(UP_SECONDS) ? this.move(above, false, this.smoothed, frameMs) : null;
  }

  private @Nullable Change stepDown(final long now, final double frameMs) {
    final int left = this.current;
    this.blockedUntil[left] = now + seconds(this.blockSeconds[left]);
    this.blockSeconds[left] = Math.min(MAX_RETRY_SECONDS, this.blockSeconds[left] * 2);
    final int encoded = this.encodedRungs();
    for (int next = this.current + 1; next < encoded; next++) {
      final Rung rung = this.ladder.get(next);
      if (this.isAllowed(rung) && this.predict(next) <= FIT * this.frameMs(rung)) {
        return this.move(next, true, this.smoothed, frameMs);
      }
    }
    if (encoded == this.ladder.size()) {
      // no dithered maps to fall back to: the lowest encoded rung is the floor
      final int lowest = this.lowestEncoded();
      this.overSince = NEVER;
      return lowest > this.current ? this.move(lowest, true, this.smoothed, frameMs) : null;
    }
    final Change change = this.move(this.ladder.size() - 1, true, this.smoothed, frameMs);
    this.retryAt = now + seconds(this.retrySeconds);
    this.retrySeconds = Math.min(MAX_RETRY_SECONDS, this.retrySeconds * 2);
    return change;
  }

  /** How many rungs encode: all but the dithered maps, if the ladder has them. */
  private int encodedRungs() {
    return this.ladder.getLast().isDithered() ? this.ladder.size() - 1 : this.ladder.size();
  }

  private Change move(final int next, final boolean down, final double encodeMs, final double frameMs) {
    final Change change = new Change(this.getRung(), this.ladder.get(next), down, encodeMs, frameMs, this.getVideoFps());
    this.current = next;
    this.smoothed = Double.NaN;
    this.overSince = NEVER;
    this.roomSince = NEVER;
    this.settledSince = NEVER;
    this.arrivals = 0;
    return change;
  }

  /** The time a frame has on a rung, in milliseconds. */
  private double frameMs(final Rung rung) {
    return (rung.divisor() * this.videoInterval) / 1e6;
  }

  /** The encode time the measurements on the current rung predict for another rung: it grows with the pixels. */
  private double predict(final int index) {
    return (this.smoothed * this.ladder.get(index).pixels()) / this.getRung().pixels();
  }

  /**
   * Whether a rung encodes every frame of the video, or leaves at least the lowest frame rate; a video's rate as
   * measured wavers around its nominal one, so a rung within a twentieth of the lowest rate counts as reaching it.
   */
  private boolean isAllowed(final Rung rung) {
    return rung.divisor() == 1 || rung.fps(this.getVideoFps()) >= MIN_FPS * 0.95;
  }

  /**
   * The highest rung above the current one that is allowed, not kept off, and predicted to fit with room, or -1: after
   * a smaller size, the best frame rate the size it climbs back to has room for.
   */
  private int bestAbove(final long now) {
    for (int index = 0; index < this.current; index++) {
      final Rung rung = this.ladder.get(index);
      if (this.isAllowed(rung) && now >= this.blockedUntil[index] && this.predict(index) <= ROOM * this.frameMs(rung)) {
        return index;
      }
    }
    return -1;
  }

  /** The lowest allowed encoded rung; the top rung, which encodes every frame, always is. */
  private int lowestEncoded() {
    int index = this.encodedRungs() - 1;
    while (!this.isAllowed(this.ladder.get(index))) {
      index--;
    }
    return index;
  }

  private static long seconds(final double value) {
    return (long) (value * NANOS_PER_SECOND);
  }
}
