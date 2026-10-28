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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

final class StripAnchorTest {

  private static final int COLUMN = 4;
  private static final int ROW = 5;

  private static final int COLUMNS = 6;
  private static final int ROWS = 7;

  private static final int FACING = 2;

  private static final long STREAM = 9L;

  private static final float CORNER_X = 1.5F;
  private static final float CORNER_Y = -2.25F;
  private static final float CORNER_Z = 3.75F;

  @Test
  void shouldReadItsFieldsAndTheTwelveBitStreamFromTheSymbolsAfterTheSignature() {
    final int stream = (21 << 6) | 42;
    final byte[] colours = MapFixtures.colours(MapFixtures.anchorSymbols(5, 6, 7, 8, 2, stream));
    final StripAnchor anchor = StripAnchor.of(colours, 1.5F, -2F, 3.25F);
    assertEquals(StripAnchor.of(5, 6, 7, 8, 2, stream, 1.5F, -2F, 3.25F), anchor);
  }

  @Test
  void shouldReturnItsParts() {
    final StripAnchor anchor = this.anchor();

    final int column = anchor.getColumn();
    final int row = anchor.getRow();
    assertEquals(COLUMN, column);
    assertEquals(ROW, row);

    final int columns = anchor.getColumns();
    final int rows = anchor.getRows();
    assertEquals(COLUMNS, columns);
    assertEquals(ROWS, rows);

    final int facing = anchor.getFacing();
    assertEquals(FACING, facing);

    final long stream = anchor.getStream();
    assertEquals(STREAM, stream);

    final float cornerX = anchor.getCornerX();
    final float cornerY = anchor.getCornerY();
    final float cornerZ = anchor.getCornerZ();
    assertEquals(CORNER_X, cornerX);
    assertEquals(CORNER_Y, cornerY);
    assertEquals(CORNER_Z, cornerZ);
  }

  @Test
  void shouldEqualAnAnchorOfTheSameParts() {
    final StripAnchor anchor = this.anchor();
    final StripAnchor same = this.anchor();
    final Object itself = anchor;

    final boolean equalToItself = anchor.equals(itself);
    final boolean equalToSame = anchor.equals(same);
    assertTrue(equalToItself);
    assertTrue(equalToSame);

    final int hash = anchor.hashCode();
    final int sameHash = same.hashCode();
    assertEquals(hash, sameHash);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("anchorsOfOneOtherPart")
  void shouldDifferFromAnAnchorOfOneOtherPart(final StripAnchor other) {
    final StripAnchor anchor = this.anchor();
    assertNotEquals(anchor, other);

    final int hash = anchor.hashCode();
    final int otherHash = other.hashCode();
    assertNotEquals(hash, otherHash);
  }

  private static Stream<Named<StripAnchor>> anchorsOfOneOtherPart() {
    return Stream.of(
      Named.of("column", StripAnchor.of(COLUMN + 1, ROW, COLUMNS, ROWS, FACING, STREAM, CORNER_X, CORNER_Y, CORNER_Z)),
      Named.of("row", StripAnchor.of(COLUMN, ROW + 1, COLUMNS, ROWS, FACING, STREAM, CORNER_X, CORNER_Y, CORNER_Z)),
      Named.of("columns", StripAnchor.of(COLUMN, ROW, COLUMNS + 1, ROWS, FACING, STREAM, CORNER_X, CORNER_Y, CORNER_Z)),
      Named.of("rows", StripAnchor.of(COLUMN, ROW, COLUMNS, ROWS + 1, FACING, STREAM, CORNER_X, CORNER_Y, CORNER_Z)),
      Named.of("facing", StripAnchor.of(COLUMN, ROW, COLUMNS, ROWS, FACING + 1, STREAM, CORNER_X, CORNER_Y, CORNER_Z)),
      Named.of("stream", StripAnchor.of(COLUMN, ROW, COLUMNS, ROWS, FACING, STREAM + 1, CORNER_X, CORNER_Y, CORNER_Z)),
      Named.of("cornerX", StripAnchor.of(COLUMN, ROW, COLUMNS, ROWS, FACING, STREAM, CORNER_X + 1, CORNER_Y, CORNER_Z)),
      Named.of("cornerY", StripAnchor.of(COLUMN, ROW, COLUMNS, ROWS, FACING, STREAM, CORNER_X, CORNER_Y + 1, CORNER_Z)),
      Named.of("cornerZ", StripAnchor.of(COLUMN, ROW, COLUMNS, ROWS, FACING, STREAM, CORNER_X, CORNER_Y, CORNER_Z + 1))
    );
  }

  @Test
  void shouldDifferFromAnObjectOfAnotherType() {
    final StripAnchor anchor = this.anchor();
    final Object notAnAnchor = STREAM;
    assertNotEquals(anchor, notAnAnchor);
  }

  @Test
  void shouldDescribeItsParts() {
    final StripAnchor anchor = this.anchor();

    final String description = anchor.toString();
    assertEquals(
      "StripAnchor{column=4, row=5, columns=6, rows=7, facing=2, stream=9, cornerX=1.5, cornerY=-2.25, cornerZ=3.75}",
      description
    );
  }

  private StripAnchor anchor() {
    return StripAnchor.of(COLUMN, ROW, COLUMNS, ROWS, FACING, STREAM, CORNER_X, CORNER_Y, CORNER_Z);
  }
}
