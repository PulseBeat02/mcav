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
package me.brandonli.mcav.media.player.multimedia.cv;

import me.brandonli.mcav.media.image.MatImageBuffer;
import org.checkerframework.checker.nullness.qual.Nullable;

// The image comes from the session's ImagePool; it is null while no video pipeline is attached, and the frame then only
// moves the clock and the position.
final class DecodedVideoFrame {

  private final @Nullable MatImageBuffer image;
  private final long timestampMicroseconds;

  DecodedVideoFrame(final @Nullable MatImageBuffer image, final long timestampMicroseconds) {
    this.image = image;
    this.timestampMicroseconds = timestampMicroseconds;
  }

  @Nullable MatImageBuffer getImage() {
    return this.image;
  }

  long getTimestampMicroseconds() {
    return this.timestampMicroseconds;
  }
}
