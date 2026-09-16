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
package me.brandonli.mcav.testing;

import com.google.common.base.Preconditions;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Random;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import me.brandonli.mcav.media.image.ImageBuffer;

/**
 * Creates small images for tests.
 */
public final class Images {

  private static final String GIF_METADATA_FORMAT = "javax_imageio_gif_image_1.0";
  private static final String GRAPHIC_CONTROL_EXTENSION = "GraphicControlExtension";
  private static final int OPAQUE = 0xFF000000;
  private static final int RGB_RANGE = 0x1000000;

  private Images() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Creates an image filled with one color.
   *
   * @param width  the width, at least 1
   * @param height the height, at least 1
   * @param argb   the color as packed ARGB
   * @return the image
   */
  public static ImageBuffer solid(final int width, final int height, final int argb) {
    checkDimensions(width, height);
    final int[] pixels = new int[width * height];
    Arrays.fill(pixels, argb);
    return ImageBuffer.buffer(pixels, width, height);
  }

  /**
   * Creates an image of opaque random colors, the same for the same seed.
   *
   * @param width  the width, at least 1
   * @param height the height, at least 1
   * @param seed   the seed of the random colors
   * @return the image
   */
  public static ImageBuffer noise(final int width, final int height, final long seed) {
    checkDimensions(width, height);
    final Random random = new Random(seed);
    final int[] pixels = new int[width * height];
    for (int index = 0; index < pixels.length; index++) {
      final int color = random.nextInt(RGB_RANGE);
      pixels[index] = OPAQUE | color;
    }
    return ImageBuffer.buffer(pixels, width, height);
  }

  /**
   * Creates an image whose pixel at index {@code i} has the opaque color {@code i}, so every pixel is unique and its
   * original position can be read from its color.
   *
   * @param width  the width, at least 1
   * @param height the height, at least 1
   * @return the image
   */
  public static ImageBuffer indexed(final int width, final int height) {
    final int[] pixels = indexedPixels(width, height);
    return ImageBuffer.buffer(pixels, width, height);
  }

  /**
   * Creates the pixels of {@link #indexed(int, int)}.
   *
   * @param width  the width, at least 1
   * @param height the height, at least 1
   * @return the pixels
   */
  public static int[] indexedPixels(final int width, final int height) {
    checkDimensions(width, height);
    final int count = width * height;
    final int[] pixels = new int[count];
    for (int index = 0; index < count; index++) {
      pixels[index] = OPAQUE | index;
    }
    return pixels;
  }

  /**
   * Encodes an image in a format ImageIO supports.
   *
   * @param image  the image
   * @param format the format name, such as {@code png}
   * @return the encoded bytes
   * @throws IllegalArgumentException if ImageIO has no writer for the format
   */
  public static byte[] encode(final BufferedImage image, final String format) {
    Preconditions.checkNotNull(image, "Image must not be null");
    Preconditions.checkNotNull(format, "Format must not be null");
    try (final ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      final boolean written = ImageIO.write(image, format, output);
      Preconditions.checkArgument(written, "ImageIO cannot write the format %s", format);
      return output.toByteArray();
    } catch (final IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  /**
   * Encodes an animated GIF with one solid frame per color.
   *
   * @param width             the width of the frames, at least 1
   * @param height            the height of the frames, at least 1
   * @param delayInHundredths how long every frame is shown, in hundredths of a second
   * @param colors            the colors of the frames as packed RGB
   * @return the encoded GIF
   */
  public static byte[] animatedGif(final int width, final int height, final int delayInHundredths, final int... colors) {
    checkDimensions(width, height);
    Preconditions.checkNotNull(colors, "Colors must not be null");
    final Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("gif");
    final ImageWriter writer = writers.next();
    try (final ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      try (final ImageOutputStream imageOutput = ImageIO.createImageOutputStream(output)) {
        writer.setOutput(imageOutput);
        writer.prepareWriteSequence(null);
        for (final int color : colors) {
          writeGifFrame(writer, width, height, delayInHundredths, color);
        }
        writer.endWriteSequence();
      }
      return output.toByteArray();
    } catch (final IOException exception) {
      throw new UncheckedIOException(exception);
    } finally {
      writer.dispose();
    }
  }

  private static void checkDimensions(final int width, final int height) {
    Preconditions.checkArgument(width > 0, "Width must be positive but was %s", width);
    Preconditions.checkArgument(height > 0, "Height must be positive but was %s", height);
  }

  private static void writeGifFrame(final ImageWriter writer, final int width, final int height, final int delay, final int color)
    throws IOException {
    final BufferedImage frame = createSolidFrame(width, height, color);
    final IIOMetadata metadata = createFrameMetadata(writer, frame, delay);
    final IIOImage image = new IIOImage(frame, null, metadata);
    writer.writeToSequence(image, null);
  }

  private static BufferedImage createSolidFrame(final int width, final int height, final int color) {
    final BufferedImage frame = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        frame.setRGB(x, y, color);
      }
    }
    return frame;
  }

  private static IIOMetadata createFrameMetadata(final ImageWriter writer, final BufferedImage frame, final int delay) throws IOException {
    final ImageTypeSpecifier type = ImageTypeSpecifier.createFromRenderedImage(frame);
    final IIOMetadata metadata = writer.getDefaultImageMetadata(type, null);
    final IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(GIF_METADATA_FORMAT);
    final IIOMetadataNode control = findOrCreateGraphicControl(root);
    final String delayTime = String.valueOf(delay);
    control.setAttribute("disposalMethod", "none");
    control.setAttribute("userInputFlag", "FALSE");
    control.setAttribute("transparentColorFlag", "FALSE");
    control.setAttribute("delayTime", delayTime);
    control.setAttribute("transparentColorIndex", "0");
    metadata.setFromTree(GIF_METADATA_FORMAT, root);
    return metadata;
  }

  private static IIOMetadataNode findOrCreateGraphicControl(final IIOMetadataNode root) {
    final int childCount = root.getLength();
    for (int index = 0; index < childCount; index++) {
      final IIOMetadataNode child = (IIOMetadataNode) root.item(index);
      final String childName = child.getNodeName();
      if (childName.equals(GRAPHIC_CONTROL_EXTENSION)) {
        return child;
      }
    }
    final IIOMetadataNode created = new IIOMetadataNode(GRAPHIC_CONTROL_EXTENSION);
    root.appendChild(created);
    return created;
  }
}
