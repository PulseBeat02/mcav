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

import com.google.common.base.Preconditions;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.function.LongConsumer;
import me.brandonli.mcav.media.mcv2.Mcv2Format;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.ffmpeg.global.swscale;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;

/**
 * Encodes a video ahead of time into an MCV2 stream: the path for a server too small to encode a video while it plays.
 * The frames are encoded one after another, each inside an encoder budget ({@link EncoderPool}), the one every screen
 * of the server shares, so encoding a file ahead takes turns with the screens instead of adding threads, and it never
 * runs on the server's main thread: call it from a thread of its own.
 *
 * <p>The stream is every frame's MCV2 bytes, each preceded by their length as a little-endian 32-bit number, the form
 * a stream is read back in to be played.
 */
public final class Mcv2FileEncoder {

  private static final double NANOS_PER_MILLISECOND = 1e6;

  private Mcv2FileEncoder() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /** The frames of a video, one after another, as RGB bytes of one size. */
  public interface FrameReader extends AutoCloseable {
    /**
     * Reads the next frame.
     *
     * @param rgb where the frame's pixels go, three bytes per pixel, row by row
     * @return false once the video has no more frames
     * @throws IOException if the video cannot be read
     */
    boolean read(byte[] rgb) throws IOException;

    /**
     * Releases the video.
     *
     * @throws IOException if the video cannot be released
     */
    @Override
    void close() throws IOException;
  }

  /**
   * What an encode did.
   *
   * @param frames      the frames encoded
   * @param keyframes   how many of them were keyframes
   * @param bytes       the MCV2 bytes of the frames
   * @param nanoseconds the time the encodes took, waiting for the budget's threads included
   */
  public record Result(long frames, long keyframes, long bytes, long nanoseconds) {
    /**
     * Gets the mean time a frame took.
     *
     * @return milliseconds per frame, 0 without frames
     */
    public double millisecondsPerFrame() {
      return this.frames == 0 ? 0 : this.nanoseconds / NANOS_PER_MILLISECOND / this.frames;
    }
  }

  /**
   * Encodes every frame a reader has into a stream.
   *
   * @param frames   the frames, which are closed at the end
   * @param width    the frames' width
   * @param height   the frames' height
   * @param settings the encoder settings, for example {@link EncoderSettings#SHIP}
   * @param budget   the encoder budget, usually {@link EncoderPool#shared()}
   * @param out      where the stream is written; it is not closed
   * @param progress told the number of frames encoded after every frame
   * @return what the encode did
   * @throws IOException          if the video cannot be read or the stream cannot be written
   * @throws InterruptedException if the calling thread is interrupted, which stops the encode
   */
  public static Result encode(
    final FrameReader frames,
    final int width,
    final int height,
    final EncoderSettings settings,
    final EncoderPool budget,
    final OutputStream out,
    final LongConsumer progress
  ) throws IOException, InterruptedException {
    Preconditions.checkNotNull(frames, "Frames must not be null");
    Preconditions.checkArgument(width > 0 && height > 0, "Size must be positive");
    Preconditions.checkNotNull(settings, "Settings must not be null");
    Preconditions.checkNotNull(budget, "Budget must not be null");
    Preconditions.checkNotNull(out, "Output must not be null");
    Preconditions.checkNotNull(progress, "Progress must not be null");
    try (frames) {
      final Mcv2Encoder encoder = budget.encoder(settings, false);
      final byte[] rgb = new byte[Math.multiplyExact(Math.multiplyExact(width, height), Mcv2Format.CHANNELS)];
      final byte[] length = new byte[Integer.BYTES];
      long count = 0;
      long keyframes = 0;
      long bytes = 0;
      long nanoseconds = 0;
      while (frames.read(rgb)) {
        final long frameId = count;
        final long started = System.nanoTime();
        final byte[] frame = budget.run(() -> encoder.encode(rgb, width, height, frameId));
        nanoseconds += System.nanoTime() - started;
        keyframes += Preconditions.checkNotNull(encoder.getStats()).keyframe() ? 1 : 0;
        ByteBuffer.wrap(length).order(ByteOrder.LITTLE_ENDIAN).putInt(0, frame.length);
        out.write(length);
        out.write(frame);
        bytes += frame.length;
        count++;
        progress.accept(count);
      }
      return new Result(count, keyframes, bytes, nanoseconds);
    }
  }

  /**
   * Opens a video file with FFmpeg, its frames scaled to a size.
   *
   * @param video  the video file
   * @param width  the width of the frames
   * @param height the height of the frames
   * @return the frames
   * @throws IOException if the file cannot be opened
   */
  public static FrameReader ffmpeg(final Path video, final int width, final int height) throws IOException {
    Preconditions.checkNotNull(video, "Video must not be null");
    Preconditions.checkArgument(width > 0 && height > 0, "Size must be positive");
    return open(new FFmpegFrameGrabber(video.toFile()), video.toString(), width, height);
  }

  /**
   * Starts a grabber that decodes frames scaled to a size into RGB.
   *
   * @param grabber the grabber, closed if it cannot start
   * @param name    what it decodes, for the error message
   * @param width   the width of the frames
   * @param height  the height of the frames
   * @return the frames
   * @throws IOException if the grabber cannot start
   */
  static FrameReader open(final FFmpegFrameGrabber grabber, final String name, final int width, final int height) throws IOException {
    grabber.setPixelFormat(avutil.AV_PIX_FMT_RGB24);
    grabber.setImageWidth(width);
    grabber.setImageHeight(height);
    grabber.setImageScalingFlags(swscale.SWS_AREA);
    grabber.setVideoOption("threads", "auto");
    try {
      grabber.start();
    } catch (final FFmpegFrameGrabber.Exception exception) {
      closeQuietly(grabber);
      throw new IOException("Cannot open " + name + ": " + exception.getMessage(), exception);
    }
    return new GrabberReader(grabber, width, height);
  }

  private static void closeQuietly(final FFmpegFrameGrabber grabber) {
    try {
      grabber.close();
    } catch (final FrameGrabber.Exception exception) {
      // nothing was read from it; the failure to open is what is reported
    }
  }

  /** The frames an FFmpeg grabber decodes. */
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
    public boolean read(final byte[] rgb) throws IOException {
      final Frame frame;
      try {
        frame = this.grabber.grabImage();
      } catch (final FFmpegFrameGrabber.Exception exception) {
        throw new IOException("Cannot decode the video: " + exception.getMessage(), exception);
      }
      if (frame == null) {
        return false;
      }
      copy(frame, rgb, this.width, this.height);
      return true;
    }

    @Override
    public void close() throws IOException {
      try {
        this.grabber.close();
      } catch (final FrameGrabber.Exception exception) {
        throw new IOException("Cannot close the video: " + exception.getMessage(), exception);
      }
    }
  }

  /**
   * Copies a decoded RGB frame's rows, which may be padded, into tightly packed bytes.
   *
   * @param frame  the frame
   * @param rgb    where the pixels go
   * @param width  the frame's width
   * @param height the frame's height
   */
  static void copy(final Frame frame, final byte[] rgb, final int width, final int height) {
    Preconditions.checkState(frame.imageWidth == width && frame.imageHeight == height, "The frame is not %sx%s", width, height);
    final ByteBuffer pixels = ((ByteBuffer) frame.image[0]).duplicate();
    final int row = width * Mcv2Format.CHANNELS;
    for (int y = 0; y < height; y++) {
      pixels.position(y * frame.imageStride);
      pixels.get(rgb, y * row, row);
    }
  }
}
