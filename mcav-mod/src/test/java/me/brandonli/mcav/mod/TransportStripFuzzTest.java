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
package me.brandonli.mcav.mod;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Tag;

@Tag("fuzz")
final class TransportStripFuzzTest {

  private static final PackLayout LAYOUT = PackLayout.parse(PackLayoutTest.packConstants("7u, 9u", "3, 2", "0, 3", 2)).orElseThrow();

  private static final int CHANNELS = 4;

  private static final int SYMBOL_BITS = 6;
  private static final int LAST_SYMBOL = 63;
  private static final int MOST_BITS = 31;
  private static final int LAST_BIT = Mcv2Maps.COLOURS * SYMBOL_BITS - MOST_BITS;

  private static final int WIDEST = 7680;
  private static final int MOST_MAPS = 4;
  private static final long LARGEST_STREAM_TRIED = 15;
  private static final int LAST_NUMBER_TRIED = 3;
  private static final int LAST_FACING = 3;
  private static final int CHANGED_COLOURS = 32;
  private static final int MATRIX_FLOATS = 16;

  @FuzzTest(maxDuration = "30s")
  void shouldReadBackTheBitsWrittenAnywhere(final FuzzedDataProvider data) {
    final int bit = data.consumeInt(0, LAST_BIT);
    final int count = data.consumeInt(0, MOST_BITS);
    final long value = data.consumeLong(0, (1L << count) - 1);
    final int background = data.consumeInt(0, LAST_SYMBOL);
    final int[] symbols = new int[Mcv2Maps.COLOURS];
    Arrays.fill(symbols, background);
    MapFixtures.writeBits(symbols, bit, count, value);
    final byte[] colours = MapFixtures.colours(symbols);

    final int read = TransportStrip.bits(colours, bit, count);
    assertEquals(value, read);
  }

  @FuzzTest(maxDuration = "30s")
  void shouldBuildAStripOfTheLayoutsSize(final FuzzedDataProvider data) {
    final int width = data.consumeInt(TransportStrip.DESCRIPTOR_PIXELS, WIDEST);
    final List<byte[]> pages = this.pages(data);
    final List<StripAnchor> anchors = this.anchors(data);
    final float[] viewRotation = this.matrix(data);
    final float[] projection = this.matrix(data);

    final byte[] strip = TransportStrip.build(LAYOUT, width, pages, anchors, viewRotation, projection);
    final int expected = LAYOUT.stripRows(width) * width * CHANNELS;
    assertEquals(expected, strip.length);
  }

  private List<byte[]> pages(final FuzzedDataProvider data) {
    final int count = data.consumeInt(0, MOST_MAPS);
    final List<byte[]> pages = new ArrayList<>();
    for (int index = 0; index < count; index++) {
      final byte[] page = this.page(data);
      pages.add(page);
    }

    return pages;
  }

  // a page of any stream and number, with bits of its header flipped
  private byte[] page(final FuzzedDataProvider data) {
    final long stream = data.consumeLong(0, LARGEST_STREAM_TRIED);
    final int number = data.consumeInt(0, LAST_NUMBER_TRIED);
    final int[] symbols = MapFixtures.pageSymbols(stream, number);
    final byte[] masks = data.consumeBytes(CHANGED_COLOURS);
    final byte[] colours = MapFixtures.colours(symbols);
    return MapFixtures.withFlippedBits(colours, masks);
  }

  private List<StripAnchor> anchors(final FuzzedDataProvider data) {
    final int count = data.consumeInt(0, MOST_MAPS);
    final List<StripAnchor> anchors = new ArrayList<>();
    for (int index = 0; index < count; index++) {
      final StripAnchor anchor = this.anchor(data);
      anchors.add(anchor);
    }

    return anchors;
  }

  private StripAnchor anchor(final FuzzedDataProvider data) {
    final int column = data.consumeInt(0, LAST_SYMBOL);
    final int row = data.consumeInt(0, LAST_SYMBOL);
    final int columns = data.consumeInt(0, LAST_SYMBOL);
    final int rows = data.consumeInt(0, LAST_SYMBOL);
    final int facing = data.consumeInt(0, LAST_FACING);
    final long stream = data.consumeLong(0, LARGEST_STREAM_TRIED);
    final float cornerX = data.consumeFloat();
    final float cornerY = data.consumeFloat();
    final float cornerZ = data.consumeFloat();
    return StripAnchor.of(column, row, columns, rows, facing, stream, cornerX, cornerY, cornerZ);
  }

  private float[] matrix(final FuzzedDataProvider data) {
    final float[] matrix = new float[MATRIX_FLOATS];
    for (int index = 0; index < matrix.length; index++) {
      matrix[index] = data.consumeFloat();
    }

    return matrix;
  }
}
