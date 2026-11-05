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

public final class Images {

  private static final String GIF_FORMAT_NAME = "gif";
  private static final String GIF_METADATA_FORMAT = "javax_imageio_gif_image_1.0";
  private static final String GRAPHIC_CONTROL_EXTENSION = "GraphicControlExtension";
  private static final String GIF_FALSE = "FALSE";

  private static final int OPAQUE = 0xFF000000;
  private static final int RGB_RANGE = 0x1000000;

  private Images() {
    final String message = "Utility class cannot be instantiated!";
    throw new UnsupportedOperationException(message);
  }

  public static ImageBuffer solid(final int width, final int height, final int argb) {
    checkDimensions(width, height);

    final int[] pixels = new int[width * height];
    Arrays.fill(pixels, argb);
    return ImageBuffer.buffer(pixels, width, height);
  }

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

  // Creates an image whose pixel at index i has the opaque color i, so every pixel is unique and its original position
  // can be read from its color.
  public static ImageBuffer indexed(final int width, final int height) {
    final int[] pixels = indexedPixels(width, height);
    return ImageBuffer.buffer(pixels, width, height);
  }

  public static int[] indexedPixels(final int width, final int height) {
    checkDimensions(width, height);

    final int count = width * height;
    final int[] pixels = new int[count];
    for (int index = 0; index < count; index++) {
      pixels[index] = OPAQUE | index;
    }

    return pixels;
  }

  public static byte[] encode(final BufferedImage image, final String format) {
    Preconditions.checkNotNull(image, "Image must not be null!");
    Preconditions.checkNotNull(format, "Format must not be null!");

    try (final ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      final boolean written = ImageIO.write(image, format, output);

      Preconditions.checkArgument(written, "Format %s cannot be written by ImageIO!", format);

      return output.toByteArray();
    } catch (final IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  public static byte[] animatedGif(final int width, final int height, final int delayInHundredths, final int... colors) {
    checkDimensions(width, height);
    Preconditions.checkNotNull(colors, "Colors must not be null!");

    final Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName(GIF_FORMAT_NAME);
    final ImageWriter writer = writers.next();
    try (final ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      writeGifSequence(writer, output, width, height, delayInHundredths, colors);

      return output.toByteArray();
    } catch (final IOException exception) {
      throw new UncheckedIOException(exception);
    } finally {
      writer.dispose();
    }
  }

  private static void writeGifSequence(
    final ImageWriter writer,
    final ByteArrayOutputStream output,
    final int width,
    final int height,
    final int delayInHundredths,
    final int[] colors
  ) throws IOException {
    try (final ImageOutputStream imageOutput = ImageIO.createImageOutputStream(output)) {
      writer.setOutput(imageOutput);
      writer.prepareWriteSequence(null);
      for (final int color : colors) {
        writeGifFrame(writer, width, height, delayInHundredths, color);
      }

      writer.endWriteSequence();
    }
  }

  private static void checkDimensions(final int width, final int height) {
    Preconditions.checkArgument(width > 0, "Width %s must be positive!", width);
    Preconditions.checkArgument(height > 0, "Height %s must be positive!", height);
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
    for (int row = 0; row < height; row++) {
      for (int column = 0; column < width; column++) {
        frame.setRGB(column, row, color);
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
    control.setAttribute("userInputFlag", GIF_FALSE);
    control.setAttribute("transparentColorFlag", GIF_FALSE);
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
