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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Equivalence;
import com.google.common.base.Preconditions;
import java.nio.ByteBuffer;
import java.util.List;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import me.brandonli.mcav.media.player.PlayerException;
import me.brandonli.mcav.media.player.metadata.OriginalAudioMetadata;
import me.brandonli.mcav.utils.ThrowableUtils;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Plays audio through the default sound device of the machine, which is useful for testing pipelines outside of
 * Minecraft. Samples that arrive while the filter is not started are ignored. On machines without a sound device,
 * such as headless servers, {@link #start()} throws a {@link PlayerException} instead.
 *
 * <p>The samples are written through one byte array that is reused for every chunk, so the filter allocates nothing
 * while it plays. It must therefore be used by one pipeline at a time.
 */
public class DirectAudioOutput implements FunctionalAudioFilter {

  /**
   * The format of the samples this filter plays, which is the format every player delivers.
   */
  @VisibleForTesting
  static final AudioFormat FORMAT = new AudioFormat(SAMPLE_RATE, BYTES_PER_SAMPLE * 8, CHANNELS, true, false);

  private static final Equivalence<Object> FAILURE_IDENTITY = Equivalence.identity();
  private static final int BUFFER_MILLIS = 200;

  private final LineOpener lineOpener;
  private volatile @Nullable SourceDataLine line;
  private byte@Nullable[] chunk;

  /**
   * Constructs a new direct audio output. Nothing is opened until {@link #start()} is called.
   */
  public DirectAudioOutput() {
    this(DirectAudioOutput::openDefaultLine);
  }

  /**
   * Constructs an output that opens its line through the specified opener.
   *
   * @param lineOpener opens the audio line in {@link #start()}
   */
  @VisibleForTesting
  DirectAudioOutput(final LineOpener lineOpener) {
    Preconditions.checkNotNull(lineOpener, "Line opener must not be null");
    this.lineOpener = lineOpener;
  }

  private static SourceDataLine openDefaultLine(final AudioFormat format, final int bufferSize) throws LineUnavailableException {
    final DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
    final SourceDataLine opened = (SourceDataLine) AudioSystem.getLine(info);
    opened.open(format, bufferSize);
    return opened;
  }

  /**
   * Writes the samples to the sound device, if it is open. The samples are only read.
   *
   * @param samples  the samples from the position to the limit of the buffer, which is not consumed
   * @param metadata the metadata of the original stream
   * @return false, because the samples are never changed
   */
  @Override
  public boolean applyFilter(final ByteBuffer samples, final OriginalAudioMetadata metadata) {
    Preconditions.checkNotNull(samples, "Samples must not be null");
    final SourceDataLine currentLine = this.line;
    if (currentLine == null) {
      return false;
    }
    final ByteBuffer view = samples.duplicate();
    final int length = view.remaining();
    final byte[] data = this.getChunkArray(length);
    view.get(data, 0, length);
    currentLine.write(data, 0, length);
    return false;
  }

  /**
   * Gets the array chunks are copied into, growing it when a chunk does not fit.
   *
   * @param length the length of the chunk in bytes
   * @return an array of at least that length
   */
  @VisibleForTesting
  byte[] getChunkArray(final int length) {
    final byte[] current = this.chunk;
    if (current != null && current.length >= length) {
      return current;
    }
    final byte[] created = new byte[length];
    this.chunk = created;
    return created;
  }

  /**
   * Opens the default sound device.
   *
   * @throws PlayerException if no sound device supports the audio format of the library, for example on a headless
   *                         machine
   */
  @Override
  public synchronized void start() {
    if (this.line != null) {
      return;
    }
    try {
      final int bufferSize = (SAMPLE_RATE * FRAME_SIZE * BUFFER_MILLIS) / 1000;
      final SourceDataLine opened = this.lineOpener.open(FORMAT, bufferSize);
      try {
        opened.start();
      } catch (final RuntimeException | Error failure) {
        ThrowableUtils.throwIfFatal(failure);
        try {
          opened.close();
        } catch (final RuntimeException | Error cleanup) {
          suppressCleanupFailure(failure, cleanup);
        }
        throw failure;
      }
      this.line = opened;
    } catch (final LineUnavailableException | IllegalArgumentException exception) {
      final String message = exception.getMessage();
      throw new PlayerException("Could not open the default audio device: " + message, exception);
    }
  }

  /**
   * Closes the sound device, discarding samples that have not been played yet. Ordinary stop or flush failures do
   * not prevent closing the line; the first failure is rethrown with later failures suppressed. Fatal VM errors
   * propagate immediately.
   */
  @Override
  public synchronized void release() {
    final SourceDataLine currentLine = this.line;
    if (currentLine == null) {
      return;
    }
    this.line = null;
    Throwable failure = null;
    final List<Runnable> cleanup = List.of(currentLine::stop, currentLine::flush, currentLine::close);
    for (final Runnable action : cleanup) {
      try {
        action.run();
      } catch (final RuntimeException | Error exception) {
        ThrowableUtils.throwIfFatal(exception);
        if (failure == null) {
          failure = exception;
        } else {
          suppressCleanupFailure(failure, exception);
        }
      }
    }
    if (failure instanceof final RuntimeException runtime) {
      throw runtime;
    }
    if (failure instanceof final Error error) {
      throw error;
    }
  }

  private static void suppressCleanupFailure(final Throwable failure, final Throwable cleanup) {
    ThrowableUtils.throwIfFatal(cleanup);
    final boolean same = FAILURE_IDENTITY.equivalent(failure, cleanup);
    if (!same) {
      failure.addSuppressed(cleanup);
    }
  }

  /**
   * Opens an audio line with a format and a buffer size.
   */
  @FunctionalInterface
  interface LineOpener {
    /**
     * Opens a line.
     *
     * @param format     the format of the samples
     * @param bufferSize the size of the line buffer in bytes
     * @return the opened line, not yet started
     * @throws LineUnavailableException if no line can be opened
     */
    SourceDataLine open(AudioFormat format, int bufferSize) throws LineUnavailableException;
  }
}
