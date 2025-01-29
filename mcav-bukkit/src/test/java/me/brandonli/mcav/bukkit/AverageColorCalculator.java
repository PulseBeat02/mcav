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

import com.google.common.collect.Streams;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.awt.image.BufferedImage;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;

@SuppressWarnings("all")
public final class AverageColorCalculator {

  public static void main(final String[] args) throws IOException {
    final Path json = Path.of("mcav-bukkit/src/main/resources/blocks.json");
    final Map<String, int[]> blockColors = getBlockColors();
    final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    try (final BufferedWriter writer = Files.newBufferedWriter(json)) {
      gson.toJson(blockColors, writer);
    }
  }

  private static final Map<String, int[]> getBlockColors() throws IOException {
    final Path directory = Path.of("mcav-bukkit/src/test/resources/colored-blocks");
    final List<Path> pngFiles = new ArrayList<>();
    try (final DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
      return Streams.stream(stream)
        .parallel()
        .map(AverageColorCalculator::processImage)
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
  }

  private static Map.Entry<String, int[]> processImage(final Path pngFile) {
    try {
      final File file = pngFile.toFile();
      final String fileName = file.getName();
      final int length = fileName.length();
      final String blockName = fileName.substring(0, length - 4);
      final BufferedImage image = ImageIO.read(file);
      final int[] averageColor = calculateAverageColor(image);
      final String entry = blockName.toUpperCase(Locale.ROOT);
      return Map.entry(entry, averageColor);
    } catch (final IOException e) {
      throw new AssertionError(e);
    }
  }

  private static int[] calculateAverageColor(final BufferedImage image) {
    long sumRed = 0;
    long sumGreen = 0;
    long sumBlue = 0;
    int totalPixels = 0;
    final int width = image.getWidth();
    final int height = image.getHeight();
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        final int rgb = image.getRGB(x, y);
        final int alpha = (rgb >> 24) & 0xFF;
        if (alpha == 0) {
          continue;
        }
        final int red = (rgb >> 16) & 0xFF;
        final int green = (rgb >> 8) & 0xFF;
        final int blue = rgb & 0xFF;
        sumRed += red;
        sumGreen += green;
        sumBlue += blue;
        totalPixels++;
      }
    }
    int avgRed = 0;
    int avgGreen = 0;
    int avgBlue = 0;
    if (totalPixels > 0) {
      avgRed = (int) (sumRed / totalPixels);
      avgGreen = (int) (sumGreen / totalPixels);
      avgBlue = (int) (sumBlue / totalPixels);
    }
    final int[] result = new int[3];
    result[0] = avgRed;
    result[1] = avgGreen;
    result[2] = avgBlue;
    return result;
  }
}
