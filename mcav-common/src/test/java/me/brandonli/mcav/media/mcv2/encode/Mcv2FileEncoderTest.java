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
package me.brandonli.mcav.media.mcv2.encode;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;
import me.brandonli.mcav.media.mcv2.FrameParser;
import me.brandonli.mcav.testing.TestMedia;
import me.brandonli.mcav.testing.UtilityClassAssertions;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pre-encoding: a video file becomes the same stream a frame-by-frame encode gives, inside the budget, and every way
 * the video can fail to open, decode or close is reported.
 */
final class Mcv2FileEncoderTest {

  private static final int WIDTH = 64;
  private static final int HEIGHT = 48;

  @TempDir
  private Path directory;

  /** Splits a stream into its frames. */
  private static List<byte[]> frames(final byte[] stream) {
    final List<byte[]> frames = new ArrayList<>();
    final ByteBuffer buffer = ByteBuffer.wrap(stream).order(ByteOrder.LITTLE_ENDIAN);
    while (buffer.hasRemaining()) {
      final byte[] frame = new byte[buffer.getInt()];
      buffer.get(frame);
      frames.add(frame);
    }
    return frames;
  }

  @Test
  void encodesAVideoFileLikeAFrameByFrameEncode() throws Exception {
    final Path video = TestMedia.video();
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    final List<Long> progress = new ArrayList<>();
    final Mcv2FileEncoder.Result result;
    try (EncoderPool budget = new EncoderPool(2)) {
      result = Mcv2FileEncoder.encode(
        Mcv2FileEncoder.ffmpeg(video, WIDTH, HEIGHT),
        WIDTH,
        HEIGHT,
        EncoderSettings.LIVE,
        budget,
        out,
        progress::add
      );
    }
    final int count = TestMedia.VIDEO_SECONDS * TestMedia.VIDEO_FRAME_RATE;
    assertEquals(count, result.frames());
    assertEquals(count, progress.size());
    assertEquals(count, (long) progress.getLast());
    assertTrue(result.keyframes() >= 2);
    assertTrue(result.nanoseconds() > 0);
    assertTrue(result.millisecondsPerFrame() > 0);
    final List<byte[]> frames = frames(out.toByteArray());
    assertEquals(count, frames.size());
    assertEquals(result.bytes(), frames.stream().mapToLong(frame -> frame.length).sum());
    // the same frames, encoded one by one outside any budget, give the same bytes
    final Mcv2Encoder alone = new Mcv2Encoder(EncoderSettings.LIVE, ForkJoinPool.commonPool(), 1, false);
    try (Mcv2FileEncoder.FrameReader reader = Mcv2FileEncoder.ffmpeg(video, WIDTH, HEIGHT)) {
      final byte[] rgb = new byte[WIDTH * HEIGHT * 3];
      for (int i = 0; i < count; i++) {
        assertTrue(reader.read(rgb));
        assertArrayEquals(alone.encode(rgb, WIDTH, HEIGHT, i), frames.get(i), "frame " + i);
        assertEquals(i, FrameParser.parse(frames.get(i)).getFrameId());
      }
      assertFalse(reader.read(rgb));
    }
  }

  @Test
  void stopsWhenInterruptedAndClosesTheVideo() {
    final AtomicBoolean closed = new AtomicBoolean();
    final Mcv2FileEncoder.FrameReader interrupting = new Mcv2FileEncoder.FrameReader() {
      @Override
      public boolean read(final byte[] rgb) {
        Thread.currentThread().interrupt();
        return true;
      }

      @Override
      public void close() {
        closed.set(true);
      }
    };
    try (EncoderPool budget = new EncoderPool(1)) {
      assertThrows(InterruptedException.class, () ->
        Mcv2FileEncoder.encode(interrupting, WIDTH, HEIGHT, EncoderSettings.LIVE, budget, new ByteArrayOutputStream(), _ -> {})
      );
    }
    assertFalse(Thread.interrupted());
    assertTrue(closed.get());
    assertEquals(0, new Mcv2FileEncoder.Result(0, 0, 0, 0).millisecondsPerFrame());
    assertEquals(2.5, new Mcv2FileEncoder.Result(2, 0, 0, 5_000_000).millisecondsPerFrame());
  }

  @Test
  void refusesWhatItCannotEncode() throws IOException {
    final Mcv2FileEncoder.FrameReader none = mock(Mcv2FileEncoder.FrameReader.class);
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (EncoderPool budget = new EncoderPool(1)) {
      assertThrows(NullPointerException.class, () -> Mcv2FileEncoder.encode(null, 1, 1, EncoderSettings.LIVE, budget, out, _ -> {}));
      assertThrows(IllegalArgumentException.class, () -> Mcv2FileEncoder.encode(none, 0, 1, EncoderSettings.LIVE, budget, out, _ -> {}));
      assertThrows(IllegalArgumentException.class, () -> Mcv2FileEncoder.encode(none, 1, 0, EncoderSettings.LIVE, budget, out, _ -> {}));
      assertThrows(NullPointerException.class, () -> Mcv2FileEncoder.encode(none, 1, 1, null, budget, out, _ -> {}));
      assertThrows(NullPointerException.class, () -> Mcv2FileEncoder.encode(none, 1, 1, EncoderSettings.LIVE, null, out, _ -> {}));
      assertThrows(NullPointerException.class, () -> Mcv2FileEncoder.encode(none, 1, 1, EncoderSettings.LIVE, budget, null, _ -> {}));
      assertThrows(NullPointerException.class, () -> Mcv2FileEncoder.encode(none, 1, 1, EncoderSettings.LIVE, budget, out, null));
    }
    assertThrows(NullPointerException.class, () -> Mcv2FileEncoder.ffmpeg(null, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> Mcv2FileEncoder.ffmpeg(this.directory, 0, 1));
    assertThrows(IllegalArgumentException.class, () -> Mcv2FileEncoder.ffmpeg(this.directory, 1, 0));
    final IOException missing = assertThrows(IOException.class, () -> Mcv2FileEncoder.ffmpeg(this.directory.resolve("missing.mp4"), 8, 8));
    assertTrue(missing.getMessage().startsWith("Cannot open "));
  }

  @Test
  void closesAVideoThatCannotStart() throws Exception {
    final FFmpegFrameGrabber grabber = mock(FFmpegFrameGrabber.class);
    doThrow(new FFmpegFrameGrabber.Exception("no stream")).when(grabber).start();
    doThrow(new FFmpegFrameGrabber.Exception("not open")).when(grabber).close();
    final IOException thrown = assertThrows(IOException.class, () -> Mcv2FileEncoder.open(grabber, "clip.mp4", 8, 8));
    assertTrue(thrown.getMessage().startsWith("Cannot open clip.mp4: no stream"), thrown.getMessage());
    verify(grabber).close();
  }

  @Test
  void reportsFramesItCannotDecodeAndAVideoItCannotClose() throws Exception {
    final FFmpegFrameGrabber grabber = mock(FFmpegFrameGrabber.class);
    when(grabber.grabImage()).thenThrow(new FFmpegFrameGrabber.Exception("broken"));
    doThrow(new FFmpegFrameGrabber.Exception("stuck")).when(grabber).close();
    final Mcv2FileEncoder.GrabberReader reader = new Mcv2FileEncoder.GrabberReader(grabber, 8, 8);
    assertTrue(
      assertThrows(IOException.class, () -> reader.read(new byte[192])).getMessage().startsWith("Cannot decode the video: broken")
    );
    assertTrue(assertThrows(IOException.class, reader::close).getMessage().startsWith("Cannot close the video: stuck"));
  }

  @Test
  void cannotBeInstantiated() throws ReflectiveOperationException {
    UtilityClassAssertions.assertNotInstantiable(Mcv2FileEncoder.class);
  }

  @Test
  void packsPaddedRows() {
    final Frame frame = new Frame(2, 2, Frame.DEPTH_UBYTE, 3, 8);
    final ByteBuffer pixels = (ByteBuffer) frame.image[0];
    for (int i = 0; i < 16; i++) {
      pixels.put(i, (byte) i);
    }
    final byte[] rgb = new byte[12];
    Mcv2FileEncoder.copy(frame, rgb, 2, 2);
    assertArrayEquals(new byte[] { 0, 1, 2, 3, 4, 5, 8, 9, 10, 11, 12, 13 }, rgb);
    assertThrows(IllegalStateException.class, () -> Mcv2FileEncoder.copy(frame, rgb, 3, 2));
    assertThrows(IllegalStateException.class, () -> Mcv2FileEncoder.copy(frame, rgb, 2, 3));
  }
}
