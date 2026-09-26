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
package me.brandonli.mcav.sandbox.command.video;

import me.brandonli.mcav.media.mcv2.encode.EncoderSettings;

/**
 * The MCV2 encoder profiles the sandbox offers.
 */
public enum Mcv2Profile {
  /** The shipped profile: previous-frame prediction at lambda 65.256, 3.46 map Mbps at 1080p30. */
  SHIP,
  /** The low-bandwidth profile: lambda 137.731, 2.12 map Mbps at 1080p30. */
  LOW,
  /**
   * P frames predict from the last keyframe only, so a viewer who misses frames recovers with the next frame instead
   * of the next keyframe; about 73% more bandwidth for the same quality.
   */
  KEYFRAME,
  /** Every frame is a keyframe, so no viewer ever depends on a frame it missed; about 144% more bandwidth. */
  INTRA,
  /**
   * The live profile: the search made for encoding as the video plays (a screen that falls behind lowers its resolution
   * or frame rate), previous-frame prediction, the same bitstream as the others.
   */
  LIVE,
  /**
   * The live profile predicting from the last keyframe, for viewers whose clients draw fewer frames per second than
   * the video has: a client decodes at most one video frame per frame it draws, and a frame it missed is the
   * reference of the next one under previous-frame prediction.
   */
  LIVE_KEYFRAME;

  /**
   * Gets the encoder settings of the profile.
   *
   * @return the settings
   */
  public EncoderSettings getSettings() {
    return switch (this) {
      case SHIP -> EncoderSettings.SHIP;
      case LOW -> EncoderSettings.LOW_BANDWIDTH;
      case KEYFRAME -> EncoderSettings.SHIP.withReference(EncoderSettings.ReferencePolicy.LAST_KEYFRAME);
      case INTRA -> EncoderSettings.SHIP.withKeyInterval(1);
      case LIVE -> EncoderSettings.LIVE;
      case LIVE_KEYFRAME -> EncoderSettings.LIVE.withReference(EncoderSettings.ReferencePolicy.LAST_KEYFRAME);
    };
  }
}
