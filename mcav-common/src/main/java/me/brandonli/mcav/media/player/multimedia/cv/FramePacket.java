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

import java.nio.ByteBuffer;
import me.brandonli.mcav.media.player.metadata.OriginalMetadata;
import org.bytedeco.javacv.Frame;

class FramePacket {

  private final OriginalMetadata metadata;
  private final ByteBuffer data;
  private final long timestamp;
  private final Frame frame;

  FramePacket(final OriginalMetadata metadata, final ByteBuffer data, final long timestamp, final Frame frame) {
    this.metadata = metadata;
    this.data = data;
    this.timestamp = timestamp;
    this.frame = frame;
  }

  OriginalMetadata getMetadata() {
    return this.metadata;
  }

  ByteBuffer getData() {
    return this.data;
  }

  long getTimestamp() {
    return this.timestamp;
  }

  Frame getFrame() {
    return this.frame;
  }

  static class AudioFramePacket extends FramePacket {

    AudioFramePacket(final ByteBuffer data, final OriginalMetadata metadata, final long timestamp, final Frame frame) {
      super(metadata, data, timestamp, frame);
    }
  }

  static class VideoFramePacket extends FramePacket {

    private final int width;
    private final int height;

    VideoFramePacket(
      final ByteBuffer data,
      final OriginalMetadata metadata,
      final int width,
      final int height,
      final long timestamp,
      final Frame frame
    ) {
      super(metadata, data, timestamp, frame);
      this.width = width;
      this.height = height;
    }

    int getWidth() {
      return this.width;
    }

    int getHeight() {
      return this.height;
    }
  }

  static AudioFramePacket audio(final ByteBuffer data, final OriginalMetadata metadata, final long timestamp, final Frame frame) {
    return new AudioFramePacket(data, metadata, timestamp, frame);
  }

  static VideoFramePacket video(
    final ByteBuffer data,
    final OriginalMetadata metadata,
    final int width,
    final int height,
    final long timestamp,
    final Frame frame
  ) {
    return new VideoFramePacket(data, metadata, width, height, timestamp, frame);
  }
}
