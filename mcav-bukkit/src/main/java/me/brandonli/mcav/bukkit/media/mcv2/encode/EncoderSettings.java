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
package me.brandonli.mcav.bukkit.media.mcv2.encode;

import com.google.common.base.Preconditions;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The encoder choices of an MCV2 profile. The two shipped profiles are the 1080p30 operating points of the research
 * codec's last kept round (round 19), chosen by the owner's rule: the cheapest point reaching VMAF 75, and the cheapest
 * reaching VMAF 70.
 *
 * @param lambda          the rate-distortion trade, weighted squared error per logical bit
 * @param keyInterval     frames between keyframes; 1 makes every frame independent
 * @param motionRange     the local motion search range in pixels around the global vector, at most 63
 * @param halfPixel       whether the local search refines to half pixels
 * @param compareGlobal   whether a frame is also tried with zero global motion
 * @param sceneThreshold  the mean absolute luma change after global prediction that forces a keyframe
 * @param reference       which decoded frame P frames predict from
 * @param live            the cheaper search of a live profile, or null for the reference's exhaustive search
 * @param adaptive        the search and lambda of the frames of a live profile whose source moves, or null for one search
 *                        for every frame
 */
public record EncoderSettings(
  double lambda,
  int keyInterval,
  int motionRange,
  boolean halfPixel,
  boolean compareGlobal,
  double sceneThreshold,
  ReferencePolicy reference,
  @Nullable LiveSearch live,
  @Nullable Adaptive adaptive
) {
  /** The largest local motion range, in pixels: a motion record's signed bytes are half pixels. */
  private static final int MAX_MOTION_RANGE = 63;

  /** The reference's keyframe interval, local motion range and scene-cut threshold, which every profile keeps. */
  private static final int KEY_INTERVAL = 60;

  private static final int MOTION_RANGE = 24;

  private static final double SCENE_THRESHOLD = 45.0;

  private static final double SHIP_LAMBDA = 65.255994022;

  private static final double LOW_LAMBDA = 137.730758207;

  private static final double LIVE_LAMBDA = 72;

  /**
   * The live-fast profile's lambda: where its search reaches the VMAF mean the live search reaches at 72 on the 1080p30
   * proxy (75.7), and on the 30 fps gameplay clip nearly so (55/72 of live's lambda there too, 56.5/74.1).
   */
  private static final double LIVE_FAST_LAMBDA = 55;

  /**
   * The average temporal information above which the adaptive profile codes its frames with the live-fast search, and
   * the one below which it returns to the live search: the calm sources measure 1.5 to 5.9 (the 1080p30 and 1080p60
   * proxies, Sintel, a dinner scene), the two gameplay captures 7.8 to 19.2.
   */
  private static final double ADAPTIVE_ENTER = 8;

  private static final double ADAPTIVE_LEAVE = 6;

  /** The live profile's keyframe interval: four seconds at 30 frames per second. */
  private static final int LIVE_KEY_INTERVAL = 120;

  /** The shipped profile: {@code p30r19-compact_final-65p255994}, 3.458 map Mbps at VMAF 77.93 on the 1080p30 source. */
  public static final EncoderSettings SHIP = new EncoderSettings(
    SHIP_LAMBDA,
    KEY_INTERVAL,
    MOTION_RANGE,
    true,
    true,
    SCENE_THRESHOLD,
    ReferencePolicy.PREVIOUS_FRAME
  );

  /** The low-bandwidth profile: {@code p30r19-compact_final-137p730758}, 2.122 map Mbps at VMAF 70.58. */
  public static final EncoderSettings LOW_BANDWIDTH = new EncoderSettings(
    LOW_LAMBDA,
    KEY_INTERVAL,
    MOTION_RANGE,
    true,
    true,
    SCENE_THRESHOLD,
    ReferencePolicy.PREVIOUS_FRAME
  );

  /**
   * The live profile: the live search ({@link LiveSearch#LIVE}) and a keyframe every 120 frames, four seconds at 30
   * frames per second, at lambda 72, the largest that keeps the 1080p30 proxy at a VMAF mean of 75 (75.65; gameplay
   * scores 88.8 there). It spends bits differently from {@link #SHIP} - a few percent fewer at the same VMAF on the
   * proxy, under a tenth more on gameplay - and the resource pack decodes both.
   */
  public static final EncoderSettings LIVE = new EncoderSettings(
    LIVE_LAMBDA,
    LIVE_KEY_INTERVAL,
    MOTION_RANGE,
    true,
    true,
    SCENE_THRESHOLD,
    ReferencePolicy.PREVIOUS_FRAME,
    LiveSearch.LIVE
  );

  /**
   * The live-fast profile: {@link #LIVE} with the fastest search of the preset ladder, {@link LiveSearch#LIVE_FAST}, at
   * lambda 55, where it reaches the quality {@link #LIVE} reaches at 72.
   */
  public static final EncoderSettings LIVE_FAST = LIVE.withLive(LiveSearch.LIVE_FAST).withLambda(LIVE_FAST_LAMBDA);

  /**
   * The adaptive profile: {@link #LIVE} on calm pictures, and {@link #LIVE_FAST}'s search and lambda once the source
   * moves (an average temporal information above 8, until it falls below 6), where VMAF forgives the faster search's
   * error and the frames need the time most.
   */
  public static final EncoderSettings LIVE_ADAPTIVE = LIVE.withAdaptive(
    new Adaptive(LiveSearch.LIVE_FAST, LIVE_FAST_LAMBDA, ADAPTIVE_ENTER, ADAPTIVE_LEAVE)
  );

  /**
   * The second search of an adaptive live profile. The profile's own search and lambda code calm pictures; once the
   * source's average temporal information ({@link MotionLambda}, which the profile's search must measure) rises above
   * {@code enter}, the frames are coded with this search at this lambda, until it falls below {@code leave}. The two
   * thresholds apart keep a source near one of them from switching back and forth. Both searches write the same format
   * and predict from the same pictures, so a switch needs no keyframe; the average depends on the source alone, so the
   * stream does not depend on how long a frame took.
   *
   * @param search the search of frames in motion
   * @param lambda its lambda, which the motion raises as it raises the profile's
   * @param enter  the average temporal information above which frames are coded in motion
   * @param leave  the one below which they are coded calm again, at most {@code enter}
   */
  public record Adaptive(LiveSearch search, double lambda, double enter, double leave) {
    /**
     * Validates the adaptive search.
     *
     * @throws IllegalArgumentException if a value is out of range
     */
    public Adaptive {
      Preconditions.checkNotNull(search, "Search must not be null");
      Preconditions.checkArgument(lambda >= 0 && Double.isFinite(lambda), "Lambda must be finite and non-negative");
      Preconditions.checkArgument(leave >= 0 && leave <= enter && Double.isFinite(enter), "Thresholds must be 0 <= leave <= enter");
    }
  }

  /** Which decoded frame P frames predict from. */
  public enum ReferencePolicy {
    /** The previous decoded frame, as the research codec was tuned; the shipped behaviour. */
    PREVIOUS_FRAME,
    /** The last keyframe only, which a receiver can hold without decoding every frame. */
    LAST_KEYFRAME,
  }

  /**
   * Constructs settings that search like the reference encoder.
   *
   * @param lambda         the rate-distortion trade, weighted squared error per logical bit
   * @param keyInterval    frames between keyframes; 1 makes every frame independent
   * @param motionRange    the local motion search range in pixels around the global vector, at most 63
   * @param halfPixel      whether the local search refines to half pixels
   * @param compareGlobal  whether a frame is also tried with zero global motion
   * @param sceneThreshold the mean absolute luma change after global prediction that forces a keyframe
   * @param reference      which decoded frame P frames predict from
   * @throws IllegalArgumentException if a value is out of range
   */
  public EncoderSettings(
    final double lambda,
    final int keyInterval,
    final int motionRange,
    final boolean halfPixel,
    final boolean compareGlobal,
    final double sceneThreshold,
    final ReferencePolicy reference
  ) {
    this(lambda, keyInterval, motionRange, halfPixel, compareGlobal, sceneThreshold, reference, null, null);
  }

  /**
   * Constructs settings that search one way for every frame.
   *
   * @param lambda         the rate-distortion trade, weighted squared error per logical bit
   * @param keyInterval    frames between keyframes; 1 makes every frame independent
   * @param motionRange    the local motion search range in pixels around the global vector, at most 63
   * @param halfPixel      whether the local search refines to half pixels
   * @param compareGlobal  whether a frame is also tried with zero global motion
   * @param sceneThreshold the mean absolute luma change after global prediction that forces a keyframe
   * @param reference      which decoded frame P frames predict from
   * @param live           the cheaper search of a live profile, or null for the reference's exhaustive search
   * @throws IllegalArgumentException if a value is out of range
   */
  public EncoderSettings(
    final double lambda,
    final int keyInterval,
    final int motionRange,
    final boolean halfPixel,
    final boolean compareGlobal,
    final double sceneThreshold,
    final ReferencePolicy reference,
    final @Nullable LiveSearch live
  ) {
    this(lambda, keyInterval, motionRange, halfPixel, compareGlobal, sceneThreshold, reference, live, null);
  }

  /**
   * Validates the settings.
   *
   * @throws IllegalArgumentException if a value is out of range
   */
  public EncoderSettings {
    Preconditions.checkArgument(lambda >= 0 && Double.isFinite(lambda), "Lambda must be finite and non-negative");
    Preconditions.checkArgument(keyInterval >= 1, "Key interval must be positive");
    Preconditions.checkArgument(motionRange >= 0 && motionRange <= MAX_MOTION_RANGE, "Motion range must be 0 to 63");
    Preconditions.checkArgument(sceneThreshold > 0 && Double.isFinite(sceneThreshold), "Scene threshold must be positive");
    Preconditions.checkNotNull(reference, "Reference policy must not be null");
    Preconditions.checkArgument(
      adaptive == null || (live != null && live.motionLambda()),
      "An adaptive profile must be a live one that measures its source's motion"
    );
  }

  /**
   * Copies these settings with another lambda.
   *
   * @param value the lambda
   * @return the new settings
   */
  public EncoderSettings withLambda(final double value) {
    return new EncoderSettings(
      value,
      this.keyInterval,
      this.motionRange,
      this.halfPixel,
      this.compareGlobal,
      this.sceneThreshold,
      this.reference,
      this.live,
      this.adaptive
    );
  }

  /**
   * Copies these settings with another key interval.
   *
   * @param value the key interval
   * @return the new settings
   */
  public EncoderSettings withKeyInterval(final int value) {
    return new EncoderSettings(
      this.lambda,
      value,
      this.motionRange,
      this.halfPixel,
      this.compareGlobal,
      this.sceneThreshold,
      this.reference,
      this.live,
      this.adaptive
    );
  }

  /**
   * Copies these settings with another reference policy.
   *
   * @param value the policy
   * @return the new settings
   */
  public EncoderSettings withReference(final ReferencePolicy value) {
    return new EncoderSettings(
      this.lambda,
      this.keyInterval,
      this.motionRange,
      this.halfPixel,
      this.compareGlobal,
      this.sceneThreshold,
      value,
      this.live,
      this.adaptive
    );
  }

  /**
   * Gets the next rung down the preset ladder - the reference's exhaustive search, then {@link LiveSearch#LIVE}, then
   * the adaptive profile (live on calm pictures, live-fast in motion), then {@link LiveSearch#LIVE_FAST}, each faster
   * than the one before - which a live screen that cannot keep up steps down before it encodes fewer frames. The
   * live-fast search reaches the live search's quality at 55/72 of its lambda, so the steps to it scale the lambda by
   * as much ({@code LIVE.faster()} is {@link #LIVE_ADAPTIVE}, whose {@code faster()} is {@link #LIVE_FAST}); everything
   * else stays.
   *
   * @return the settings with the next faster search, or null when the search is the fastest or not on the ladder
   */
  public @Nullable EncoderSettings faster() {
    final LiveSearch search = this.live;
    if (search == null) {
      return this.withLive(LiveSearch.LIVE);
    }
    final Adaptive second = this.adaptive;
    if (second != null) {
      return this.frame(true);
    }
    return LiveSearch.LIVE.equals(search)
      ? this.withAdaptive(
          new Adaptive(LiveSearch.LIVE_FAST, (this.lambda * LIVE_FAST_LAMBDA) / LIVE_LAMBDA, ADAPTIVE_ENTER, ADAPTIVE_LEAVE)
        )
      : null;
  }

  /**
   * Copies these settings with another search.
   *
   * @param value the search of a live profile, or null for the reference's
   * @return the new settings
   */
  public EncoderSettings withLive(final @Nullable LiveSearch value) {
    return new EncoderSettings(
      this.lambda,
      this.keyInterval,
      this.motionRange,
      this.halfPixel,
      this.compareGlobal,
      this.sceneThreshold,
      this.reference,
      value,
      value == null ? null : this.adaptive
    );
  }

  /**
   * Copies these settings with another adaptive search.
   *
   * @param value the search of frames in motion, or null for one search for every frame
   * @return the new settings
   * @throws IllegalArgumentException if these are not settings of a live profile that measures its source's motion
   */
  public EncoderSettings withAdaptive(final @Nullable Adaptive value) {
    return new EncoderSettings(
      this.lambda,
      this.keyInterval,
      this.motionRange,
      this.halfPixel,
      this.compareGlobal,
      this.sceneThreshold,
      this.reference,
      this.live,
      value
    );
  }

  /**
   * Gets the settings a frame is coded with: these, or, in motion, with the adaptive search and its lambda.
   *
   * @param moving whether the source moves by the adaptive thresholds
   * @return the settings of the frame, without an adaptive part
   */
  EncoderSettings frame(final boolean moving) {
    final Adaptive second = this.adaptive;
    if (second == null) {
      return this;
    }
    return moving ? this.withAdaptive(null).withLive(second.search()).withLambda(second.lambda()) : this.withAdaptive(null);
  }
}
