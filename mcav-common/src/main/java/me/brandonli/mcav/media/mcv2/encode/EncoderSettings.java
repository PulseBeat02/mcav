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
package me.brandonli.mcav.media.mcv2.encode;

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
 */
public record EncoderSettings(
  double lambda,
  int keyInterval,
  int motionRange,
  boolean halfPixel,
  boolean compareGlobal,
  double sceneThreshold,
  ReferencePolicy reference,
  @Nullable LiveSearch live
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
   * frames per second, at lambda 72, the largest that keeps the 1080p30 proxy at a VMAF mean of 75 (75.6; gameplay scores
   * 88.8 there). It spends bits differently from {@link #SHIP} - a few percent fewer at the same VMAF on the proxy, under
   * a tenth more on gameplay - and the resource pack decodes both.
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
    this(lambda, keyInterval, motionRange, halfPixel, compareGlobal, sceneThreshold, reference, null);
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
      this.live
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
      this.live
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
      this.live
    );
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
      value
    );
  }
}
