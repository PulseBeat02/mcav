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
 * after the largest chunk has been accommodated. Use it from one producer at a time. Stop producers before
 * releasing the device; synchronization of start and release does not serialize sample writes with them.
 * Starting again after release opens a new device line.
 *
 * <p>Starting and releasing are thread-safe; filtering must happen on one thread at a time, as the player does.
 *
 * @since 1.0.0
 */
public final class DirectAudioOutput implements FunctionalAudioFilter {

  private static final boolean SIGNED = true;
  private static final boolean BIG_ENDIAN = false;

  @VisibleForTesting
  static final AudioFormat FORMAT = new AudioFormat(SAMPLE_RATE, BYTES_PER_SAMPLE * 8, CHANNELS, SIGNED, BIG_ENDIAN);

  private static final Equivalence<Object> FAILURE_IDENTITY = Equivalence.identity();
  private static final int BUFFER_MILLISECONDS = 200;
  private static final int MILLISECONDS_PER_SECOND = 1000;

  private final LineOpener lineOpener;
  private volatile @Nullable SourceDataLine line;
  private byte @Nullable [] chunk;

  /**
   * Creates a new direct audio output. Nothing is opened until {@link #start()} is called.
   *
   * @since 1.0.0
   */
  public DirectAudioOutput() {
    this(DirectAudioOutput::openDefaultLine);
  }

  @VisibleForTesting
  DirectAudioOutput(final LineOpener lineOpener) {
    Preconditions.checkNotNull(lineOpener, "Line opener must not be null!");

    this.lineOpener = lineOpener;
  }

  private static SourceDataLine openDefaultLine(final AudioFormat format, final int bufferSize) throws LineUnavailableException {
    final DataLine.Info lineInformation = new DataLine.Info(SourceDataLine.class, format);
    final SourceDataLine opened = (SourceDataLine) AudioSystem.getLine(lineInformation);
    opened.open(format, bufferSize);
    return opened;
  }

  /**
   * Writes the samples to the sound device, if it is open. The samples are only read.
   *
   * @param samples  the samples from the position to the limit of the buffer, which is not consumed
   * @param metadata the metadata of the original stream
   * @return false, because the samples are never changed
   * @throws IllegalArgumentException if the sound line rejects a byte count that is not a whole PCM frame
   * @throws NullPointerException     if {@code samples} is null
   * @since 1.0.0
   */
  @Override
  public boolean applyFilter(final ByteBuffer samples, final OriginalAudioMetadata metadata) {
    Preconditions.checkNotNull(samples, "Samples must not be null!");

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
   * Opens the default sound device. A line that opens but fails to start is closed, and a failure to close it is
   * suppressed on the start failure. Fatal VM errors propagate immediately.
   *
   * @throws PlayerException  if no sound device supports the audio format of the library, for example on a headless
   *                          machine
   * @throws RuntimeException if the opened line fails to start
   * @throws Error            if the opened line reports an error while it starts
   * @since 1.0.0
   */
  @Override
  public synchronized void start() {
    if (this.line != null) {
      return;
    }

    try {
      final int bufferSize = (SAMPLE_RATE * FRAME_SIZE * BUFFER_MILLISECONDS) / MILLISECONDS_PER_SECOND;
      final SourceDataLine opened = this.lineOpener.open(FORMAT, bufferSize);
      this.startOrClose(opened);
      this.line = opened;
    } catch (final LineUnavailableException | IllegalArgumentException exception) {
      final String reason = exception.getMessage();
      final String message = "Default audio device could not be opened because of \"%s\"!".formatted(reason);
      throw new PlayerException(message, exception);
    }
  }

  private void startOrClose(final SourceDataLine opened) {
    try {
      opened.start();
    } catch (final RuntimeException | Error exception) {
      ThrowableUtils.throwIfFatal(exception);
      this.closeAfterFailedStart(opened, exception);
      throw exception;
    }
  }

  private void closeAfterFailedStart(final SourceDataLine opened, final Throwable failure) {
    try {
      opened.close();
    } catch (final RuntimeException | Error exception) {
      suppressCleanupFailure(failure, exception);
    }
  }

  /**
   * Closes the sound device, discarding samples that have not been played yet. Ordinary stop or flush failures do
   * not prevent closing the line; the first failure is rethrown with later failures suppressed. Fatal VM errors
   * propagate immediately.
   * @throws RuntimeException if stopping, flushing or closing the line fails
   * @throws Error            if the sound line reports an error during cleanup
   * @since 1.0.0
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
      failure = this.runCleanupAction(action, failure);
    }

    if (failure instanceof final RuntimeException runtimeException) {
      throw runtimeException;
    }

    if (failure instanceof final Error error) {
      throw error;
    }
  }

  private @Nullable Throwable runCleanupAction(final Runnable action, final @Nullable Throwable failure) {
    try {
      action.run();
      return failure;
    } catch (final RuntimeException | Error exception) {
      ThrowableUtils.throwIfFatal(exception);

      if (failure == null) {
        return exception;
      }

      suppressCleanupFailure(failure, exception);

      return failure;
    }
  }

  private static void suppressCleanupFailure(final Throwable failure, final Throwable cleanup) {
    ThrowableUtils.throwIfFatal(cleanup);
    final boolean same = FAILURE_IDENTITY.equivalent(failure, cleanup);
    if (!same) {
      failure.addSuppressed(cleanup);
    }
  }

  @FunctionalInterface
  interface LineOpener {
    SourceDataLine open(final AudioFormat format, final int bufferSize) throws LineUnavailableException;
  }
}
