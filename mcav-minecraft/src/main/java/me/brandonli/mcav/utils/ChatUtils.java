/*
 * This file is part of mcav, a media playback library for Minecraft
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
package me.brandonli.mcav.utils;

import static net.kyori.adventure.text.Component.text;
import static net.kyori.adventure.text.format.TextColor.color;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;

/**
 * Utility class for creating and manipulating chat components with color-coded text representations.
 * This class is designed to generate components by processing data arrays and applying coloring
 * based on pixel values. It is optimized for creating visual output suitable for chat environments.
 * <p>
 * This is a non-instantiable utility class.
 */
public final class ChatUtils {

  private ChatUtils() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Creates a single line of color-coded text components based on pixel values from an array.
   * Each character in the line is styled with a color corresponding to the RGB value at its position.
   *
   * @param data      an array of integers representing RGB values for pixels in the image
   * @param character the character to use for building the line
   * @param width     the total width of the line in pixels
   * @param y         the vertical index or row in the data array to process
   * @return a {@code Component} object representing the constructed line with color-coded text
   */
  public static Component createLine(final int[] data, final String character, final int width, final int y) {
    final TextComponent.Builder builder = text();
    int before = -1;
    for (int x = 0; x < width; ++x) {
      final int rgb = data[width * y + x];
      if (before != rgb) {
        builder.append(text(character).color(color(rgb)));
        before = rgb;
      } else {
        builder.append(text(character));
      }
    }
    return builder.build();
  }

  /**
   * Generates a chat component by mapping an array of RGB pixel data to text characters with
   * appropriate colors. This method is used to create a visual representation that resembles
   * an image, using text in a chat context.
   *
   * @param data      an array of integers representing pixel data in RGB format, where each value
   *                  corresponds to a color.
   * @param character the character to use for visual representation of each pixel.
   * @param width     the width of the "image" to be represented in characters.
   * @param height    the height of the "image" to be represented in characters.
   * @return a {@link Component} representing the constructed colored
   * chat component.
   */
  public static Component createChatComponent(final int[] data, final String character, final int width, final int height) {
    final TextComponent.Builder builder = text();
    int before = -1;
    for (int y = 0; y < height; ++y) {
      for (int x = 0; x < width; ++x) {
        final int rgb = data[width * y + x];
        if (before != rgb) {
          builder.append(text(character).color(color(rgb)));
          before = rgb;
        } else {
          builder.append(text(character));
        }
      }
    }
    return builder.build();
  }
}
