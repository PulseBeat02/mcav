/*
 * This file is part of mcav, a media playback library for Minecraft
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
import me.brandonli.mcav.media.player.metadata.Metadata;

class FramePacket {

  private final Metadata metadata;
  private final ByteBuffer data;
  private final long timestamp;

  FramePacket(final Metadata metadata, final ByteBuffer data, final long timestamp) {
    this.metadata = metadata;
    this.data = data;
    this.timestamp = timestamp;
  }

  public Metadata getMetadata() {
    return this.metadata;
  }

  public ByteBuffer getData() {
    return this.data;
  }

  public long getTimestamp() {
    return this.timestamp;
  }

  static class AudioFramePacket extends FramePacket {

    public AudioFramePacket(final ByteBuffer data, final Metadata metadata, final long timestamp) {
      super(metadata, data, timestamp);
    }
  }

  static class VideoFramePacket extends FramePacket {

    private final int width;
    private final int height;

    public VideoFramePacket(final ByteBuffer data, final Metadata metadata, final int width, final int height, final long timestamp) {
      super(metadata, data, timestamp);
      this.width = width;
      this.height = height;
    }

    public int getWidth() {
      return this.width;
    }

    public int getHeight() {
      return this.height;
    }
  }

  static AudioFramePacket audio(final ByteBuffer data, final Metadata metadata, final long timestamp) {
    return new AudioFramePacket(data, metadata, timestamp);
  }

  static VideoFramePacket video(final ByteBuffer data, final Metadata metadata, final int width, final int height, final long timestamp) {
    return new VideoFramePacket(data, metadata, width, height, timestamp);
  }
}
