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
import me.brandonli.mcav.media.mcv2.Mcv2Format;

/**
 * One frame of an MCV2 screen for Java Flight Recorder: when it reached the result, how long its encode took, when its
 * pages left for the viewers, and how many viewers took it. It costs nothing unless a recording is running; a server
 * started with {@code -XX:StartFlightRecording} records one per frame, which is how the end-to-end latency, the frames
 * held back per viewer and the backlog are measured. The fingerprint is the luma of 24 pixels of the frame's row 16, one
 * every 32 pixels from x = 16: the centres of the first 24 32-pixel blocks, enough to tell apart frames that carry a
 * block counter there.
 */
@Name("me.brandonli.mcav.Mcv2Frame")
@Label("MCV2 Frame")
@Category({ "mcav", "MCV2" })
@Description("An encoded MCV2 frame: when it arrived, how long it took, and which viewers it reached")
final class Mcv2FrameEvent extends Event {

  /** The pixels of the fingerprint. */
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

  /**
   * The fingerprint of a frame: two hex digits per sampled pixel.
   *
   * @param rgb   the frame, row-major RGB
   * @param width its width
   * @param height its height
   * @return the fingerprint, shorter for a frame narrower than 24 blocks or shorter than 17 rows
   */
  static String fingerprint(final byte[] rgb, final int width, final int height) {
    final StringBuilder builder = new StringBuilder(2 * FINGERPRINT_PIXELS);
    // the centre of each of the first superblocks of the top row
    final int centre = Mcv2Format.ROOT_SIZE / 2;
    for (int i = 0; i < FINGERPRINT_PIXELS && centre + Mcv2Format.ROOT_SIZE * i < width && height > centre; i++) {
      final int at = (centre * width + centre + Mcv2Format.ROOT_SIZE * i) * Mcv2Format.CHANNELS;
      final int luma = ((rgb[at] & 0xFF) + 2 * (rgb[at + 1] & 0xFF) + (rgb[at + 2] & 0xFF)) / 4;
      builder.append(HexFormat.of().toHexDigits((byte) luma));
    }
    return builder.toString();
  }
}
