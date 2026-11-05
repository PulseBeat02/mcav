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

import java.util.HexFormat;
import jdk.jfr.Category;
import jdk.jfr.DataAmount;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.Timespan;
import jdk.jfr.Timestamp;

@Name("me.brandonli.mcav.Mcv2Frame")
@Label("MCV2 Frame")
@Category({ "mcav", "MCV2" })
@Description("An encoded MCV2 frame: when it arrived, how long it took, and which viewers it reached")
final class Mcv2FrameEvent extends Event {

  static final int FINGERPRINT_PIXELS = 24;

  private static final int HEXADECIMAL_DIGITS_PER_BYTE = 2;

  private static final int CENTER_DIVISOR = 2;

  private static final int BYTE_MASK = 0xFF;

  private static final int GREEN_OFFSET = 1;
  private static final int BLUE_OFFSET = 2;

  private static final int GREEN_WEIGHT = 2;
  private static final int LUMA_DIVISOR = 4;

  @Label("Frame Id")
  long frameId;

  @Label("Keyframe")
  boolean keyframe;

  @Label("Bytes")
  @DataAmount
  int bytes;

  @Label("Map Colors")
  @Description("The map colors of the frame's pages, or -1 when it had more pages than the screen has slots")
  int colors;

  @Label("Arrived")
  @Timestamp(Timestamp.MILLISECONDS_SINCE_EPOCH)
  long arrived;

  @Label("Sent")
  @Timestamp(Timestamp.MILLISECONDS_SINCE_EPOCH)
  long sent;

  @Label("Encode Time")
  @Timespan(Timespan.NANOSECONDS)
  long encodeTime;

  @Label("Viewers Sent")
  int sentTo;

  @Label("Viewers Behind")
  @Description("Viewers held back because their connection was over the backlog limit")
  int behind;

  @Label("Viewers Waiting")
  @Description("Viewers held back because they did not hold the frame's reference")
  int waiting;

  @Label("Largest Backlog")
  @DataAmount
  long backlog;

  @Label("Fingerprint")
  String fingerprint = "";

  static String fingerprint(final byte[] pictureBytes, final int width, final int height) {
    final StringBuilder builder = new StringBuilder(HEXADECIMAL_DIGITS_PER_BYTE * FINGERPRINT_PIXELS);

    final int center = Mcv2Decoder.ROOT_SIZE / CENTER_DIVISOR;
    for (
      int superblockIndex = 0;
      superblockIndex < FINGERPRINT_PIXELS && center + Mcv2Decoder.ROOT_SIZE * superblockIndex < width && height > center;
      superblockIndex++
    ) {
      final int pixelOffset = (center * width + center + Mcv2Decoder.ROOT_SIZE * superblockIndex) * Mcv2Decoder.CHANNELS;
      final int luma =
        ((pictureBytes[pixelOffset] & BYTE_MASK) +
          GREEN_WEIGHT * (pictureBytes[pixelOffset + GREEN_OFFSET] & BYTE_MASK) +
          (pictureBytes[pixelOffset + BLUE_OFFSET] & BYTE_MASK)) /
        LUMA_DIVISOR;
      builder.append(HexFormat.of().toHexDigits((byte) luma));
    }

    return builder.toString();
  }
}
