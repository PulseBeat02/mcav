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
package me.brandonli.mcav.media.image;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import me.brandonli.mcav.media.source.file.FileSource;
import me.brandonli.mcav.media.source.uri.UriSource;
import me.brandonli.mcav.utils.IOUtils;
import me.brandonli.mcav.utils.opencv.FramePixels;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.opencv_core.Mat;

/**
 * Decodes animated images with FFmpeg, which supports GIF, APNG, WebP, and every video container. Every frame is
 * copied once, straight from the decoder into the matrix of its image buffer.
 */
final class DynamicImageBufferImpl implements DynamicImageBuffer {

  private static final float DEFAULT_FRAME_RATE = 10.0f;
  private static final double MICROS_PER_SECOND = 1_000_000.0;

  private final List<ImageBuffer> frames;
  private final float frameRate;

  DynamicImageBufferImpl(final FileSource source) throws IOException {
    final Path path = source.getPath();
    final Animation animation = decode(path);
    this.frames = animation.getFrames();
    this.frameRate = animation.getFrameRate();
  }

  DynamicImageBufferImpl(final UriSource source) throws IOException {
    final FileSource downloaded = download(source);
    final Path path = downloaded.getPath();
    final Animation animation = decode(path);
    this.frames = animation.getFrames();
    this.frameRate = animation.getFrameRate();
  }

  private static Animation decode(final Path path) throws IOException {
    final String raw = path.toString();
    final List<ImageBuffer> decoded = new ArrayList<>();
    final float frameRate;
    try (final FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(raw)) {
      grabber.start();
      final TimestampRange timestamps = decodeFrames(grabber, decoded);
      final double reportedFrameRate = grabber.getFrameRate();
      final int frameCount = decoded.size();
      final long first = timestamps.getFirst();
      final long last = timestamps.getLast();
      frameRate = resolveFrameRate(reportedFrameRate, frameCount, first, last);
    } catch (final IOException | RuntimeException exception) {
      releaseAll(decoded);
      throw exception;
    }
    final boolean empty = decoded.isEmpty();
    Preconditions.checkArgument(!empty, "Image contains no frames: %s", path);
    final List<ImageBuffer> frames = List.copyOf(decoded);
    return new Animation(frames, frameRate);
  }

  private static FileSource download(final UriSource source) {
    final Path downloaded = IOUtils.downloadImage(source);
    return FileSource.path(downloaded);
  }

  private static TimestampRange decodeFrames(final FFmpegFrameGrabber grabber, final List<ImageBuffer> decoded) throws IOException {
    final TimestampRange timestamps = new TimestampRange();
    Frame current = grabber.grabImage();
    while (current != null) {
      copyFrame(current, decoded);
      timestamps.add(current.timestamp);
      current = grabber.grabImage();
    }
    return timestamps;
  }

  /**
   * Copies a decoded frame into a new image, which is added to the list before it is filled, so it is released with
   * the others if the copy fails.
   */
  private static void copyFrame(final Frame frame, final List<ImageBuffer> decoded) {
    final int width = frame.imageWidth;
    final int height = frame.imageHeight;
    final Mat mat = new Mat(height, width, opencv_core.CV_8UC3);
    final ImageBuffer image = ImageBuffer.mat(mat);
    decoded.add(image);
    final ByteBuffer data = image.getData();
    FramePixels.copyBgr(frame, data);
  }

  /**
   * Chooses the frame rate of an animation. GIFs often report no frame rate, so the rate is derived from the
   * timestamps when possible: the frames after the first one take up the time from the first to the last timestamp.
   *
   * @param reported       the frame rate the decoder reported, which may be zero, negative, or not finite
   * @param frameCount     the number of decoded frames
   * @param firstTimestamp the timestamp of the first frame in microseconds, which need not be zero
   * @param lastTimestamp  the timestamp of the last frame in microseconds
   * @return the frame rate
   */
  @VisibleForTesting
  static float resolveFrameRate(final double reported, final int frameCount, final long firstTimestamp, final long lastTimestamp) {
    final boolean validReported = reported > 0 && Double.isFinite(reported);
    if (validReported) {
      return (float) reported;
    }
    final long span = lastTimestamp - firstTimestamp;
    if (frameCount > 1 && span > 0) {
      final double seconds = span / MICROS_PER_SECOND;
      final double derived = (frameCount - 1) / seconds;
      return (float) derived;
    }
    return DEFAULT_FRAME_RATE;
  }

  private static void releaseAll(final List<ImageBuffer> frames) {
    for (final ImageBuffer frame : frames) {
      frame.release();
    }
  }

  @Override
  public List<ImageBuffer> getFrames() {
    return this.frames;
  }

  @Override
  public float getFrameRate() {
    return this.frameRate;
  }

  @Override
  public ImageBuffer getFrame(final int index) {
    final int count = this.frames.size();
    Preconditions.checkElementIndex(index, count, "Frame index");
    return this.frames.get(index);
  }

  @Override
  public int getFrameCount() {
    return this.frames.size();
  }

  @Override
  public void close() {
    releaseAll(this.frames);
  }

  /**
   * The decoded frames of an animation and its frame rate.
   */
  private static final class Animation {

    private final List<ImageBuffer> frames;
    private final float frameRate;

    Animation(final List<ImageBuffer> frames, final float frameRate) {
      this.frames = frames;
      this.frameRate = frameRate;
    }

    List<ImageBuffer> getFrames() {
      return this.frames;
    }

    float getFrameRate() {
      return this.frameRate;
    }
  }

  /**
   * The timestamps of the first and the last decoded frame.
   */
  private static final class TimestampRange {

    private long first;
    private long last;
    private boolean empty;

    TimestampRange() {
      this.empty = true;
    }

    void add(final long timestamp) {
      if (this.empty) {
        this.first = timestamp;
        this.empty = false;
      }
      this.last = timestamp;
    }

    long getFirst() {
      return this.first;
    }

    long getLast() {
      return this.last;
    }
  }
}
