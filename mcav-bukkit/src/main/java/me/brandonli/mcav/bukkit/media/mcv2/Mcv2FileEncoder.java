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

import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.LongConsumer;
import me.brandonli.mcav.bukkit.media.map.MapLayout;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2.Pool;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2.Settings;
import me.brandonli.mcav.bukkit.media.mcv2.transport.TransportPages;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.ffmpeg.global.swscale;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Encodes a video ahead of time into an MCV2 stream: the path for a server too small to encode a video while it plays.
 * The frames are encoded one after another, each inside an encoder budget ({@link Pool}), the one every screen
 * of the server shares, so encoding a file ahead takes turns with the screens instead of adding threads.
 *
 * <p>Every frame fits the default page slots of a wall of the video's native size, one map for each 128 by 128 pixels:
 * a screen drops a bigger frame, and a stream that was encoded ahead cannot send the keyframe that would follow.
 *
 * <p>The stream is every frame's MCV2 bytes, each preceded by their length as a little-endian 32-bit number, the form
 * a stream is read back in to be played.
 *
 * <p>Call from an application worker thread: the method performs reading, writing and progress callbacks
 * synchronously, and waits for encoding tasks in the supplied budget. It does not dispatch the entire operation
 * off the calling thread. The caller owns the output stream and pool; the frame reader is closed after validated
 * arguments enter the encode operation, including on encoding or I/O failure.
 *
 * {@snippet :
 * final int width = 1920;
 * final int height = 1080;
 * final Mcv2.Settings settings = Mcv2.Settings.DEFAULT;
 * final Mcv2.Pool budget = Mcv2.Pool.shared();
 * try (
 *   final Mcv2FileEncoder.FrameReader frames = Mcv2FileEncoder.ffmpeg(video, width, height);
 *   final OutputStream output = Files.newOutputStream(stream)
 * ) {
 *   final Mcv2FileEncoder.Result result = Mcv2FileEncoder.encode(frames, width, height, settings, budget, output, progress);
 * }
 * }
 *
 * @since 1.0.0
 */
public final class Mcv2FileEncoder {

  private static final String SIZE_NOT_POSITIVE_MESSAGE = "Size must be positive!";

  private static final double NANOSECONDS_PER_MILLISECOND = 1e6;

  private Mcv2FileEncoder() {
    final String message = "Utility class cannot be instantiated!";
    throw new UnsupportedOperationException(message);
  }

  /**
   * Encodes every frame a reader has into a stream.
   *
   * @param frames   the non-null reader, closed at the end after argument validation; validation failures
   *                 before encoding starts leave it caller-owned
   * @param width    the frame width in pixels, 1 through 4096 for encoding
   * @param height   the frame height in pixels, 1 through 4096 for encoding
   * @param settings the encoder settings, for example {@link Settings#DEFAULT}
   * @param budget   the encoder budget, usually {@link Pool#shared()}
   * @param output   the caller-owned output, written synchronously without flush or close; failures can leave
   *                 a partial length prefix or frame in the stream
   * @param progress called synchronously on the calling thread after each complete frame write, with
   *                 the cumulative count starting at one; callback failures abort encoding
   * @return what the encode did
   * @throws Mcv2Exception              if the reader cannot read or close, the output cannot be written, or the calling
   *                                    thread is interrupted, which stops the encode and leaves the thread interrupted
   * @throws IllegalArgumentException   if a dimension is nonpositive, or an encoded frame exceeds the codec
   *                                    dimension/id range
   * @throws ArithmeticException        if the RGB allocation size overflows an int
   * @throws RejectedExecutionException if the budget rejects the encoding task
   * @throws NullPointerException       if {@code frames}, {@code settings}, {@code budget}, {@code output} or {@code progress} is null
   * @since 1.0.0
   */
  public static Result encode(
    final FrameReader frames,
    final int width,
    final int height,
    final Settings settings,
    final Pool budget,
    final OutputStream output,
    final LongConsumer progress
  ) {
    Preconditions.checkNotNull(frames, "Frames must not be null!");
    Preconditions.checkArgument(width > 0 && height > 0, SIZE_NOT_POSITIVE_MESSAGE);
    Preconditions.checkNotNull(settings, "Settings must not be null!");
    Preconditions.checkNotNull(budget, "Budget must not be null!");
    Preconditions.checkNotNull(output, "Output must not be null!");
    Preconditions.checkNotNull(progress, "Progress must not be null!");

    try (frames) {
      final Mcv2 encoder = budget.encoder(settings, Mcv2.Verification.UNVERIFIED);
      encoder.setFrameLimit(screenFrameLimit(width, height));
      final byte[] pictureBytes = new byte[Math.multiplyExact(Math.multiplyExact(width, height), Mcv2Decoder.CHANNELS)];
      final byte[] length = new byte[Integer.BYTES];
      long count = 0;
      long keyframes = 0;
      long bytes = 0;
      long nanoseconds = 0;
      while (frames.read(pictureBytes)) {
        final long frameId = count;
        final long started = System.nanoTime();
        final byte[] frame = budget.run(() -> encoder.encode(pictureBytes, width, height, frameId));
        nanoseconds += System.nanoTime() - started;
        keyframes += encoder.getStatistics().orElseThrow().isKeyframe() ? 1 : 0;
        ByteBuffer.wrap(length).order(ByteOrder.LITTLE_ENDIAN).putInt(0, frame.length);
        writeFrame(output, length, frame);
        bytes += frame.length;
        count++;
        progress.accept(count);
      }

      return new Result(count, keyframes, bytes, nanoseconds);
    }
  }

  static int screenFrameLimit(final int width, final int height) {
    final int maps = Math.ceilDiv(width, MapLayout.MAP_SIZE) * Math.ceilDiv(height, MapLayout.MAP_SIZE);
    return Mcv2Configuration.defaultPageSlots(maps) * TransportPages.capacity();
  }

  private static void writeFrame(final OutputStream output, final byte[] length, final byte[] frame) {
    try {
      output.write(length);
      output.write(frame);
    } catch (final IOException exception) {
      final String message = "The MCV2 stream could not be written!";
      throw new Mcv2Exception(message, exception);
    }
  }

  /**
   * Opens a video file with FFmpeg, its frames scaled to a size.
   *
   * <p>The returned reader owns the FFmpeg grabber. Close it after use or pass it to encode, which closes it
   * once argument validation succeeds. Do not read or close the same reader concurrently.
   *
   * @param video  the video file
   * @param width  the width of the frames
   * @param height the height of the frames
   * @return the frames
   * @throws Mcv2Exception            if the file cannot be opened
   * @throws IllegalArgumentException if either dimension is nonpositive
   * @throws NullPointerException     if {@code video} is null
   * @since 1.0.0
   */
  public static FrameReader ffmpeg(final Path video, final int width, final int height) {
    Preconditions.checkNotNull(video, "Video must not be null!");
    Preconditions.checkArgument(width > 0 && height > 0, SIZE_NOT_POSITIVE_MESSAGE);

    return open(new FFmpegFrameGrabber(video.toFile()), video.toString(), width, height);
  }

  static FrameReader open(final FFmpegFrameGrabber grabber, final String name, final int width, final int height) {
    grabber.setPixelFormat(avutil.AV_PIX_FMT_RGB24);
    grabber.setImageWidth(width);
    grabber.setImageHeight(height);
    grabber.setImageScalingFlags(swscale.SWS_AREA);
    grabber.setVideoOption("threads", "auto");
    try {
      grabber.start();
    } catch (final FFmpegFrameGrabber.Exception exception) {
      closeQuietly(grabber);
      final String message = "Video %s could not be opened!".formatted(name);
      throw new Mcv2Exception(message, exception);
    }

    return new GrabberReader(grabber, width, height);
  }

  private static void closeQuietly(final FFmpegFrameGrabber grabber) {
    try {
      grabber.close();
    } catch (final FrameGrabber.Exception exception) {
      // A close failure must not hide the error that prevented opening the input.
    }
  }

  static void copy(final Frame frame, final byte[] pictureBytes, final int width, final int height) {
    Preconditions.checkState(frame.imageWidth == width && frame.imageHeight == height, "The frame must be %sx%s pixels!", width, height);

    final ByteBuffer pixels = ((ByteBuffer) frame.image[0]).duplicate();
    final int rowBytes = width * Mcv2Decoder.CHANNELS;
    for (int row = 0; row < height; row++) {
      pixels.position(row * frame.imageStride);
      pixels.get(pictureBytes, row * rowBytes, rowBytes);
    }
  }

  /**
   * The frames of a video, one after another, as RGB bytes of one size.
   *
   * <p>The encoder reads from one thread only, so implementations need not be thread-safe.
   *
   * @since 1.0.0
   */
  public interface FrameReader extends AutoCloseable {
    /**
     * Reads the next frame.
     *
     * @param pictureBytes where the frame's pixels go, three bytes per pixel, row by row
     * @return false once the video has no more frames
     * @throws Mcv2Exception if the video cannot be read
     * @since 1.0.0
     */
    boolean read(byte[] pictureBytes);

    /**
     * Releases the video.
     *
     * @throws Mcv2Exception if the video cannot be released
     * @since 1.0.0
     */
    @Override
    void close();
  }

  /**
   * What an encode did.
   *
   * <p>This class is immutable and thread-safe.
   *
   * @since 1.0.0
   */
  public static final class Result {

    private final long frames;
    private final long keyframes;
    private final long bytes;
    private final long nanoseconds;

    private Result(final long frames, final long keyframes, final long bytes, final long nanoseconds) {
      this.frames = frames;
      this.keyframes = keyframes;
      this.bytes = bytes;
      this.nanoseconds = nanoseconds;
    }

    /**
     * Returns the result of an encode.
     *
     * @param frames      the frames encoded
     * @param keyframes   how many of them were keyframes
     * @param bytes       the MCV2 frame bytes, excluding each four-byte length prefix
     * @param nanoseconds the time the encodes took, waiting for the budget's threads included
     * @return the result
     * @since 1.0.0
     */
    public static Result of(final long frames, final long keyframes, final long bytes, final long nanoseconds) {
      return new Result(frames, keyframes, bytes, nanoseconds);
    }

    /**
     * Returns the mean time a frame took.
     *
     * @return milliseconds per frame, 0 without frames
     * @since 1.0.0
     */
    public double millisecondsPerFrame() {
      return this.frames == 0 ? 0 : this.nanoseconds / NANOSECONDS_PER_MILLISECOND / this.frames;
    }

    /**
     * Returns the number of frames encoded.
     *
     * @return the frames encoded
     * @since 1.0.0
     */
    public long getFrames() {
      return this.frames;
    }

    /**
     * Returns how many of the frames were keyframes.
     *
     * @return how many of them were keyframes
     * @since 1.0.0
     */
    public long getKeyframes() {
      return this.keyframes;
    }

    /**
     * Returns the MCV2 frame bytes, excluding each four-byte length prefix.
     *
     * @return the MCV2 frame bytes, excluding each four-byte length prefix
     * @since 1.0.0
     */
    public long getBytes() {
      return this.bytes;
    }

    /**
     * Returns the time the encodes took in nanoseconds.
     *
     * @return the time the encodes took, waiting for the budget's threads included
     * @since 1.0.0
     */
    public long getNanoseconds() {
      return this.nanoseconds;
    }

    /**
     * Returns whether {@code other} is a result with the same parts.
     *
     * @param other the object to compare with
     * @return whether every part matches
     * @since 1.0.0
     */
    @Override
    public boolean equals(final @Nullable Object other) {
      if (this == other) {
        return true;
      }

      if (!(other instanceof final Result result)) {
        return false;
      }

      return (
        this.frames == result.frames &&
        this.keyframes == result.keyframes &&
        this.bytes == result.bytes &&
        this.nanoseconds == result.nanoseconds
      );
    }

    /**
     * Returns a hash code built from every part.
     *
     * @return the hash code
     * @since 1.0.0
     */
    @Override
    public int hashCode() {
      return Objects.hash(this.frames, this.keyframes, this.bytes, this.nanoseconds);
    }

    /**
     * Returns a debugging representation with every part.
     *
     * @return the representation
     * @since 1.0.0
     */
    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
        .add("frames", this.frames)
        .add("keyframes", this.keyframes)
        .add("bytes", this.bytes)
        .add("nanoseconds", this.nanoseconds)
        .toString();
    }
  }

  static final class GrabberReader implements FrameReader {

    private final FFmpegFrameGrabber grabber;

    private final int width;

    private final int height;

    GrabberReader(final FFmpegFrameGrabber grabber, final int width, final int height) {
      this.grabber = grabber;
      this.width = width;
      this.height = height;
    }

    @Override
    public boolean read(final byte[] pictureBytes) {
      final Frame frame;
      try {
        frame = this.grabber.grabImage();
      } catch (final FFmpegFrameGrabber.Exception exception) {
        final String message = "The video could not be decoded!";
        throw new Mcv2Exception(message, exception);
      }

      if (frame == null) {
        return false;
      }

      copy(frame, pictureBytes, this.width, this.height);

      return true;
    }

    @Override
    public void close() {
      try {
        this.grabber.close();
      } catch (final FrameGrabber.Exception exception) {
        final String message = "The video could not be closed!";
        throw new Mcv2Exception(message, exception);
      }
    }
  }
}
