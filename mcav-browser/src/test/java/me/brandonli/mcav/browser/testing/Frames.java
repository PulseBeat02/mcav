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
package me.brandonli.mcav.browser.testing;

import com.google.common.base.MoreObjects;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.concurrent.CopyOnWriteArrayList;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.metadata.OriginalVideoMetadata;
import me.brandonli.mcav.media.player.pipeline.builder.PipelineBuilder;
import me.brandonli.mcav.media.player.pipeline.builder.VideoPipelineStepBuilder;
import me.brandonli.mcav.media.player.pipeline.filter.video.VideoFilter;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import org.checkerframework.checker.nullness.qual.Nullable;

public final class Frames {

  private static final int RED_SHIFT = 16;
  private static final int BITS_PER_CHANNEL = 8;

  private static final int CHANNEL_MASK = 0xFF;
  private static final int RGB_MASK = 0xFFFFFF;

  private static final int CHANNEL_TOLERANCE = 40;

  private static final String CENTER_SEPARATOR = ", ";

  private final List<Frame> frames;

  private Frames() {
    this.frames = new CopyOnWriteArrayList<>();
  }

  public static Frames attach(final VideoAttachableCallback callback) {
    final Frames recorder = new Frames();
    final VideoFilter filter = recorder::record;
    final VideoPipelineStepBuilder builder = PipelineBuilder.video();
    builder.then(filter);
    final VideoPipelineStep pipeline = builder.build();
    callback.attach(pipeline);

    return recorder;
  }

  private boolean record(final ImageBuffer image, final OriginalVideoMetadata metadata) {
    final int width = image.getWidth();
    final int height = image.getHeight();
    final int[] pixels = image.getPixels();
    final int centerIndex = (height / 2) * width + width / 2;
    final int center = pixels[centerIndex] & RGB_MASK;
    final int metadataWidth = metadata.getVideoWidth();
    final int metadataHeight = metadata.getVideoHeight();
    final Frame frame = Frame.of(width, height, center, metadataWidth, metadataHeight);
    this.frames.add(frame);

    return false;
  }

  public int count() {
    return this.frames.size();
  }

  public Frame requireLast() {
    final Frame frame = this.last();
    if (frame == null) {
      final String message = "No frame was delivered!";
      throw new AssertionError(message);
    }

    return frame;
  }

  private @Nullable Frame last() {
    final List<Frame> copy = this.copyFrames();
    if (copy.isEmpty()) {
      return null;
    }

    return copy.getLast();
  }

  private List<Frame> copyFrames() {
    return List.copyOf(this.frames);
  }

  public boolean lastShows(final int rgb) {
    final Frame frame = this.last();
    if (frame == null) {
      return false;
    }

    final int center = frame.getCenter();
    return this.isNear(rgb, center);
  }

  // JPEG compression shifts colors a little
  private boolean isNear(final int expected, final int actual) {
    for (int shift = 0; shift <= RED_SHIFT; shift += BITS_PER_CHANNEL) {
      final int expectedChannel = (expected >> shift) & CHANNEL_MASK;
      final int actualChannel = (actual >> shift) & CHANNEL_MASK;
      final int difference = Math.abs(expectedChannel - actualChannel);
      if (difference > CHANNEL_TOLERANCE) {
        return false;
      }
    }

    return true;
  }

  public String describeLast() {
    final List<Frame> all = this.copyFrames();
    if (all.isEmpty()) {
      return "no frame";
    }

    final Frame frame = all.getLast();
    final String centers = this.describeCenterRuns(all);
    final int count = all.size();
    final int width = frame.getWidth();
    final int height = frame.getHeight();
    final int center = frame.getCenter();
    final String color = this.formatColor(center);
    return "%s frames, the last %sx%s with center %s; centers in order: %s".formatted(count, width, height, color, centers);
  }

  private String describeCenterRuns(final List<Frame> frames) {
    final StringJoiner centers = new StringJoiner(CENTER_SEPARATOR);
    int run = 0;
    for (int index = 0; index < frames.size(); index++) {
      run++;
      final int center = frames.get(index).getCenter();
      if (index == frames.size() - 1 || frames.get(index + 1).getCenter() != center) {
        final String color = this.formatColor(center);
        centers.add("%s x%s".formatted(color, run));
        run = 0;
      }
    }

    return centers.toString();
  }

  private String formatColor(final int color) {
    return String.format(Locale.ROOT, "%06x", color);
  }

  public static final class Frame {

    private final int width;
    private final int height;
    private final int center;
    private final int metadataWidth;
    private final int metadataHeight;

    private Frame(final int width, final int height, final int center, final int metadataWidth, final int metadataHeight) {
      this.width = width;
      this.height = height;
      this.center = center;
      this.metadataWidth = metadataWidth;
      this.metadataHeight = metadataHeight;
    }

    static Frame of(final int width, final int height, final int center, final int metadataWidth, final int metadataHeight) {
      return new Frame(width, height, center, metadataWidth, metadataHeight);
    }

    public int getWidth() {
      return this.width;
    }

    public int getHeight() {
      return this.height;
    }

    // the pixel at (width / 2, height / 2), rounded down
    public int getCenter() {
      return this.center;
    }

    public int getMetadataWidth() {
      return this.metadataWidth;
    }

    public int getMetadataHeight() {
      return this.metadataHeight;
    }

    @Override
    public boolean equals(final @Nullable Object other) {
      if (this == other) {
        return true;
      }

      if (!(other instanceof final Frame frame)) {
        return false;
      }

      return (
        this.width == frame.width &&
        this.height == frame.height &&
        this.center == frame.center &&
        this.metadataWidth == frame.metadataWidth &&
        this.metadataHeight == frame.metadataHeight
      );
    }

    @Override
    public int hashCode() {
      return Objects.hash(this.width, this.height, this.center, this.metadataWidth, this.metadataHeight);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
        .add("width", this.width)
        .add("height", this.height)
        .add("center", this.center)
        .add("metadataWidth", this.metadataWidth)
        .add("metadataHeight", this.metadataHeight)
        .toString();
    }
  }
}
