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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import org.junit.jupiter.api.Tag;

@Tag("fuzz")
final class StripAnchorFuzzTest {

  private static final int LAST_SYMBOL = 63;
  private static final int LAST_FACING = 3;
  private static final int LAST_STREAM = 4095;

  private static final int HEADER_COLOURS = 16;

  @FuzzTest(maxDuration = "30s")
  void shouldReadEveryAcceptedAnchorWithinItsFields(final FuzzedDataProvider data) {
    final byte[] colours = this.anchor(data);
    final float cornerX = data.consumeFloat();
    final float cornerY = data.consumeFloat();
    final float cornerZ = data.consumeFloat();
    final boolean accepted = Mcv2Maps.isAnchor(colours);
    if (!accepted) {
      return;
    }

    final StripAnchor anchor = StripAnchor.of(colours, cornerX, cornerY, cornerZ);
    this.assertFields(anchor);
    this.assertCorner(anchor, cornerX, cornerY, cornerZ);
  }

  // an anchor of any fields, with bits of its header flipped
  private byte[] anchor(final FuzzedDataProvider data) {
    final int column = data.consumeInt(0, LAST_SYMBOL);
    final int row = data.consumeInt(0, LAST_SYMBOL);
    final int columns = data.consumeInt(0, LAST_SYMBOL);
    final int rows = data.consumeInt(0, LAST_SYMBOL);
    final int facing = data.consumeInt(0, LAST_SYMBOL);
    final int stream = data.consumeInt(0, LAST_STREAM);
    final int[] symbols = MapFixtures.anchorSymbols(column, row, columns, rows, facing, stream);
    final byte[] masks = data.consumeBytes(HEADER_COLOURS);
    final byte[] colours = MapFixtures.colours(symbols);
    return MapFixtures.withFlippedBits(colours, masks);
  }

  private void assertFields(final StripAnchor anchor) {
    final int column = anchor.getColumn();
    final int row = anchor.getRow();
    final int columns = anchor.getColumns();
    final int rows = anchor.getRows();
    final int facing = anchor.getFacing();
    final long stream = anchor.getStream();
    this.assertSymbol(column);
    this.assertSymbol(row);
    this.assertSymbol(columns);
    this.assertSymbol(rows);
    assertTrue(facing >= 0 && facing <= LAST_FACING);
    assertTrue(stream >= 0 && stream <= LAST_STREAM);
  }

  private void assertSymbol(final int symbol) {
    assertTrue(symbol >= 0 && symbol <= LAST_SYMBOL);
  }

  private void assertCorner(final StripAnchor anchor, final float cornerX, final float cornerY, final float cornerZ) {
    final float readX = anchor.getCornerX();
    final float readY = anchor.getCornerY();
    final float readZ = anchor.getCornerZ();
    assertEquals(cornerX, readX);
    assertEquals(cornerY, readY);
    assertEquals(cornerZ, readZ);
  }
}
