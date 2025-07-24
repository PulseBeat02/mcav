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
package me.brandonli.mcav.media.player.pipeline.filter.audio;

import java.nio.ByteBuffer;
import javax.sound.sampled.*;
import me.brandonli.mcav.media.player.PlayerException;
import me.brandonli.mcav.media.player.metadata.OriginalAudioMetadata;

/**
 * Streams audio samples directly to the system's default audio output device.
 */
public class DirectAudioOutput implements FunctionalAudioFilter {

  private static final AudioFormat AUDIO_FORMAT = new AudioFormat(48000, 16, 2, true, false);

  private SourceDataLine line;

  /**
   * Constructs a new {@link DirectAudioOutput} instance.
   */
  public DirectAudioOutput() {
    // no-op
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void applyFilter(final ByteBuffer samples, final OriginalAudioMetadata metadata) {
    if (this.line == null) {
      throw new PlayerException("Audio line is not open!");
    }
    final byte[] data = new byte[samples.remaining()];
    samples.get(data);
    this.line.write(data, 0, data.length);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void start() {
    try {
      final DataLine.Info info = new DataLine.Info(SourceDataLine.class, AUDIO_FORMAT);
      this.line = (SourceDataLine) AudioSystem.getLine(info);
      this.line.open(AUDIO_FORMAT);
      this.line.start();
    } catch (final LineUnavailableException e) {
      throw new PlayerException(e.getMessage(), e);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void release() {
    if (this.line == null || !this.line.isOpen()) {
      return;
    }
    this.line.close();
  }
}
