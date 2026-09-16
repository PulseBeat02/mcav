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
import java.io.UncheckedIOException;
import java.lang.reflect.Type;
import me.brandonli.mcav.json.GsonProvider;
import me.brandonli.mcav.utils.IOUtils;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Loads the Minecraft map colors from the {@code palette.json} resource, which lists the RGB components of every
 * map color in index order. The resource is generated from the game and has to be updated when Minecraft adds
 * map colors.
 */
public final class MapPaletteLoader {

  private static final String PALETTE_RESOURCE = "palette.json";
  private static final int OPAQUE = 0xFF << 24;
  private static final int[] COLORS = loadColors(PALETTE_RESOURCE);

  private MapPaletteLoader() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Loads the map colors from a JSON resource with one {@code [red, green, blue]} array per color.
   *
   * @param resource the path of the resource, relative to the classpath root
   * @return the colors as opaque packed ARGB
   * @throws PaletteLoadingException if the resource is missing or does not describe a palette
   */
  @VisibleForTesting
  static int[] loadColors(final String resource) {
    try (final Reader reader = IOUtils.getResourceAsStreamReader(resource)) {
      return parseColors(reader);
    } catch (final IOException | UncheckedIOException exception) {
      final String message = exception.getMessage();
      throw new PaletteLoadingException(message, exception);
    }
  }

  /**
   * Parses map colors from JSON with one {@code [red, green, blue]} array per color.
   *
   * @param reader the JSON
   * @return the colors as opaque packed ARGB
   * @throws PaletteLoadingException if the JSON does not describe a palette, for example because a color does not
   *                                 have three components from 0 to 255
   */
  @VisibleForTesting
  static int[] parseColors(final Reader reader) {
    final Gson gson = GsonProvider.getSimple();
    final TypeToken<int[][]> typeToken = new TypeToken<>() {};
    final Type type = typeToken.getType();
    final int[][] components;
    try {
      components = gson.fromJson(reader, type);
    } catch (final JsonSyntaxException exception) {
      final String message = exception.getMessage();
      throw new PaletteLoadingException(message, exception);
    }
    if (components == null || components.length == 0) {
      throw new PaletteLoadingException("Map palette resource is empty");
    }
    final int[] colors = new int[components.length];
    for (int index = 0; index < components.length; index++) {
      final int[] rgb = components[index];
      colors[index] = toColor(rgb, index);
    }
    return colors;
  }

  private static int toColor(final int@Nullable[] rgb, final int index) {
    if (rgb == null || rgb.length != 3) {
      final String message = "Map color %d does not have three components".formatted(index);
      throw new PaletteLoadingException(message);
    }
    final int red = checkComponent(rgb[0], index);
    final int green = checkComponent(rgb[1], index);
    final int blue = checkComponent(rgb[2], index);
    return OPAQUE | (red << 16) | (green << 8) | blue;
  }

  private static int checkComponent(final int component, final int index) {
    if (component < 0 || component > 255) {
      final String message = "Map color %d has the component %d, which is outside of 0 to 255".formatted(index, component);
      throw new PaletteLoadingException(message);
    }
    return component;
  }

  /**
   * Gets every map color as opaque ARGB, indexed by map color index.
   *
   * @return a copy of the map colors
   */
  public static int[] getColors() {
    return COLORS.clone();
  }

  /**
   * Gets the number of map colors.
   *
   * @return the color count
   */
  public static int getColorCount() {
    return COLORS.length;
  }

  /**
   * Gets a map color by its index.
   *
   * @param index the map color index, as stored in map data; negative bytes are treated as unsigned
   * @return the color
   * @throws IllegalArgumentException if the index does not exist
   */
  public static Color getColor(final byte index) {
    final int unsigned = index & 0xFF;
    Preconditions.checkArgument(unsigned < COLORS.length, "Map color %s does not exist", unsigned);
    final int argb = COLORS[unsigned];
    return new Color(argb, true);
  }
}
