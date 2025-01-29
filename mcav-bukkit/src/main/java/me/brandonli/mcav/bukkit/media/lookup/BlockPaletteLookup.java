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
package me.brandonli.mcav.bukkit.media.lookup;

import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import me.brandonli.mcav.json.GsonProvider;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.error.FilterLiteDither;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.palette.Palette;
import me.brandonli.mcav.utils.IOUtils;
import me.brandonli.mcav.utils.UncheckedIOException;
import org.bukkit.Material;
import org.checkerframework.checker.nullness.qual.KeyFor;

/**
 * A utility class that provides easy ways to convert from colors to blocks and vice versa.
 */
public final class BlockPaletteLookup {

  private BlockPaletteLookup() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  private static final Map<Integer, Material> MATERIAL_LOOKUP;
  private static final FilterLiteDither DITHERING_IMPL;

  static {
    final Map<String, int[]> blockPalette = getBlockPalette();
    DITHERING_IMPL = getDitheringImpl(blockPalette);
    MATERIAL_LOOKUP = getMaterialPalette(blockPalette);
  }

  private static FilterLiteDither getDitheringImpl(final Map<String, int[]> blockPalette) {
    final int[] colors = blockPalette.values().stream().mapToInt(val -> (val[0] << 16) | (val[1] << 8) | val[2]).toArray();
    final Palette palette = Palette.colors(colors);
    return new FilterLiteDither(palette);
  }

  private static Map<Integer, Material> getMaterialPalette(final Map<String, int[]> blockPalette) {
    final Map<Integer, Material> map = new HashMap<>();
    final Set<Map.Entry<@KeyFor("blockPalette") String, int[]>> entries = blockPalette.entrySet();
    for (final Map.Entry<String, int[]> entry : entries) {
      final String key = entry.getKey();
      final int[] rgb = entry.getValue();
      final int color = (rgb[0] << 16) | (rgb[1] << 8) | rgb[2];
      final Material material = Material.matchMaterial(key);
      if (material != null) {
        map.put(color, material);
      }
    }
    return map;
  }

  /**
   * Creates all lookup tables and initializes the block palette.
   */
  public static void init() {
    // init
  }

  private static Map<String, int[]> getBlockPalette() {
    final Gson gson = GsonProvider.getSimple();
    try (final Reader stream = IOUtils.getResourceAsStreamReader("blocks.json")) {
      final Type type = new TypeToken<Map<String, int[]>>() {}.getType();
      return gson.fromJson(stream, type);
    } catch (final IOException e) {
      throw new UncheckedIOException(e.getMessage(), e);
    }
  }

  /**
   * Gets the closest {@link Material} for the given RGB color.
   *
   * @param color the RGB color (BGR24 format)
   * @return the closest {@link Material} for the given RGB color, or {@link Material#AIR} if no match is found
   */
  public static Material getMaterial(final int color) {
    return MATERIAL_LOOKUP.getOrDefault(color, Material.AIR);
  }

  /**
   * Gets the corresponding dithering implementation for the block palette.
   *
   * @return the dithering implementation for the block palette
   */
  public static FilterLiteDither getDitheringImpl() {
    return DITHERING_IMPL;
  }
}
