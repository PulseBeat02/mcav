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

import static java.util.Objects.requireNonNull;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.awt.*;
import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.function.Function;
import java.util.stream.Stream;
import me.brandonli.mcav.json.GsonProvider;
import me.brandonli.mcav.utils.IOUtils;

/**
 * The MapPalette class provides a palette that maps byte values to {@link Color} objects,
 * allowing for efficient retrieval of pre-defined colors. It loads its palette from a JSON
 * resource file located in the application resources.
 * <p>
 * This class is immutable and thread-safe.
 */
public final class MapPaletteLoader {

  private static final String PALETTE_PATH = "palette.json";

  public static final Color[] NMS_PALETTE;

  static {
    final Gson gson = GsonProvider.getSimple();
    try (final Reader reader = IOUtils.getResourceAsStreamReader(PALETTE_PATH)) {
      final TypeToken<int[][]> token = new TypeToken<>() {};
      final Type type = token.getType();
      final int[][] colors = requireNonNull(gson.fromJson(reader, type));
      for (final int[] color : colors) {
        if (color == null || color.length != 3) {
          final String colorStr = Arrays.toString(color);
          throw new PaletteLoadingException(String.format("Invalid color: %s", colorStr));
        }
      }
      NMS_PALETTE = Stream.of(colors).map(createColor()).toArray(Color[]::new);
    } catch (final IOException e) {
      throw new PaletteLoadingException(e.getMessage(), e);
    }
  }

  private static Function<int[], Color> createColor() {
    return color -> new Color(color[0], color[1], color[2]);
  }

  /**
   * Retrieves the {@link Color} associated with the specified palette index.
   *
   * @param val the byte value representing the index in the color palette. This value
   *            corresponds to a specific predefined color in the palette.
   * @return the {@link Color} object mapped to the given byte index from the palette.
   * If the index is out of range, an {@link ArrayIndexOutOfBoundsException} will
   * be thrown.
   */
  public static Color getColor(final byte val) {
    return NMS_PALETTE[val];
  }
}
