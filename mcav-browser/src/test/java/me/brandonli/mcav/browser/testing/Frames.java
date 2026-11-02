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

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.imageio.ImageIO;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.metadata.OriginalVideoMetadata;
import me.brandonli.mcav.media.player.pipeline.builder.PipelineBuilder;
import me.brandonli.mcav.media.player.pipeline.builder.VideoPipelineStepBuilder;
import me.brandonli.mcav.media.player.pipeline.filter.video.VideoFilter;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import org.checkerframework.checker.nullness.qual.Nullable;

public final class Frames {

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

  public static byte[] jpeg(final int width, final int height, final int rgb) {
    final BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    for (int row = 0; row < height; row++) {
      for (int column = 0; column < width; column++) {
        image.setRGB(column, row, rgb);
      }
    }

    try (final ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      ImageIO.write(image, "jpg", output);
      return output.toByteArray();
    } catch (final IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  // JPEG compression shifts colors a little
  public static boolean isNear(final int expected, final int actual) {
    for (int shift = 0; shift <= 16; shift += 8) {
      final int expectedChannel = (expected >> shift) & 0xFF;
      final int actualChannel = (actual >> shift) & 0xFF;
      final int difference = Math.abs(expectedChannel - actualChannel);
      if (difference > 40) {
        return false;
      }
    }

    return true;
  }

  private boolean record(final ImageBuffer image, final OriginalVideoMetadata metadata) {
    final int width = image.getWidth();
    final int height = image.getHeight();
    final int[] pixels = image.getPixels();
    final int centerIndex = (height / 2) * width + width / 2;
    final int center = pixels[centerIndex] & 0xFFFFFF;
    final int metadataWidth = metadata.getVideoWidth();
    final int metadataHeight = metadata.getVideoHeight();
    final Frame frame = new Frame(width, height, center, metadataWidth, metadataHeight);
    this.frames.add(frame);

    return false;
  }

  public int count() {
    return this.frames.size();
  }

  public @Nullable Frame last() {
    final List<Frame> copy = this.getFrames();
    if (copy.isEmpty()) {
      return null;
    }

    return copy.getLast();
  }

  public Frame requireLast() {
    final Frame frame = this.last();
    if (frame == null) {
      final String message = "No frame was delivered!";
      throw new AssertionError(message);
    }

    return frame;
  }

  public boolean lastShows(final int rgb) {
    final Frame frame = this.last();
    if (frame == null) {
      return false;
    }

    final int center = frame.getCenter();
    return isNear(rgb, center);
  }

  public String describeLast() {
    final List<Frame> all = this.getFrames();
    if (all.isEmpty()) {
      return "no frame";
    }

    final Frame frame = all.getLast();
    final StringBuilder centers = new StringBuilder();
    int run = 0;
    for (int index = 0; index < all.size(); index++) {
      run++;
      final int center = all.get(index).getCenter();
      if (index == all.size() - 1 || all.get(index + 1).getCenter() != center) {
        centers
          .append(centers.isEmpty() ? "" : ", ")
          .append(String.format(Locale.ROOT, "%06x", center))
          .append(" x")
          .append(run);
        run = 0;
      }
    }

    return (
      all.size() +
      " frames, the last " +
      frame.getWidth() +
      "x" +
      frame.getHeight() +
      " with center " +
      String.format(Locale.ROOT, "%06x", frame.getCenter()) +
      "; centers in order: " +
      centers
    );
  }

  public List<Frame> getFrames() {
    return List.copyOf(this.frames);
  }

  public static final class Frame {

    private final int width;
    private final int height;
    private final int center;
    private final int metadataWidth;
    private final int metadataHeight;

    Frame(final int width, final int height, final int center, final int metadataWidth, final int metadataHeight) {
      this.width = width;
      this.height = height;
      this.center = center;
      this.metadataWidth = metadataWidth;
      this.metadataHeight = metadataHeight;
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
  }
}
