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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import me.brandonli.mcav.bukkit.BukkitException;
import me.brandonli.mcav.bukkit.testing.UtilityClassAssertions;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.error.FilterLiteDither;
import me.brandonli.mcav.utils.IoUtils;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.junit.jupiter.api.Test;

final class BlockPaletteLookupTest {

  private static final int BROWN_STAINED_GLASS = (102 << 16) | (76 << 8) | 51;
  private static final int NOT_IN_PALETTE = 0x010203;

  private static final int OPAQUE_ALPHA = 0xFF000000;

  private static final String PALETTE_RESOURCE = "mcav/bukkit/blocks.json";

  private static final String STONE_MATERIAL_NAME = "STONE";
  private static final String STONE_BLOCK_NAME = "stone";

  private static final int SECOND_ENTRY_COLOR = 0x040506;

  private static final String CLOSE_FAILURE_MESSAGE = "Close failed!";

  @Test
  void shouldFindTheBlockOfAPaletteColorAndIgnoreTheAlphaChannel() {
    final Material material = BlockPaletteLookup.getMaterial(BROWN_STAINED_GLASS);
    final Material opaque = BlockPaletteLookup.getMaterial(OPAQUE_ALPHA | BROWN_STAINED_GLASS);
    final Material unknown = BlockPaletteLookup.getMaterial(NOT_IN_PALETTE);

    assertEquals(Material.BROWN_STAINED_GLASS, material);
    assertEquals(Material.BROWN_STAINED_GLASS, opaque);
    assertEquals(Material.AIR, unknown);
  }

  @Test
  void shouldShareTheBlockDataOfAPaletteColor() {
    final BlockData data = BlockPaletteLookup.getBlockData(0x7F000000 | BROWN_STAINED_GLASS);
    final BlockData sameData = BlockPaletteLookup.getBlockData(BROWN_STAINED_GLASS);
    final BlockData unknown = BlockPaletteLookup.getBlockData(NOT_IN_PALETTE);
    final BlockData otherUnknown = BlockPaletteLookup.getBlockData(NOT_IN_PALETTE + 1);

    final Material material = data.getMaterial();
    final Material unknownMaterial = unknown.getMaterial();
    assertEquals(Material.BROWN_STAINED_GLASS, material);
    assertSame(data, sameData);
    assertEquals(Material.AIR, unknownMaterial);
    assertSame(unknown, otherUnknown);
  }

  @Test
  void shouldResolveEveryBlockOfTheBundledPalette() {
    final Reader reader = IoUtils.getResourceAsStreamReader(PALETTE_RESOURCE);
    final Map<String, int[]> palette = BlockPaletteLookup.parsePalette(reader);
    final Set<Map.Entry<String, int[]>> entries = palette.entrySet();

    for (final Map.Entry<String, int[]> entry : entries) {
      final String name = entry.getKey();
      final int[] rgb = entry.getValue();
      final int color = (rgb[0] << 16) | (rgb[1] << 8) | rgb[2];
      final Material expected = Material.matchMaterial(name);
      final Material material = BlockPaletteLookup.getMaterial(color);
      assertEquals(expected, material, name);
    }
  }

  @Test
  void shouldDitherImagesToColorsOfThePalette() {
    final FilterLiteDither dithering = BlockPaletteLookup.getDither();
    final FilterLiteDither sameDithering = BlockPaletteLookup.getDither();
    final Random random = new Random(7);
    final int[] pixels = new int[64];
    for (int index = 0; index < pixels.length; index++) {
      pixels[index] = OPAQUE_ALPHA | random.nextInt(0x1000000);
    }

    dithering.dither(pixels, 8);

    assertSame(dithering, sameDithering);

    for (final int pixel : pixels) {
      final Material material = BlockPaletteLookup.getMaterial(pixel);
      assertNotEquals(Material.AIR, material, "every dithered color belongs to a block");
    }
  }

  @Test
  void shouldKeepTheTablesWhenInitializingAgain() {
    final FilterLiteDither before = BlockPaletteLookup.getDither();

    BlockPaletteLookup.initialize();
    BlockPaletteLookup.initialize();

    final FilterLiteDither after = BlockPaletteLookup.getDither();
    assertSame(before, after);
  }

  @Test
  void shouldParsePalettesInTheirOriginalOrderAndCloseTheReader() throws IOException {
    try (final TrackingReader reader = new TrackingReader("{\"STONE\": [1, 2, 3], \"DIRT\": [4, 5, 6]}")) {
      final Map<String, int[]> palette = BlockPaletteLookup.parsePalette(reader);

      final Set<String> keys = palette.keySet();
      final List<String> names = List.copyOf(keys);
      final int[] dirt = palette.get("DIRT");
      final int blue = dirt[2];
      final List<String> expected = List.of(STONE_MATERIAL_NAME, "DIRT");
      assertEquals(expected, names);
      assertEquals(6, blue);

      final boolean readerClosed = reader.closed;
      assertTrue(readerClosed);
    }
  }

  @Test
  void shouldRejectEmptyPalettes() {
    final StringReader empty = new StringReader("{}");
    final StringReader nothing = new StringReader("null");

    assertThrows(BukkitException.class, () -> BlockPaletteLookup.parsePalette(empty));
    assertThrows(BukkitException.class, () -> BlockPaletteLookup.parsePalette(nothing));
  }

  @Test
  void shouldReportPalettesThatCannotBeRead() throws IOException {
    try (final TrackingReader reader = new TrackingReader("{\"STONE\": [1, 2, 3]}")) {
      reader.failOnFirstClose = true;

      final BukkitException exception = assertThrows(BukkitException.class, () -> BlockPaletteLookup.parsePalette(reader));

      final Throwable cause = exception.getCause();
      final String message = exception.getMessage();
      final String expected = "Block palette resource %s could not be read!".formatted(PALETTE_RESOURCE);
      assertInstanceOf(IOException.class, cause);
      assertEquals(expected, message);
    }
  }

  @Test
  void shouldSkipNamesThatAreUnknownOrNotBlocks() {
    final Map<String, int[]> palette = new LinkedHashMap<>();
    palette.put(STONE_BLOCK_NAME, new int[] { 1, 2, 3 });
    palette.put("no_such_block", new int[] { 4, 5, 6 });
    palette.put("diamond", new int[] { 7, 8, 9 });

    final Map<Integer, Material> materials = BlockPaletteLookup.resolveMaterials(palette);

    final Map<Integer, Material> expected = Map.of(0x010203, Material.STONE);
    assertEquals(expected, materials);
  }

  @Test
  void shouldSkipEntriesWithMalformedColors() {
    final Map<String, int[]> palette = new LinkedHashMap<>();
    palette.put(STONE_BLOCK_NAME, new int[] { 1, 2 });
    palette.put("dirt", null);
    palette.put("cobblestone", new int[0]);
    palette.put("oak_planks", new int[] { 4, 5, 6 });

    final Map<Integer, Material> materials = BlockPaletteLookup.resolveMaterials(palette);

    final Map<Integer, Material> expected = Map.of(SECOND_ENTRY_COLOR, Material.OAK_PLANKS);
    assertEquals(expected, materials, "malformed entries never break the initialization");
  }

  @Test
  void shouldParseMissingColorsAsNull() {
    final StringReader reader = new StringReader("{\"STONE\": null, \"DIRT\": [4, 5, 6]}");

    final Map<String, int[]> palette = BlockPaletteLookup.parsePalette(reader);
    final Map<Integer, Material> materials = BlockPaletteLookup.resolveMaterials(palette);

    final boolean hasStone = palette.containsKey(STONE_MATERIAL_NAME);
    final int[] stone = palette.get(STONE_MATERIAL_NAME);
    final Map<Integer, Material> expected = Map.of(SECOND_ENTRY_COLOR, Material.DIRT);
    assertTrue(hasStone);
    assertNull(stone);
    assertEquals(expected, materials);
  }

  @Test
  void shouldNotBeInstantiable() {
    UtilityClassAssertions.assertNotInstantiable(BlockPaletteLookup.class);
  }

  private static final class TrackingReader extends Reader {

    private final StringReader content;

    private boolean closed;
    private boolean failOnFirstClose;

    TrackingReader(final String content) {
      this.content = new StringReader(content);
    }

    @Override
    public int read(final char @NonNull [] buffer, final int offset, final int length) throws IOException {
      return this.content.read(buffer, offset, length);
    }

    @Override
    public void close() throws IOException {
      final boolean firstClose = !this.closed;
      this.content.close();
      this.closed = true;

      if (firstClose && this.failOnFirstClose) {
        throw new IOException(CLOSE_FAILURE_MESSAGE);
      }
    }
  }
}
