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

  @Label("Frame Id")
  long frameId;

  @Label("Keyframe")
  boolean keyframe;

  @Label("Bytes")
  @DataAmount
  int bytes;

  @Label("Map Colours")
  @Description("The map colours of the frame's pages, or -1 when it had more pages than the screen has slots")
  int colors;

  @Label("Arrived")
  @Timestamp(Timestamp.MILLISECONDS_SINCE_EPOCH)
  long arrived;

  @Label("Sent")
  @Timestamp(Timestamp.MILLISECONDS_SINCE_EPOCH)
  long sent;

  @Label("Encode Time")
  @Timespan(Timespan.NANOSECONDS)
  long encode;

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

  static String fingerprint(final byte[] rgb, final int width, final int height) {
    final StringBuilder builder = new StringBuilder(2 * FINGERPRINT_PIXELS);

    final int centre = Mcv2Decoder.ROOT_SIZE / 2;
    for (
      int superblockIndex = 0;
      superblockIndex < FINGERPRINT_PIXELS && centre + Mcv2Decoder.ROOT_SIZE * superblockIndex < width && height > centre;
      superblockIndex++
    ) {
      final int at = (centre * width + centre + Mcv2Decoder.ROOT_SIZE * superblockIndex) * Mcv2Decoder.CHANNELS;
      final int luma = ((rgb[at] & 0xFF) + 2 * (rgb[at + 1] & 0xFF) + (rgb[at + 2] & 0xFF)) / 4;
      builder.append(HexFormat.of().toHexDigits((byte) luma));
    }
    return builder.toString();
  }
}
