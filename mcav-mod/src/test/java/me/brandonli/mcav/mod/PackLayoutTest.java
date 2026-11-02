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

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class PackLayoutTest {

  private static final int WIDTH = 854;

  private static final String STREAMS = "7u, 9u";
  private static final String SLOTS = "3, 2";
  private static final String FIRST_SLOTS = "0, 3";

  // a layout as the server generates it
  static String packConstants(final String streams, final String slots, final String firsts, final int screens) {
    return String.join(
      "\n",
      "#ifndef MCAV_MCV2_CONFIG_GLSL",
      "#define MCAV_MCV2_CONFIG_GLSL",
      "const int MCV2_SCREENS = " + screens + ";",
      "const int MCV2_TOTAL_SLOTS = 5;",
      "const uint MCV2_SCREEN_STREAMS[" + screens + "] = uint[" + screens + "](" + streams + ");",
      "const int MCV2_SCREEN_SLOTS[" + screens + "] = int[" + screens + "](" + slots + ");",
      "const int MCV2_SCREEN_FIRST_SLOTS[" + screens + "] = int[" + screens + "](" + firsts + ");",
      "const bool MCV2_DEBUG_VIEW = false;",
      "const ivec3 MCV2_OUTLINE_COLOR = ivec3(1, 2, 3);",
      "#endif",
      ""
    );
  }

  @Test
  void shouldReadTheScreensTheServerGenerated() {
    final PackLayout layout = PackLayout.parse(packConstants("7u, 4294967295u", SLOTS, FIRST_SLOTS, 2)).orElseThrow();
    final int screens = layout.getScreenCount();
    final int firstScreenSlots = layout.getSlotCount(0);
    final int secondScreenSlots = layout.getSlotCount(1);
    final int firstScreenFirstSlot = layout.getFirstSlot(0);
    final int secondScreenFirstSlot = layout.getFirstSlot(1);
    final int totalSlots = layout.getTotalSlots();
    final int screenOfSeven = layout.findScreen(7);
    final int screenOfTheLargestStream = layout.findScreen(4_294_967_295L);
    final int screenOfAnUnknownStream = layout.findScreen(8);
    final int stripRows = layout.getStripRows(WIDTH);
    assertEquals(2, screens);
    assertEquals(3, firstScreenSlots);
    assertEquals(2, secondScreenSlots);
    assertEquals(0, firstScreenFirstSlot);
    assertEquals(3, secondScreenFirstSlot);
    assertEquals(5, totalSlots);
    assertEquals(0, screenOfSeven);
    assertEquals(1, screenOfTheLargestStream);
    assertEquals(PackLayout.NO_SCREEN, screenOfAnUnknownStream);
    assertEquals(27, stripRows);
  }

  @Test
  void shouldReadOneScreenOfOneSlotAndEightScreensOfEightSlots() {
    final PackLayout one = PackLayout.parse(packConstants("0u", "1", "0", 1)).orElseThrow();
    final int screensOfOne = one.getScreenCount();
    final int totalSlotsOfOne = one.getTotalSlots();
    assertEquals(1, screensOfOne);
    assertEquals(1, totalSlotsOfOne);
    final PackLayout eight = PackLayout.parse(
      packConstants("1u, 2u, 3u, 4u, 5u, 6u, 7u, 8u", "8, 8, 8, 8, 8, 8, 8, 8", "0, 8, 16, 24, 32, 40, 48, 56", 8)
    ).orElseThrow();
    final int screensOfEight = eight.getScreenCount();
    final int firstSlotOfTheLastScreen = eight.getFirstSlot(7);
    final int totalSlotsOfEight = eight.getTotalSlots();
    assertEquals(8, screensOfEight);
    assertEquals(56, firstSlotOfTheLastScreen);
    assertEquals(64, totalSlotsOfEight);
  }

  @Test
  void shouldGiveAPageTheRowsItsFourThousandPixelsNeed() {
    final int rowsAtWidthOne = PackLayout.getRowsPerPage(1);
    final int rowsAtScreenWidth = PackLayout.getRowsPerPage(WIDTH);
    final int rowsAtPageWidth = PackLayout.getRowsPerPage(4096);
    final int rowsAbovePageWidth = PackLayout.getRowsPerPage(4097);
    final int rowsBelowPageWidth = PackLayout.getRowsPerPage(4095);
    assertEquals(4096, rowsAtWidthOne);
    assertEquals(5, rowsAtScreenWidth);
    assertEquals(1, rowsAtPageWidth);
    assertEquals(1, rowsAbovePageWidth);
    assertEquals(2, rowsBelowPageWidth);
  }

  @Test
  void shouldFindNoLayoutInAnythingTheServerCouldNotHaveGenerated() {
    final String valid = packConstants(STREAMS, SLOTS, FIRST_SLOTS, 2);
    for (final String packConstants : List.of(
      "",
      valid.replace("MCV2_SCREENS = 2;", "MCV2_SCREENS = 0;"),
      valid.replace("MCV2_SCREENS = 2;", "MCV2_SCREENS = 9;"),
      valid.replace("MCV2_SCREENS = 2;", "MCV2_SCREENS = 102;"),
      valid.replace("MCV2_SCREENS = 2;", "MCV2_SCREENS = 1;"),
      valid.replace("uint MCV2_SCREEN_STREAMS", "uint MCV2_SCREEN_STREAM"),
      valid.replace(STREAMS, "7u"),
      valid.replace(STREAMS, "7, 9u"),
      valid.replace(STREAMS, "7u, 4294967296u"),
      valid.replace(STREAMS, "7u, 12345678901u"),
      valid.replace("int[2](3, 2)", "int[2](9, 2)"),
      valid.replace("int[2](3, 2)", "int[2](3, 0)"),
      valid.replace("int[2](3, 2)", "int[2](3, -2)"),
      valid.replace("int MCV2_SCREEN_SLOTS", "int MCV2_SCREEN_SLOT"),
      valid.replace("int[2](0, 3)", "int[2](0, 2)"),
      valid.replace("int[2](0, 3)", "int[2](1, 3)"),
      valid.replace("int[2](0, 3)", "int[2](0, 65)"),
      valid.replace("int MCV2_SCREEN_FIRST_SLOTS", "int MCV2_SCREEN_FIRST")
    )) {
      final Optional<PackLayout> layout = PackLayout.parse(packConstants);
      assertTrue(layout.isEmpty(), packConstants);
    }
    final Optional<PackLayout> validLayout = PackLayout.parse(valid);
    assertTrue(validLayout.isPresent());
  }

  @Test
  void shouldCompareLayoutsByEveryPart() {
    final PackLayout layout = this.parseLayout(STREAMS, SLOTS, FIRST_SLOTS);
    EqualityAssertions.assertEqualityContract(
      layout,
      this.parseLayout(STREAMS, SLOTS, FIRST_SLOTS),
      this.parseLayout("7u, 8u", SLOTS, FIRST_SLOTS),
      this.parseLayout(STREAMS, "3, 1", FIRST_SLOTS),
      this.parseLayout(STREAMS, "2, 3", "0, 2")
    );
  }

  @Test
  void shouldHashLayoutsByEveryPart() {
    final PackLayout layout = this.parseLayout(STREAMS, SLOTS, FIRST_SLOTS);

    final int hash = layout.hashCode();
    final int streamsHash = Arrays.hashCode(new long[] { 7L, 9L });
    final int slotsHash = Arrays.hashCode(new int[] { 3, 2 });
    final int firstSlotsHash = Arrays.hashCode(new int[] { 0, 3 });
    final int expected = Objects.hash(streamsHash, slotsHash, firstSlotsHash);
    assertEquals(expected, hash);
  }

  @Test
  void shouldDescribeItsScreens() {
    final PackLayout layout = this.parseLayout(STREAMS, SLOTS, FIRST_SLOTS);

    final String description = layout.toString();
    assertEquals("PackLayout{streams=[7, 9], slots=[3, 2], firstSlots=[0, 3]}", description);
  }

  private PackLayout parseLayout(final String streams, final String slots, final String firstSlots) {
    final String packConstants = packConstants(streams, slots, firstSlots, 2);
    final Optional<PackLayout> layout = PackLayout.parse(packConstants);
    return layout.orElseThrow();
  }
}
