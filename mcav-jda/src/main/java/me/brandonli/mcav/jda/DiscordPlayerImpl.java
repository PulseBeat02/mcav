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
package me.brandonli.mcav.jda;

import java.nio.ByteBuffer;
import me.brandonli.mcav.media.player.metadata.AudioMetadata;
import me.brandonli.mcav.utils.natives.ByteUtils;

/**
 * A class that implements the {@code DiscordFilter} interface, responsible for processing and filtering
 * audio data. This implementation manages a buffer to handle audio samples and applies transformations
 * on received audio chunks.
 * <p>
 * The {@code DiscordFilterImpl} includes methods to determine if enough audio data is available for
 * processing, provide audio data in a fixed 20ms size, and apply filters to incoming audio samples and
 * associated metadata.
 */
public class DiscordPlayerImpl implements DiscordPlayer {

  private static final int TWENTY_MS_SIZE = (48000 * 2 * 2 * 20) / 1000;

  private final ByteBuffer buffer;

  DiscordPlayerImpl() {
    this.buffer = ByteBuffer.allocate(1024 * 1024);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean canProvide() {
    return this.buffer.position() >= TWENTY_MS_SIZE;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ByteBuffer provide20MsAudio() {
    if (!this.canProvide()) {
      return ByteBuffer.allocate(0);
    }
    final byte[] audioChunk = new byte[TWENTY_MS_SIZE];
    this.buffer.flip();
    this.buffer.get(audioChunk, 0, TWENTY_MS_SIZE);
    this.buffer.compact();
    return ByteBuffer.wrap(audioChunk);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void applyFilter(final ByteBuffer samples, final AudioMetadata metadata) {
    final ByteBuffer clamped = ByteUtils.clampNormalBufferToBigEndian(samples);
    if (this.buffer.remaining() >= clamped.remaining()) {
      this.buffer.put(clamped);
    } else {
      this.buffer.clear();
      this.buffer.put(clamped);
    }
  }
}
