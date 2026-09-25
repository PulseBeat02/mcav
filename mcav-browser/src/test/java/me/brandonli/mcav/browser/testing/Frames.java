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

/**
 * Records the frames a player delivers and creates encoded frames for tests.
 */
public final class Frames {

  private final List<Frame> frames;

  private Frames() {
    this.frames = new CopyOnWriteArrayList<>();
  }

  /**
   * Attaches a new recorder to a video callback.
   *
   * @param callback the callback of the player
   * @return the recorder
   */
  public static Frames attach(final VideoAttachableCallback callback) {
    final Frames recorder = new Frames();
    final VideoFilter filter = recorder::record;
    final VideoPipelineStepBuilder builder = PipelineBuilder.video();
    builder.then(filter);
    final VideoPipelineStep pipeline = builder.build();
    callback.attach(pipeline);
    return recorder;
  }

  /**
   * Encodes an image of one color as a JPEG.
   *
   * @param width  the width
   * @param height the height
   * @param rgb    the color as packed RGB
   * @return the JPEG bytes
   */
  public static byte[] jpeg(final int width, final int height, final int rgb) {
    final BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        image.setRGB(x, y, rgb);
      }
    }
    try (final ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      ImageIO.write(image, "jpg", output);
      return output.toByteArray();
    } catch (final IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  /**
   * Checks whether two colors are close, as colors that went through JPEG compression are.
   *
   * @param expected the expected color as packed RGB
   * @param actual   the actual color as packed RGB
   * @return true if every channel differs by at most 40
   */
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
    // The recorder only reads the input image.
    return false;
  }

  /**
   * Gets the recorded frames.
   *
   * @return a copy of the frames in the order they arrived
   */
  public List<Frame> getFrames() {
    return List.copyOf(this.frames);
  }

  /**
   * Counts the recorded frames.
   *
   * @return the number of frames
   */
  public int count() {
    return this.frames.size();
  }

  /**
   * Gets the last recorded frame.
   *
   * @return the frame, or null if none arrived
   */
  public @Nullable Frame last() {
    final List<Frame> copy = this.getFrames();
    if (copy.isEmpty()) {
      return null;
    }
    return copy.getLast();
  }

  /**
   * Gets the last recorded frame of a test that waited for a frame.
   *
   * @return the frame
   * @throws AssertionError if no frame arrived
   */
  public Frame requireLast() {
    final Frame frame = this.last();
    if (frame == null) {
      throw new AssertionError("No frame was delivered");
    }
    return frame;
  }

  /**
   * Checks whether the last frame shows a color.
   *
   * @param rgb the color as packed RGB
   * @return true if the center of the last frame is near the color
   */
  public boolean lastShows(final int rgb) {
    final Frame frame = this.last();
    if (frame == null) {
      return false;
    }
    final int center = frame.getCenter();
    return isNear(rgb, center);
  }

  /**
   * Describes the last frame for a failure message: how many frames arrived, and the size and the center color of
   * the last one.
   *
   * @return the description
   */
  public String describeLast() {
    final Frame frame = this.last();
    if (frame == null) {
      return "no frame";
    }
    return (
      this.count() +
      " frames, the last " +
      frame.getWidth() +
      "x" +
      frame.getHeight() +
      " with center " +
      String.format("%06x", frame.getCenter())
    );
  }

  /**
   * A delivered frame.
   */
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

    /**
     * Gets the width.
     *
     * @return the width in pixels
     */
    public int getWidth() {
      return this.width;
    }

    /**
     * Gets the height.
     *
     * @return the height in pixels
     */
    public int getHeight() {
      return this.height;
    }

    /**
     * Gets the color of the pixel at (width / 2, height / 2), rounded down to integer coordinates.
     *
     * @return the color as packed RGB
     */
    public int getCenter() {
      return this.center;
    }

    /**
     * Gets the width in the metadata that came with the frame.
     *
     * @return the width in pixels
     */
    public int getMetadataWidth() {
      return this.metadataWidth;
    }

    /**
     * Gets the height in the metadata that came with the frame.
     *
     * @return the height in pixels
     */
    public int getMetadataHeight() {
      return this.metadataHeight;
    }
  }
}
