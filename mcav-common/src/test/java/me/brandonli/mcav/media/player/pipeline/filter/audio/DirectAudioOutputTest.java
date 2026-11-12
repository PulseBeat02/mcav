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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import me.brandonli.mcav.media.player.PlayerException;
import me.brandonli.mcav.media.player.metadata.OriginalAudioMetadata;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.InOrder;
import org.mockito.MockedStatic;

final class DirectAudioOutputTest {

  private static final OriginalAudioMetadata METADATA = OriginalAudioMetadata.of("pcm", -1, 48_000, 2, -1);

  private static final String START_FAILURE_MESSAGE = "Start failed!";
  private static final String STOP_FAILURE_MESSAGE = "Stop failed!";
  private static final String CLOSE_FAILURE_MESSAGE = "Close failed!";

  private static final String READ_ONLY_MESSAGE = "the samples are only read";

  @Test
  void shouldWriteNothingBeforeItIsStarted() {
    final SourceDataLine line = mock(SourceDataLine.class);
    final DirectAudioOutput output = new DirectAudioOutput((_, _) -> line);
    final ByteBuffer samples = ByteBuffer.allocate(4);
    final boolean changed = output.applyFilter(samples, METADATA);
    assertFalse(changed, READ_ONLY_MESSAGE);

    verifyNoInteractions(line);
  }

  @Test
  void shouldOpenTheLineOnceAndWriteTheRemainingSamples() {
    final SourceDataLine line = mock(SourceDataLine.class);
    final AtomicInteger opened = new AtomicInteger();
    final DirectAudioOutput output = new DirectAudioOutput((format, bufferSize) -> this.checkAndOpenLine(format, bufferSize, opened, line));
    output.start();
    output.start();
    final ByteBuffer samples = ByteBuffer.wrap(new byte[] { 9, 1, 2, 3, 4 });
    samples.position(1);
    final boolean changed = output.applyFilter(samples, METADATA);
    final int position = samples.position();
    final int openCount = opened.get();
    assertFalse(changed, READ_ONLY_MESSAGE);
    assertEquals(1, openCount);
    assertEquals(1, position, "the samples must not be consumed");
    verify(line).start();
    verify(line).write(new byte[] { 1, 2, 3, 4 }, 0, 4);
  }

  private SourceDataLine checkAndOpenLine(
    final AudioFormat format,
    final int bufferSize,
    final AtomicInteger opened,
    final SourceDataLine line
  ) {
    final float sampleRate = format.getSampleRate();
    final int channels = format.getChannels();
    final AudioFormat.Encoding encoding = format.getEncoding();
    final int expected = (48_000 * 4) / 5;
    assertEquals(48_000.0f, sampleRate);
    assertEquals(2, channels);
    assertEquals(AudioFormat.Encoding.PCM_SIGNED, encoding);
    assertEquals(expected, bufferSize);

    opened.incrementAndGet();
    return line;
  }

  @Test
  void shouldReleaseTheLineOnce() {
    final SourceDataLine line = mock(SourceDataLine.class);
    final DirectAudioOutput output = new DirectAudioOutput((_, _) -> line);
    output.release();
    output.start();
    output.release();
    output.release();
    final InOrder order = inOrder(line);
    order.verify(line).stop();
    order.verify(line).flush();
    order.verify(line).close();
  }

  @Test
  void shouldCloseAnAcquiredLineWhenStartingItFails() {
    final SourceDataLine line = mock(SourceDataLine.class);
    final IllegalStateException failure = new IllegalStateException(START_FAILURE_MESSAGE);
    doThrow(failure).when(line).start();
    final DirectAudioOutput output = new DirectAudioOutput((_, _) -> line);
    final IllegalStateException thrown = assertThrows(IllegalStateException.class, output::start);
    assertSame(failure, thrown);
    verify(line).close();

    output.release();
    verify(line).close();
  }

  @Test
  void shouldPreserveStartFailureWhenClosingTheLineAlsoFails() {
    final SourceDataLine line = mock(SourceDataLine.class);
    final IllegalArgumentException failure = new IllegalArgumentException(START_FAILURE_MESSAGE);
    final AssertionError cleanup = new AssertionError(CLOSE_FAILURE_MESSAGE);
    doThrow(failure).when(line).start();
    doThrow(cleanup).when(line).close();
    final DirectAudioOutput output = new DirectAudioOutput((_, _) -> line);
    final PlayerException thrown = assertThrows(PlayerException.class, output::start);
    final Throwable cause = thrown.getCause();
    final Throwable[] suppressed = failure.getSuppressed();
    final Throwable[] expected = new Throwable[] { cleanup };
    assertSame(failure, cause);
    assertArrayEquals(expected, suppressed);
    verify(line).close();
  }

  @Test
  void shouldAttemptEveryReleaseStageAndPreserveTheFirstFailure() {
    final SourceDataLine line = mock(SourceDataLine.class);
    final IllegalStateException failure = new IllegalStateException(STOP_FAILURE_MESSAGE);
    final AssertionError flush = new AssertionError("Flush failed!");
    final IllegalArgumentException close = new IllegalArgumentException(CLOSE_FAILURE_MESSAGE);
    doThrow(failure).when(line).stop();
    doThrow(flush).when(line).flush();
    doThrow(close).when(line).close();
    final DirectAudioOutput output = new DirectAudioOutput((_, _) -> line);
    output.start();
    final IllegalStateException thrown = assertThrows(IllegalStateException.class, output::release);
    final Throwable[] suppressed = thrown.getSuppressed();
    final Throwable[] expected = new Throwable[] { flush, close };
    assertSame(failure, thrown);
    assertArrayEquals(expected, suppressed);

    final InOrder order = inOrder(line);
    order.verify(line).stop();
    order.verify(line).flush();
    order.verify(line).close();
    output.release();
    verify(line).close();
  }

  @Test
  void shouldKeepAReleaseFailureWhenTheLaterStagesSucceed() {
    final SourceDataLine line = mock(SourceDataLine.class);
    final IllegalStateException failure = new IllegalStateException(STOP_FAILURE_MESSAGE);
    doThrow(failure).when(line).stop();
    final DirectAudioOutput output = new DirectAudioOutput((_, _) -> line);
    output.start();
    final IllegalStateException thrown = assertThrows(IllegalStateException.class, output::release);
    assertSame(failure, thrown);

    verify(line).flush();
    verify(line).close();
  }

  @Test
  void shouldRethrowAnOrdinaryErrorAfterClosingTheLineWithoutSelfSuppression() {
    final SourceDataLine line = mock(SourceDataLine.class);
    final AssertionError failure = new AssertionError("Shared failure!");
    doThrow(failure).when(line).flush();
    doThrow(failure).when(line).close();
    final DirectAudioOutput output = new DirectAudioOutput((_, _) -> line);
    output.start();
    final AssertionError thrown = assertThrows(AssertionError.class, output::release);
    final Throwable[] suppressed = thrown.getSuppressed();
    assertSame(failure, thrown);
    assertEquals(0, suppressed.length);
    verify(line).close();
  }

  @Test
  void shouldPropagateFatalStartFailuresImmediately() {
    final SourceDataLine line = mock(SourceDataLine.class);
    final InternalError failure = new InternalError("Fatal start!");
    doThrow(failure).when(line).start();
    final DirectAudioOutput output = new DirectAudioOutput((_, _) -> line);
    final InternalError thrown = assertThrows(InternalError.class, output::start);
    assertSame(failure, thrown);
    verify(line, never()).close();
  }

  @Test
  void shouldPropagateFatalCleanupAfterAStartFailure() {
    final SourceDataLine line = mock(SourceDataLine.class);
    final IllegalStateException failure = new IllegalStateException(START_FAILURE_MESSAGE);
    final InternalError fatal = new InternalError("Fatal close!");
    doThrow(failure).when(line).start();
    doThrow(fatal).when(line).close();
    final DirectAudioOutput output = new DirectAudioOutput((_, _) -> line);
    final InternalError thrown = assertThrows(InternalError.class, output::start);
    assertSame(fatal, thrown);
  }

  @Test
  void shouldPropagateFatalReleaseFailuresImmediately() {
    final SourceDataLine line = mock(SourceDataLine.class);
    final InternalError fatal = new InternalError("Fatal stop!");
    doThrow(fatal).when(line).stop();
    final DirectAudioOutput output = new DirectAudioOutput((_, _) -> line);
    output.start();
    final InternalError thrown = assertThrows(InternalError.class, output::release);
    assertSame(fatal, thrown);
    verify(line, never()).flush();
    verify(line, never()).close();
  }

  @Test
  void shouldReportLinesThatCannotBeOpened() {
    final DirectAudioOutput unavailable = new DirectAudioOutput((_, _) -> this.failLineOpen(new LineUnavailableException("Busy!")));
    final DirectAudioOutput unsupported = new DirectAudioOutput((_, _) ->
      this.failLineOpen(new IllegalArgumentException("No line matches!"))
    );
    final PlayerException unavailableException = assertThrows(PlayerException.class, unavailable::start);
    final PlayerException unsupportedException = assertThrows(PlayerException.class, unsupported::start);
    final Throwable unavailableCause = unavailableException.getCause();
    final Throwable unsupportedCause = unsupportedException.getCause();
    assertInstanceOf(LineUnavailableException.class, unavailableCause);
    assertInstanceOf(IllegalArgumentException.class, unsupportedCause);
  }

  private <FailureType extends Throwable> SourceDataLine failLineOpen(final FailureType failure) throws FailureType {
    throw failure;
  }

  @Test
  void shouldOpenTheDefaultLineBeforeStartingIt() throws Exception {
    final SourceDataLine line = mock(SourceDataLine.class);
    try (final MockedStatic<AudioSystem> system = mockStatic(AudioSystem.class)) {
      system.when(() -> AudioSystem.getLine(ArgumentMatchers.any(DataLine.Info.class))).thenReturn(line);
      final DirectAudioOutput output = new DirectAudioOutput();
      try {
        output.start();
        final InOrder order = inOrder(line);
        order.verify(line).open(DirectAudioOutput.FORMAT, 38_400);
        order.verify(line).start();
      } finally {
        output.release();
      }
    }
  }

  @Test
  void shouldUseTheDefaultAudioDeviceWhenOneExists() {
    final DataLine.Info info = new DataLine.Info(SourceDataLine.class, DirectAudioOutput.FORMAT);
    final boolean hasDevice = AudioSystem.isLineSupported(info);
    Assumptions.assumeTrue(hasDevice, "This machine has no sound device, see shouldReportLinesThatCannotBeOpened for that case!");
    final DirectAudioOutput output = new DirectAudioOutput();
    try {
      output.start();
      final ByteBuffer silence = ByteBuffer.allocate(AudioFilter.FRAME_SIZE * 16);
      final boolean changed = output.applyFilter(silence, METADATA);
      assertFalse(changed);
    } finally {
      output.release();
    }
  }

  @Test
  void shouldRejectMissingArguments() {
    final DirectAudioOutput output = new DirectAudioOutput((_, _) -> mock(SourceDataLine.class));
    assertThrows(NullPointerException.class, () -> output.applyFilter(null, METADATA));
    assertThrows(NullPointerException.class, () -> new DirectAudioOutput(null));
  }

  @Test
  void shouldPassOnTheRejectionOfAChunkThatIsNotAWholeFrame() {
    final SourceDataLine line = mock(SourceDataLine.class);
    final IllegalArgumentException rejection = new IllegalArgumentException("Not a whole frame!");
    doThrow(rejection).when(line).write(ArgumentMatchers.any(byte[].class), eq(0), eq(3));
    final DirectAudioOutput output = new DirectAudioOutput((_, _) -> line);
    output.start();
    final ByteBuffer samples = ByteBuffer.allocate(3);
    final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> output.applyFilter(samples, METADATA));
    assertSame(rejection, thrown);
  }

  @Test
  void shouldReuseOneArrayForEveryChunk() {
    final SourceDataLine line = mock(SourceDataLine.class);
    final DirectAudioOutput output = new DirectAudioOutput((_, _) -> line);
    output.start();
    final ByteBuffer first = ByteBuffer.allocate(8);
    final ByteBuffer second = ByteBuffer.allocate(8);
    output.applyFilter(first, METADATA);
    output.applyFilter(second, METADATA);
    final ArgumentCaptor<byte[]> written = ArgumentCaptor.forClass(byte[].class);
    verify(line, times(2)).write(written.capture(), eq(0), eq(8));

    final List<byte[]> arrays = written.getAllValues();
    final byte[] firstArray = arrays.getFirst();
    final byte[] secondArray = arrays.get(1);
    final byte[] smallChunk = output.getChunkArray(4);
    final byte[] largeChunk = output.getChunkArray(16);
    assertSame(firstArray, secondArray, "no array is allocated per chunk");
    assertSame(firstArray, smallChunk, "smaller chunks fit into the array");
    assertEquals(16, largeChunk.length, "the array grows for larger chunks");

    output.release();
  }

  @Test
  void shouldLeaveTheSamplesUntouchedWithANoOperationFilter() {
    final byte[] original = { 4, 7, 9, 12 };
    final ByteBuffer samples = ByteBuffer.wrap(original.clone());
    samples.position(1).limit(3).mark();
    final boolean changed = AudioFilter.NO_OPERATION.applyFilter(samples, METADATA);
    assertFalse(changed);

    final byte[] sampleBytes = samples.array();
    assertArrayEquals(original, sampleBytes);

    final int position = samples.position();
    assertEquals(1, position);

    final int limit = samples.limit();
    assertEquals(3, limit);

    final int markedPosition = samples.reset().position();
    assertEquals(1, markedPosition);
  }
}
