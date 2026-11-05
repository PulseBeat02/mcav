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
import me.brandonli.mcav.utils.IoUtils;
import me.brandonli.mcav.utils.opencv.FramePixels;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.opencv_core.Mat;

// Decodes animated images, such as GIF, APNG and WebP, with FFmpeg, copying each frame once from the decoder into its buffer's matrix.
final class FfmpegDynamicImageBuffer implements DynamicImageBuffer {

  private static final float DEFAULT_FRAME_RATE = 10.0f;

  private static final double MICROSECONDS_PER_SECOND = 1_000_000.0;

  private final List<ImageBuffer> frames;
  private final float frameRate;

  FfmpegDynamicImageBuffer(final FileSource source) {
    final Path path = source.getPath();
    final Animation animation = decode(path);
    this.frames = animation.frames();
    this.frameRate = animation.frameRate();
  }

  FfmpegDynamicImageBuffer(final UriSource source) {
    final FileSource downloaded = download(source);
    final Path path = downloaded.getPath();
    final Animation animation = decode(path);
    this.frames = animation.frames();
    this.frameRate = animation.frameRate();
  }

  private static Animation decode(final Path path) {
    final String raw = path.toString();
    final FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(raw);
    final List<ImageBuffer> decoded = new ArrayList<>();
    return decode(path, grabber, decoded);
  }

  // test seam for the timestamp and releaseAll mutants: a grabber without a frame rate that starts after zero, and the decoded list
  @VisibleForTesting
  static Animation decode(final Path path, final FFmpegFrameGrabber grabber, final List<ImageBuffer> decoded) {
    final float frameRate;
    try {
      frameRate = decodeInto(path, grabber, decoded);
    } catch (final RuntimeException exception) {
      releaseAll(decoded);
      throw exception;
    }

    final boolean empty = decoded.isEmpty();
    Preconditions.checkArgument(!empty, "Image %s contains no frames!", path);

    final List<ImageBuffer> frames = List.copyOf(decoded);
    return new Animation(frames, frameRate);
  }

  private static float decodeInto(final Path path, final FFmpegFrameGrabber grabber, final List<ImageBuffer> decoded) {
    try (grabber) {
      grabber.start();
      final TimestampRange timestamps = decodeFrames(grabber, decoded);

      final double reportedFrameRate = grabber.getFrameRate();
      final int frameCount = decoded.size();
      final long first = timestamps.getFirst();
      final long last = timestamps.getLast();
      return resolveFrameRate(reportedFrameRate, frameCount, first, last);
    } catch (final IOException exception) {
      final String message = "Image %s could not be decoded!".formatted(path);
      throw new ImageDecodingException(message, exception);
    }
  }

  private static FileSource download(final UriSource source) {
    final Path downloaded = IoUtils.downloadImage(source);
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

  // The image joins the list before it is filled, so a failed copy releases it with the others.
  private static void copyFrame(final Frame frame, final List<ImageBuffer> decoded) {
    final int width = frame.imageWidth;
    final int height = frame.imageHeight;
    final Mat matrix = new Mat(height, width, opencv_core.CV_8UC3);
    final ImageBuffer image = ImageBuffer.mat(matrix);
    decoded.add(image);

    final ByteBuffer data = image.getData();
    FramePixels.copyBgr(frame, data);
  }

  // GIFs often report no frame rate, so it is derived from the timestamps: the frames after the first span the first to the last.
  @VisibleForTesting
  static float resolveFrameRate(final double reported, final int frameCount, final long firstTimestamp, final long lastTimestamp) {
    final float reportedRate = (float) reported;
    final boolean validReported = reportedRate > 0 && Float.isFinite(reportedRate);
    if (validReported) {
      return reportedRate;
    }

    final long span = lastTimestamp - firstTimestamp;
    if (frameCount > 1 && span > 0) {
      final double seconds = span / MICROSECONDS_PER_SECOND;
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
  public void close() {
    releaseAll(this.frames);
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

  record Animation(List<ImageBuffer> frames, float frameRate) {}

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

    private long getFirst() {
      return this.first;
    }

    private long getLast() {
      return this.last;
    }
  }
}
