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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
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
import org.mockito.InOrder;

/**
 * Tests {@link DirectAudioOutput} with a mocked audio line, and {@link AudioFilter#NO_OP}.
 */
final class DirectAudioOutputTest {

  private static final OriginalAudioMetadata METADATA = OriginalAudioMetadata.of("pcm", -1, 48_000, 2, -1);

  @Test
  void writesNothingBeforeItIsStarted() {
    final SourceDataLine line = mock(SourceDataLine.class);
    final DirectAudioOutput output = new DirectAudioOutput((_, _) -> line);
    final ByteBuffer samples = ByteBuffer.allocate(4);
    final boolean result = output.applyFilter(samples, METADATA);
    assertFalse(result, "the samples are only read");
    verifyNoInteractions(line);
  }

  @Test
  void opensTheLineOnceAndWritesTheRemainingSamples() {
    final SourceDataLine line = mock(SourceDataLine.class);
    final AtomicInteger opened = new AtomicInteger();
    final DirectAudioOutput output = new DirectAudioOutput((format, bufferSize) -> {
      final float sampleRate = format.getSampleRate();
      final int channels = format.getChannels();
      final AudioFormat.Encoding encoding = format.getEncoding();
      assertEquals(48_000.0f, sampleRate);
      assertEquals(2, channels);
      assertEquals(AudioFormat.Encoding.PCM_SIGNED, encoding);
      assertEquals((48_000 * 4) / 5, bufferSize);
      opened.incrementAndGet();
      return line;
    });
    output.start();
    output.start();
    final ByteBuffer samples = ByteBuffer.wrap(new byte[] { 9, 1, 2, 3, 4 });
    samples.position(1);
    final boolean result = output.applyFilter(samples, METADATA);
    final int position = samples.position();
    final int openCount = opened.get();
    assertFalse(result, "the samples are only read");
    assertEquals(1, openCount);
    assertEquals(1, position, "the samples must not be consumed");
    verify(line).start();
    verify(line).write(new byte[] { 1, 2, 3, 4 }, 0, 4);
  }

  @Test
  void releasesTheLineOnce() {
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
  void reportsLinesThatCannotBeOpened() {
    final DirectAudioOutput unavailable = new DirectAudioOutput((_, _) -> {
      throw new LineUnavailableException("busy");
    });
    final DirectAudioOutput unsupported = new DirectAudioOutput((_, _) -> {
      throw new IllegalArgumentException("no line matches");
    });
    final PlayerException unavailableException = assertThrows(PlayerException.class, unavailable::start);
    final PlayerException unsupportedException = assertThrows(PlayerException.class, unsupported::start);
    final Throwable unavailableCause = unavailableException.getCause();
    final Throwable unsupportedCause = unsupportedException.getCause();
    assertInstanceOf(LineUnavailableException.class, unavailableCause);
    assertInstanceOf(IllegalArgumentException.class, unsupportedCause);
  }

  @Test
  void usesTheDefaultAudioDeviceWhenOneExists() {
    final DataLine.Info info = new DataLine.Info(SourceDataLine.class, DirectAudioOutput.FORMAT);
    final boolean device = AudioSystem.isLineSupported(info);
    Assumptions.assumeTrue(device, "this machine has no sound device, see reportsLinesThatCannotBeOpened for that case");
    final DirectAudioOutput output = new DirectAudioOutput();
    try {
      output.start();
      final ByteBuffer silence = ByteBuffer.allocate(AudioFilter.FRAME_SIZE * 16);
      final boolean result = output.applyFilter(silence, METADATA);
      assertFalse(result);
    } finally {
      output.release();
    }
  }

  @Test
  void rejectsMissingArguments() {
    final DirectAudioOutput output = new DirectAudioOutput((_, _) -> mock(SourceDataLine.class));
    assertThrows(NullPointerException.class, () -> output.applyFilter(null, METADATA));
    assertThrows(NullPointerException.class, () -> new DirectAudioOutput(null));
  }

  @Test
  void reusesOneArrayForEveryChunk() {
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
  void noOpFilterLeavesTheSamplesUntouched() {
    final ByteBuffer samples = ByteBuffer.allocate(4);
    final boolean result = AudioFilter.NO_OP.applyFilter(samples, METADATA);
    assertFalse(result);
  }
}
