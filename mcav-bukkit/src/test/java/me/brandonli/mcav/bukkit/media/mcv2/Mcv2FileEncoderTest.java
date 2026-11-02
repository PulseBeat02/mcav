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
package me.brandonli.mcav.bukkit.media.mcv2;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;
import me.brandonli.mcav.bukkit.media.map.MapLayout;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2.Pool;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2.Settings;
import me.brandonli.mcav.bukkit.media.mcv2.transport.TransportPages;
import me.brandonli.mcav.bukkit.testing.EqualityAssertions;
import me.brandonli.mcav.bukkit.testing.TestMedia;
import me.brandonli.mcav.bukkit.testing.UtilityClassAssertions;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.ffmpeg.global.swscale;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class Mcv2FileEncoderTest {

  private static final int WIDTH = 64;

  private static final int HEIGHT = 48;

  @TempDir
  private Path directory;

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
  void shouldEncodeAVideoFileLikeAFrameByFrameEncode() throws Exception {
    final Path video = TestMedia.video();
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    final List<Long> progress = new ArrayList<>();
    final Mcv2FileEncoder.Result result;
    try (final Pool budget = new Pool(2)) {
      result = Mcv2FileEncoder.encode(
        Mcv2FileEncoder.ffmpeg(video, WIDTH, HEIGHT),
        WIDTH,
        HEIGHT,
        Settings.DEFAULT,
        budget,
        out,
        progress::add
      );
    }

    final int count = TestMedia.VIDEO_SECONDS * TestMedia.VIDEO_FRAME_RATE;
    final long frameCount = result.getFrames();
    assertEquals(count, frameCount);

    final int progressCount = progress.size();
    assertEquals(count, progressCount);

    final long lastProgress = progress.getLast();
    assertEquals(count, lastProgress);

    final boolean hasSeveralKeyframes = result.getKeyframes() >= 2;
    assertTrue(hasSeveralKeyframes);

    final boolean tookTime = result.getNanoseconds() > 0;
    assertTrue(tookTime);

    final boolean tookTimePerFrame = result.millisecondsPerFrame() > 0;
    assertTrue(tookTimePerFrame);

    final List<byte[]> frames = frames(out.toByteArray());
    final int writtenFrameCount = frames.size();
    assertEquals(count, writtenFrameCount);

    final long expectedBytes = result.getBytes();
    final long writtenBytes = frames
      .stream()
      .mapToLong(frame -> frame.length)
      .sum();
    assertEquals(expectedBytes, writtenBytes);

    final Mcv2 alone = new Mcv2(Settings.DEFAULT, ForkJoinPool.commonPool(), 1, Mcv2.Verification.UNVERIFIED);
    alone.setFrameLimit(TransportPages.capacity());
    try (final Mcv2FileEncoder.FrameReader reader = Mcv2FileEncoder.ffmpeg(video, WIDTH, HEIGHT)) {
      final byte[] rgb = new byte[WIDTH * HEIGHT * 3];
      for (int frameNumber = 0; frameNumber < count; frameNumber++) {
        final boolean readPicture = reader.read(rgb);
        assertTrue(readPicture);
        final byte[] expectedFrame = alone.encode(rgb, WIDTH, HEIGHT, frameNumber);
        final byte[] writtenFrame = frames.get(frameNumber);
        assertArrayEquals(expectedFrame, writtenFrame, "frame " + frameNumber);
        final long frameId = Mcv2Decoder.parse(frames.get(frameNumber)).getFrameId();
        assertEquals(frameNumber, frameId);
      }

      final boolean readPastTheEnd = reader.read(rgb);
      assertFalse(readPastTheEnd);
    }
  }

  @Test
  void shouldKeepEveryFrameSmallEnoughForTheDefaultPageSlotsOfItsWall() throws Exception {
    final int width = 8 * MapLayout.MAP_SIZE;
    final int height = 4 * MapLayout.MAP_SIZE;
    final int limit = Mcv2Configuration.MAXIMUM_PAGE_SLOTS * TransportPages.capacity();
    final byte[] checkerboard = this.checkerboard(width, height);
    final Mcv2 unlimited = new Mcv2(Settings.DEFAULT, ForkJoinPool.commonPool(), 1, Mcv2.Verification.UNVERIFIED);
    final boolean unlimitedFrameTooLarge = unlimited.encode(checkerboard, width, height, 0).length > limit;
    assertTrue(unlimitedFrameTooLarge);

    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (final Pool budget = new Pool(1)) {
      Mcv2FileEncoder.encode(this.repeating(checkerboard, 2), width, height, Settings.DEFAULT, budget, out, count -> {});
    }

    final List<byte[]> frames = frames(out.toByteArray());
    final int frameCount = frames.size();
    assertEquals(2, frameCount);

    for (final byte[] frame : frames) {
      final boolean withinLimit = frame.length <= limit;
      assertTrue(withinLimit, "a frame of " + frame.length + " bytes");
    }

    final int expectedLength = checkerboard.length;
    final int decodedLength = Mcv2Decoder.decode(frames.getFirst(), null, 0).length;
    assertEquals(expectedLength, decodedLength);
  }

  @Test
  void shouldReadThePicturesOfAVideo() throws Exception {
    final byte[] rgb = new byte[WIDTH * HEIGHT * Mcv2Decoder.CHANNELS];
    try (final Mcv2FileEncoder.FrameReader reader = Mcv2FileEncoder.ffmpeg(TestMedia.video(), WIDTH, HEIGHT)) {
      final boolean readPicture = reader.read(rgb);
      assertTrue(readPicture);
    }

    final boolean allBlack = Arrays.equals(new byte[rgb.length], rgb);
    assertFalse(allBlack, "the first picture of the test pattern is not all black");
  }

  @Test
  void shouldAskForRgbPicturesScaledByAreaAndDecodedOnEveryCore() throws Exception {
    final Path video = TestMedia.video();
    final FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(video.toFile());
    try (final Mcv2FileEncoder.FrameReader _ = Mcv2FileEncoder.open(grabber, "clip.mp4", WIDTH, HEIGHT)) {
      final int pixelFormat = grabber.getPixelFormat();
      final int scalingFlags = grabber.getImageScalingFlags();
      final String threads = grabber.getVideoOption("threads");
      assertEquals(avutil.AV_PIX_FMT_RGB24, pixelFormat);
      assertEquals(swscale.SWS_AREA, scalingFlags);
      assertEquals("auto", threads);
    }
  }

  @Test
  void shouldCountTheKeyframesOfTheStreamItWrites() throws Exception {
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    final Mcv2FileEncoder.Result result;
    try (final Pool budget = new Pool(1)) {
      result = Mcv2FileEncoder.encode(
        this.repeating(this.checkerboard(WIDTH, HEIGHT), 3),
        WIDTH,
        HEIGHT,
        Settings.DEFAULT,
        budget,
        out,
        _ -> {}
      );
    }

    long keyframes = 0;
    for (final byte[] frame : frames(out.toByteArray())) {
      if (Mcv2Decoder.parse(frame).isKeyframe()) {
        keyframes++;
      }
    }

    final long frameCount = result.getFrames();
    assertEquals(3, frameCount);

    final long countedKeyframes = result.getKeyframes();
    assertEquals(keyframes, countedKeyframes, "the keyframes in the stream of three frames");
  }

  @Test
  void shouldMeasureItsEncodesWithinTheTimeOfTheCall() throws Exception {
    final Mcv2FileEncoder.Result result;
    final long started = System.nanoTime();
    try (final Pool budget = new Pool(1)) {
      result = Mcv2FileEncoder.encode(
        this.repeating(this.checkerboard(WIDTH, HEIGHT), 3),
        WIDTH,
        HEIGHT,
        Settings.DEFAULT,
        budget,
        new ByteArrayOutputStream(),
        _ -> {}
      );
    }

    final long elapsed = System.nanoTime() - started;
    final boolean withinTheCall = result.getNanoseconds() >= 0 && result.getNanoseconds() <= elapsed;
    assertTrue(withinTheCall, result.getNanoseconds() + " ns in a call of " + elapsed + " ns");
  }

  @Test
  void shouldLimitFramesToTheDefaultPageSlotsOfAWallOfTheVideosNativeSize() {
    final int capacity = TransportPages.capacity();
    final int onePixelLimit = Mcv2FileEncoder.screenFrameLimit(1, 1);
    assertEquals(capacity, onePixelLimit);

    final int oneMapLimit = Mcv2FileEncoder.screenFrameLimit(MapLayout.MAP_SIZE, MapLayout.MAP_SIZE);
    assertEquals(capacity, oneMapLimit);

    final int expectedTwoMapLimit = 2 * capacity;
    final int widerThanOneMapLimit = Mcv2FileEncoder.screenFrameLimit(MapLayout.MAP_SIZE + 1, MapLayout.MAP_SIZE);
    assertEquals(expectedTwoMapLimit, widerThanOneMapLimit);

    final int expectedTwoMapLimitForHeight = 2 * capacity;
    final int tallerThanOneMapLimit = Mcv2FileEncoder.screenFrameLimit(MapLayout.MAP_SIZE, MapLayout.MAP_SIZE + 1);
    assertEquals(expectedTwoMapLimitForHeight, tallerThanOneMapLimit);

    final int expectedSevenMapLimit = 7 * capacity;
    final int sevenMapLimit = Mcv2FileEncoder.screenFrameLimit(7 * MapLayout.MAP_SIZE, MapLayout.MAP_SIZE);
    assertEquals(expectedSevenMapLimit, sevenMapLimit);

    final int expectedFullHdLimit = Mcv2Configuration.MAXIMUM_PAGE_SLOTS * capacity;
    final int fullHdLimit = Mcv2FileEncoder.screenFrameLimit(1920, 1080);
    assertEquals(expectedFullHdLimit, fullHdLimit);
  }

  private byte[] checkerboard(final int width, final int height) {
    final byte[] picture = new byte[width * height * Mcv2Decoder.CHANNELS];
    for (int pixel = 0; pixel < width * height; pixel++) {
      final byte shade = (byte) ((((pixel % width) + pixel / width) % 2) * 255);
      Arrays.fill(picture, pixel * Mcv2Decoder.CHANNELS, (pixel + 1) * Mcv2Decoder.CHANNELS, shade);
    }

    return picture;
  }

  private Mcv2FileEncoder.FrameReader repeating(final byte[] picture, final int count) {
    return new RepeatingReader(picture, count);
  }

  @Test
  void shouldStopWhenInterruptedAndCloseTheVideo() {
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
    final Mcv2Exception thrown;
    try (final Pool budget = new Pool(1)) {
      thrown = assertThrows(Mcv2Exception.class, () ->
        Mcv2FileEncoder.encode(interrupting, WIDTH, HEIGHT, Settings.DEFAULT, budget, new ByteArrayOutputStream(), _ -> {})
      );
    }

    final boolean stillInterrupted = Thread.interrupted();
    assertTrue(stillInterrupted);

    final Throwable cause = thrown.getCause();
    assertInstanceOf(InterruptedException.class, cause);

    final boolean videoClosed = closed.get();
    assertTrue(videoClosed);

    final double emptyMillisecondsPerFrame = Mcv2FileEncoder.Result.of(0, 0, 0, 0).millisecondsPerFrame();
    assertEquals(0, emptyMillisecondsPerFrame);

    final double millisecondsPerFrame = Mcv2FileEncoder.Result.of(2, 0, 0, 5_000_000).millisecondsPerFrame();
    assertEquals(2.5, millisecondsPerFrame);
  }

  @Test
  void shouldReportAStreamItCannotWrite() {
    final IOException failure = new IOException("disk full!");
    final OutputStream full = new OutputStream() {
      @Override
      public void write(final int value) throws IOException {
        throw failure;
      }
    };
    final Mcv2Exception thrown;
    try (final Pool budget = new Pool(1)) {
      thrown = assertThrows(Mcv2Exception.class, () ->
        Mcv2FileEncoder.encode(this.repeating(this.checkerboard(WIDTH, HEIGHT), 1), WIDTH, HEIGHT, Settings.DEFAULT, budget, full, _ -> {})
      );
    }

    final String message = thrown.getMessage();
    assertEquals("The MCV2 stream could not be written!", message);

    final Throwable cause = thrown.getCause();
    assertSame(failure, cause);
  }

  @Test
  void shouldRefuseWhatItCannotEncode() {
    final Mcv2FileEncoder.FrameReader none = mock(Mcv2FileEncoder.FrameReader.class);
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (final Pool budget = new Pool(1)) {
      assertThrows(NullPointerException.class, () -> Mcv2FileEncoder.encode(null, 1, 1, Settings.DEFAULT, budget, out, _ -> {}));
      assertThrows(IllegalArgumentException.class, () -> Mcv2FileEncoder.encode(none, 0, 1, Settings.DEFAULT, budget, out, _ -> {}));
      assertThrows(IllegalArgumentException.class, () -> Mcv2FileEncoder.encode(none, 1, 0, Settings.DEFAULT, budget, out, _ -> {}));
      assertThrows(NullPointerException.class, () -> Mcv2FileEncoder.encode(none, 1, 1, null, budget, out, _ -> {}));
      assertThrows(NullPointerException.class, () -> Mcv2FileEncoder.encode(none, 1, 1, Settings.DEFAULT, null, out, _ -> {}));
      assertThrows(NullPointerException.class, () -> Mcv2FileEncoder.encode(none, 1, 1, Settings.DEFAULT, budget, null, _ -> {}));
      assertThrows(NullPointerException.class, () -> Mcv2FileEncoder.encode(none, 1, 1, Settings.DEFAULT, budget, out, null));
    }

    assertThrows(NullPointerException.class, () -> Mcv2FileEncoder.ffmpeg(null, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> Mcv2FileEncoder.ffmpeg(this.directory, 0, 1));
    assertThrows(IllegalArgumentException.class, () -> Mcv2FileEncoder.ffmpeg(this.directory, 1, 0));

    final Mcv2Exception missing = assertThrows(Mcv2Exception.class, () ->
      Mcv2FileEncoder.ffmpeg(this.directory.resolve("missing.mp4"), 8, 8)
    );
    final String message = missing.getMessage();
    final boolean reportsTheOpen = message.startsWith("Video ") && message.endsWith(" could not be opened!");
    assertTrue(reportsTheOpen);
  }

  @Test
  void shouldCloseAVideoThatCannotStart() throws Exception {
    final FFmpegFrameGrabber grabber = mock(FFmpegFrameGrabber.class);
    doThrow(new FFmpegFrameGrabber.Exception("no stream!")).when(grabber).start();
    doThrow(new FFmpegFrameGrabber.Exception("not open!")).when(grabber).close();
    final Mcv2Exception thrown = assertThrows(Mcv2Exception.class, () -> Mcv2FileEncoder.open(grabber, "clip.mp4", 8, 8));
    final String message = thrown.getMessage();
    assertEquals("Video clip.mp4 could not be opened!", message);

    verify(grabber).close();
  }

  @Test
  void shouldReportFramesItCannotDecodeAndAVideoItCannotClose() throws Exception {
    final FFmpegFrameGrabber grabber = mock(FFmpegFrameGrabber.class);
    when(grabber.grabImage()).thenThrow(new FFmpegFrameGrabber.Exception("broken!"));
    doThrow(new FFmpegFrameGrabber.Exception("stuck!")).when(grabber).close();
    final Mcv2FileEncoder.GrabberReader reader = new Mcv2FileEncoder.GrabberReader(grabber, 8, 8);
    final Mcv2Exception decodeFailure = assertThrows(Mcv2Exception.class, () -> reader.read(new byte[192]));
    final String decodeMessage = decodeFailure.getMessage();
    assertEquals("The video could not be decoded!", decodeMessage);

    final Mcv2Exception closeFailure = assertThrows(Mcv2Exception.class, reader::close);
    final String closeMessage = closeFailure.getMessage();
    assertEquals("The video could not be closed!", closeMessage);
  }

  @Test
  void shouldNotBeInstantiable() throws ReflectiveOperationException {
    UtilityClassAssertions.assertNotInstantiable(Mcv2FileEncoder.class);
  }

  @Test
  void shouldPackPaddedRows() {
    final Frame frame = new Frame(2, 2, Frame.DEPTH_UBYTE, 3, 8);
    final ByteBuffer pixels = (ByteBuffer) frame.image[0];
    for (int index = 0; index < 16; index++) {
      pixels.put(index, (byte) index);
    }

    final byte[] rgb = new byte[12];
    Mcv2FileEncoder.copy(frame, rgb, 2, 2);
    final byte[] expected = new byte[] { 0, 1, 2, 3, 4, 5, 8, 9, 10, 11, 12, 13 };
    assertArrayEquals(expected, rgb);

    assertThrows(IllegalStateException.class, () -> Mcv2FileEncoder.copy(frame, rgb, 3, 2));
    assertThrows(IllegalStateException.class, () -> Mcv2FileEncoder.copy(frame, rgb, 2, 3));
  }

  @Test
  void shouldCompareResultsByEveryPart() {
    final Mcv2FileEncoder.Result result = Mcv2FileEncoder.Result.of(1, 2, 3, 4);
    EqualityAssertions.assertEqualityContract(
      result,
      Mcv2FileEncoder.Result.of(1, 2, 3, 4),
      Mcv2FileEncoder.Result.of(9, 2, 3, 4),
      Mcv2FileEncoder.Result.of(1, 9, 3, 4),
      Mcv2FileEncoder.Result.of(1, 2, 9, 4),
      Mcv2FileEncoder.Result.of(1, 2, 3, 9)
    );
  }

  @Test
  void shouldHashResultsByEveryPart() {
    final Mcv2FileEncoder.Result result = Mcv2FileEncoder.Result.of(1, 2, 3, 4);

    final int hash = result.hashCode();
    final int expected = Objects.hash(1L, 2L, 3L, 4L);
    assertEquals(expected, hash);
  }

  @Test
  void shouldDescribeResultsWithEveryPart() {
    final Mcv2FileEncoder.Result result = Mcv2FileEncoder.Result.of(1, 2, 3, 4);

    final String description = result.toString();
    assertEquals("Result{frames=1, keyframes=2, bytes=3, nanoseconds=4}", description);
  }

  private static final class RepeatingReader implements Mcv2FileEncoder.FrameReader {

    private final byte[] picture;
    private final int count;

    private int read;

    RepeatingReader(final byte[] picture, final int count) {
      this.picture = picture;
      this.count = count;
    }

    @Override
    public boolean read(final byte[] pictureBytes) {
      if (this.read == this.count) {
        return false;
      }

      System.arraycopy(this.picture, 0, pictureBytes, 0, this.picture.length);
      this.read++;

      return true;
    }

    @Override
    public void close() {}
  }
}
