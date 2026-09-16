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
package me.brandonli.mcav.bukkit.media.lookup;

import com.google.common.annotations.VisibleForTesting;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import me.brandonli.mcav.json.GsonProvider;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.error.FilterLiteDither;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.palette.DitherPalette;
import me.brandonli.mcav.utils.IOUtils;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Converts between colors and blocks.
 *
 * <p>The block palette is loaded from the {@code blocks.json} resource, which maps the names of full blocks to
 * their average texture color. Most of them are solid, but a few are translucent, such as stained glass. Images are
 * first dithered to the colors of that palette with {@link #getDitheringImpl()}, and every resulting color is then
 * converted back into its block with {@link #getMaterial(int)} or {@link #getBlockData(int)}.
 *
 * <p>The palette is loaded lazily the first time it is needed, which takes a moment because the color lookup
 * tables have to be built. Call {@link #init()} during startup to do that work ahead of time. All methods are
 * thread-safe.
 */
public final class BlockPaletteLookup {

  private static final String PALETTE_RESOURCE = "blocks.json";
  private static final int RGB_MASK = 0xFFFFFF;
  private static final int RGB_COMPONENTS = 3;

  private BlockPaletteLookup() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Loads the block palette and builds all lookup tables. Calling this method is optional, but it avoids a delay
   * the first time a block display is used.
   */
  public static void init() {
    Holder.initialize();
  }

  /**
   * Gets the block whose average color is the given palette color.
   *
   * @param color the RGB color, which should be one of the colors produced by {@link #getDitheringImpl()}; the
   *              alpha channel is ignored
   * @return the block with the given average color, or {@link Material#AIR} if no block has that color
   */
  public static Material getMaterial(final int color) {
    final int rgb = color & RGB_MASK;
    final Material material = Holder.MATERIALS.get(rgb);
    if (material == null) {
      return Material.AIR;
    }
    return material;
  }

  /**
   * Gets the default block data of the block whose average color is the given palette color. The block data is
   * cached and shared, so it must not be modified.
   *
   * @param color the RGB color, which should be one of the colors produced by {@link #getDitheringImpl()}; the
   *              alpha channel is ignored
   * @return the block data with the given average color, or air if no block has that color
   */
  public static BlockData getBlockData(final int color) {
    final int rgb = color & RGB_MASK;
    final BlockData data = Holder.BLOCK_DATA.get(rgb);
    if (data == null) {
      return Holder.AIR;
    }
    return data;
  }

  /**
   * Gets the dithering algorithm that reduces images to the colors of the block palette.
   *
   * @return the dithering algorithm for the block palette
   */
  public static FilterLiteDither getDitheringImpl() {
    return Holder.DITHERING;
  }

  /**
   * Holds the lookup tables. The tables are built when this class is first accessed, which the JVM guarantees to
   * happen exactly once and in a thread-safe way.
   */
  private static final class Holder {

    private static final Map<Integer, Material> MATERIALS;
    private static final Map<Integer, BlockData> BLOCK_DATA;
    private static final FilterLiteDither DITHERING;
    private static final BlockData AIR;

    static {
      final Reader reader = IOUtils.getResourceAsStreamReader(PALETTE_RESOURCE);
      final Map<String, int@Nullable[]> palette = parsePalette(reader);
      final Map<Integer, Material> materials = resolveMaterials(palette);

      MATERIALS = materials;
      BLOCK_DATA = createBlockData(materials);
      AIR = Material.AIR.createBlockData();
      DITHERING = createDithering(materials);
    }

    private static void initialize() {
      // calling any static method triggers the class initialization above
    }

    private static Map<Integer, BlockData> createBlockData(final Map<Integer, Material> materials) {
      final Map<Integer, BlockData> blockData = new HashMap<>();
      for (final Map.Entry<Integer, Material> entry : materials.entrySet()) {
        final Integer color = entry.getKey();
        final Material material = entry.getValue();
        final BlockData data = material.createBlockData();
        blockData.put(color, data);
      }
      return Map.copyOf(blockData);
    }

    private static FilterLiteDither createDithering(final Map<Integer, Material> materials) {
      final Set<Integer> colorSet = materials.keySet();
      final int colorCount = colorSet.size();
      final int[] colors = new int[colorCount];
      int index = 0;
      for (final Integer color : colorSet) {
        colors[index] = color;
        index++;
      }

      final DitherPalette ditherPalette = DitherPalette.colors(colors);
      return new FilterLiteDither(ditherPalette);
    }
  }

  /**
   * Reads a block palette, which maps block names to their average color as an array of red, green, and blue.
   * The reader is closed afterward.
   *
   * @param reader the reader of the palette JSON
   * @return the palette in the order of the JSON object, whose colors are null where the JSON has {@code null}
   * @throws UncheckedIOException if the palette is empty or cannot be read
   */
  @VisibleForTesting
  static Map<String, int@Nullable[]> parsePalette(final Reader reader) {
    final Gson gson = GsonProvider.getSimple();
    final TypeToken<LinkedHashMap<String, int[]>> typeToken = new TypeToken<>() {};
    final Type type = typeToken.getType();
    try (reader) {
      final Map<String, int@Nullable[]> palette = gson.fromJson(reader, type);
      if (palette == null || palette.isEmpty()) {
        final String message = "Block palette resource is empty";
        final IOException emptyPalette = new IOException(message);
        throw new UncheckedIOException(message, emptyPalette);
      }
      return palette;
    } catch (final IOException exception) {
      final String message = exception.getMessage();
      throw new UncheckedIOException(message, exception);
    }
  }

  /**
   * Resolves the blocks of a palette. Names that are unknown or do not name a block are skipped, and so are
   * malformed entries whose color is missing or has fewer than three components, so a broken palette can never
   * break the initialization of the lookup tables.
   *
   * @param palette the palette, mapping block names to their average color as an array of red, green, and blue
   * @return an immutable map from the RGB color to its block
   */
  @VisibleForTesting
  static Map<Integer, Material> resolveMaterials(final Map<String, int@Nullable[]> palette) {
    final Map<Integer, Material> materials = new HashMap<>();
    for (final Map.Entry<String, int@Nullable[]> entry : palette.entrySet()) {
      final String name = entry.getKey();
      final int@Nullable[] rgb = entry.getValue();
      if (rgb == null || rgb.length < RGB_COMPONENTS) {
        continue;
      }

      final Material material = Material.matchMaterial(name);
      if (material == null || !material.isBlock()) {
        continue;
      }

      final int red = rgb[0];
      final int green = rgb[1];
      final int blue = rgb[2];
      final int color = (red << 16) | (green << 8) | blue;
      materials.put(color, material);
    }
    return Map.copyOf(materials);
  }
}
