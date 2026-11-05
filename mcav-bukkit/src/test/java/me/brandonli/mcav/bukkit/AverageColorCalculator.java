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

// regenerates mcav/bukkit/blocks.json from src/test/resources/colored-blocks; run it from the repository root
final class AverageColorCalculator {

  private static final Path TEXTURES = Path.of("mcav-bukkit/src/test/resources/colored-blocks");
  private static final Path OUTPUT = Path.of("mcav-bukkit/src/main/resources/mcav/bukkit/blocks.json");
  private static final String PNG_EXTENSION = ".png";
  private static final String PNG_GLOB = "*.png";

  private static final char LINE_SEPARATOR = '\n';

  private static final int RED_INDEX = 0;
  private static final int GREEN_INDEX = 1;
  private static final int BLUE_INDEX = 2;
  private static final int COUNT_INDEX = 3;

  private static final int ALPHA_SHIFT = 24;
  private static final int RED_SHIFT = 16;
  private static final int GREEN_SHIFT = 8;

  private static final int CHANNEL_MASK = 0xFF;

  private AverageColorCalculator() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated!");
  }

  static void main() throws IOException {
    final Map<String, int[]> blockColors = calculateBlockColors();
    final GsonBuilder builder = new GsonBuilder();
    builder.setPrettyPrinting();
    final Gson gson = builder.create();
    try (final BufferedWriter writer = Files.newBufferedWriter(OUTPUT)) {
      gson.toJson(blockColors, writer);
      writer.write(LINE_SEPARATOR);
    }
  }

  private static Map<String, int[]> calculateBlockColors() throws IOException {
    final Map<String, int[]> colors = new TreeMap<>();
    try (final DirectoryStream<Path> textures = Files.newDirectoryStream(TEXTURES, PNG_GLOB)) {
      for (final Path texture : textures) {
        putAverageColor(texture, colors);
      }
    }

    return colors;
  }

  private static void putAverageColor(final Path texture, final Map<String, int[]> colors) throws IOException {
    final Path fileNamePath = texture.getFileName();
    final String fileName = String.valueOf(fileNamePath);
    final int nameLength = fileName.length() - PNG_EXTENSION.length();
    final String blockName = fileName.substring(0, nameLength);
    final String materialName = blockName.toUpperCase(Locale.ROOT);
    final File textureFile = texture.toFile();
    final BufferedImage image = ImageIO.read(textureFile);
    if (image == null) {
      return;
    }

    final int[] averageColor = calculateAverageColor(image);
    colors.put(materialName, averageColor);
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

  private static long[] sumOpaquePixels(final BufferedImage image) {
    final long[] sums = new long[COUNT_INDEX + 1];
    final int width = image.getWidth();
    final int height = image.getHeight();
    for (int row = 0; row < height; row++) {
      for (int column = 0; column < width; column++) {
        final int argb = image.getRGB(column, row);
        addOpaquePixel(sums, argb);
      }
    }

    return sums;
  }

  private static void addOpaquePixel(final long[] sums, final int argb) {
    final int alpha = (argb >>> ALPHA_SHIFT) & CHANNEL_MASK;
    if (alpha == 0) {
      return;
    }

    sums[RED_INDEX] += (argb >> RED_SHIFT) & CHANNEL_MASK;
    sums[GREEN_INDEX] += (argb >> GREEN_SHIFT) & CHANNEL_MASK;
    sums[BLUE_INDEX] += argb & CHANNEL_MASK;
    sums[COUNT_INDEX]++;
  }
}
