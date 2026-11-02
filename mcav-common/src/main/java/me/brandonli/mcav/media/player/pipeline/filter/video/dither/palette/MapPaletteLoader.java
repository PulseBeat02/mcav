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
package me.brandonli.mcav.media.player.pipeline.filter.video.dither.palette;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import java.awt.Color;
import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Type;
import me.brandonli.mcav.json.GsonProvider;
import me.brandonli.mcav.utils.FileOperationException;
import me.brandonli.mcav.utils.IoUtils;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Loads the Minecraft map colors from the {@code mcav/common/palette.json} resource, which lists the RGB components of every
 * map color in index order. The resource is generated from the game and has to be updated when Minecraft adds
 * map colors.
 *
 * <p>This class is stateless and thread-safe.
 *
 * @since 1.0.0
 */
public final class MapPaletteLoader {

  private static final String PALETTE_RESOURCE = "mcav/common/palette.json";
  private static final int OPAQUE = 0xFF << 24;
  private static final int RED_SHIFT = 16;
  private static final int GREEN_SHIFT = 8;

  private static final int COMPONENTS = 3;
  private static final int RED_COMPONENT = 0;
  private static final int GREEN_COMPONENT = 1;
  private static final int BLUE_COMPONENT = 2;
  private static final int COMPONENT_MAXIMUM = 255;

  private static final int UNSIGNED_BYTE_MASK = 0xFF;
  private static final int[] COLORS = loadColors(PALETTE_RESOURCE);

  private MapPaletteLoader() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated!");
  }

  @VisibleForTesting
  static int[] loadColors(final String resource) {
    try (final Reader reader = IoUtils.getResourceAsStreamReader(resource)) {
      return parseColors(reader);
    } catch (final IOException | FileOperationException exception) {
      final String message = "Map palette resource %s could not be read!".formatted(resource);
      throw new PaletteLoadingException(message, exception);
    }
  }

  @VisibleForTesting
  static int[] parseColors(final Reader reader) {
    final Gson gson = GsonProvider.getSimple();
    final TypeToken<int[][]> typeToken = new TypeToken<>() {};
    final Type type = typeToken.getType();
    final int[][] components;
    try {
      components = gson.fromJson(reader, type);
    } catch (final JsonSyntaxException | IllegalArgumentException exception) {
      // Gson reads an empty element of an int array as a null and reports it with an IllegalArgumentException
      throw new PaletteLoadingException("Map palette resource is malformed!", exception);
    }

    if (components == null || components.length == 0) {
      throw new PaletteLoadingException("Map palette resource is empty!");
    }

    final int[] colors = new int[components.length];
    for (int index = 0; index < components.length; index++) {
      final int[] rgb = components[index];
      colors[index] = toColor(rgb, index);
    }

    return colors;
  }

  private static int toColor(final int @Nullable [] rgb, final int index) {
    if (rgb == null || rgb.length != COMPONENTS) {
      final String message = "Map color %s does not have three components!".formatted(index);
      throw new PaletteLoadingException(message);
    }

    final int red = checkComponent(rgb[RED_COMPONENT], index);
    final int green = checkComponent(rgb[GREEN_COMPONENT], index);
    final int blue = checkComponent(rgb[BLUE_COMPONENT], index);
    return OPAQUE | (red << RED_SHIFT) | (green << GREEN_SHIFT) | blue;
  }

  private static int checkComponent(final int component, final int index) {
    if (component < 0 || component > COMPONENT_MAXIMUM) {
      final String message = "Component %s of map color %s must be between 0 and 255 inclusive!".formatted(component, index);
      throw new PaletteLoadingException(message);
    }

    return component;
  }

  /**
   * Returns every map color as opaque ARGB, indexed by map color index.
   *
   * @return a copy of the map colors
   * @since 1.0.0
   */
  public static int[] getColors() {
    return COLORS.clone();
  }

  /**
   * Returns the number of map colors.
   *
   * @return the color count
   * @since 1.0.0
   */
  public static int getColorCount() {
    return COLORS.length;
  }

  /**
   * Returns a map color by its index.
   *
   * @param index the map color index, as stored in map data; negative bytes are treated as unsigned
   * @return the color
   * @throws IllegalArgumentException if the index does not exist
   * @since 1.0.0
   */
  public static Color getColor(final byte index) {
    final int unsigned = index & UNSIGNED_BYTE_MASK;
    Preconditions.checkArgument(unsigned < COLORS.length, "Map color %s does not exist!", unsigned);

    final int argb = COLORS[unsigned];
    return new Color(argb, true);
  }
}
