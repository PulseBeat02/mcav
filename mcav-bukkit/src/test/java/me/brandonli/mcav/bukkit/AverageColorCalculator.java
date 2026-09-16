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
package me.brandonli.mcav.bukkit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.awt.image.BufferedImage;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import javax.imageio.ImageIO;

/**
 * Regenerates {@code blocks.json}, the average color of every block texture, from the textures in
 * {@code src/test/resources/colored-blocks}. Run it from the root of the repository.
 */
public final class AverageColorCalculator {

  private static final Path TEXTURES = Path.of("mcav-bukkit/src/test/resources/colored-blocks");
  private static final Path OUTPUT = Path.of("mcav-bukkit/src/main/resources/blocks.json");
  private static final String PNG_EXTENSION = ".png";
  private static final int RED_INDEX = 0;
  private static final int GREEN_INDEX = 1;
  private static final int BLUE_INDEX = 2;
  private static final int COUNT_INDEX = 3;

  private AverageColorCalculator() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Writes the average color of every block texture to {@code blocks.json}.
   *
   * @throws IOException if a texture cannot be read or the output cannot be written
   */
  static void main() throws IOException {
    final Map<String, int[]> blockColors = calculateBlockColors();
    final GsonBuilder builder = new GsonBuilder();
    builder.setPrettyPrinting();
    final Gson gson = builder.create();
    try (final BufferedWriter writer = Files.newBufferedWriter(OUTPUT)) {
      gson.toJson(blockColors, writer);
      writer.write('\n');
    }
  }

  private static Map<String, int[]> calculateBlockColors() throws IOException {
    final Map<String, int[]> colors = new TreeMap<>();
    try (final DirectoryStream<Path> textures = Files.newDirectoryStream(TEXTURES, "*" + PNG_EXTENSION)) {
      for (final Path texture : textures) {
        final Path fileNamePath = texture.getFileName();
        final String fileName = String.valueOf(fileNamePath);
        final int nameLength = fileName.length() - PNG_EXTENSION.length();
        final String blockName = fileName.substring(0, nameLength);
        final String materialName = blockName.toUpperCase(Locale.ROOT);
        final File textureFile = texture.toFile();
        final BufferedImage image = ImageIO.read(textureFile);
        if (image == null) {
          continue;
        }
        final int[] averageColor = calculateAverageColor(image);
        colors.put(materialName, averageColor);
      }
    }
    return colors;
  }

  private static int[] calculateAverageColor(final BufferedImage image) {
    final long[] sums = sumOpaquePixels(image);
    final long opaquePixels = sums[COUNT_INDEX];
    if (opaquePixels == 0) {
      return new int[] { 0, 0, 0 };
    }

    final int averageRed = (int) (sums[RED_INDEX] / opaquePixels);
    final int averageGreen = (int) (sums[GREEN_INDEX] / opaquePixels);
    final int averageBlue = (int) (sums[BLUE_INDEX] / opaquePixels);
    return new int[] { averageRed, averageGreen, averageBlue };
  }

  /**
   * Sums the red, green, and blue channels of every pixel that is not fully transparent, and counts those pixels.
   */
  private static long[] sumOpaquePixels(final BufferedImage image) {
    final long[] sums = new long[4];
    final int width = image.getWidth();
    final int height = image.getHeight();
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        final int argb = image.getRGB(x, y);
        final int alpha = (argb >>> 24) & 0xFF;
        if (alpha == 0) {
          continue;
        }
        sums[RED_INDEX] += (argb >> 16) & 0xFF;
        sums[GREEN_INDEX] += (argb >> 8) & 0xFF;
        sums[BLUE_INDEX] += argb & 0xFF;
        sums[COUNT_INDEX]++;
      }
    }
    return sums;
  }
}
