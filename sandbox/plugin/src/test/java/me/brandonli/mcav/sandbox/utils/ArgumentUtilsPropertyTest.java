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
package me.brandonli.mcav.sandbox.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.Function;
import me.brandonli.mcav.utils.immutable.Pair;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.arbitraries.IntegerArbitrary;
import net.jqwik.api.arbitraries.StringArbitrary;
import net.jqwik.api.constraints.IntRange;

/**
 * Properties of the size arguments of the commands: every text a player can type either yields a size within the
 * documented bounds or is refused with an {@link IllegalArgumentException} the command turns into a message. Pass 1
 * and pass 4 found the sizes unbounded, so that {@code /mcav screen 100000x100000} froze the main thread; these state
 * the bounds for every input rather than the handful the examples try.
 */
final class ArgumentUtilsPropertyTest {

  private static final String SEED = "20260925";
  // the protocol's limit of packets in one bundle, which the largest wall is chosen to fit
  private static final int BUNDLE_LIMIT = 4096;
  private static final long MAP_PACKET_BYTES = 128 * 128 + 16;

  @Provide
  Arbitrary<String> typedSizes() {
    // every arbitrary starts from a fresh base: jqwik 1.9 shares settings between an arbitrary and those made from it
    final StringArbitrary sizeStrings = Arbitraries.strings();
    final StringArbitrary sizeCharacters = sizeStrings.withChars("0123456789x+-X ,.");
    final StringArbitrary shortSizes = sizeCharacters.ofMaxLength(24);
    final StringArbitrary textStrings = Arbitraries.strings();
    final StringArbitrary anyText = textStrings.ofMaxLength(40);
    final IntegerArbitrary integers = Arbitraries.integers();
    final Arbitrary<String> numbers = integers.map(String::valueOf);
    final Arbitrary<String> pairs = numbers.flatMap(width -> numbers.map(height -> width + "x" + height));
    // sizes around both bounds, where an off-by-one or a loosened limit shows
    final Arbitrary<Integer> nearWalls = nearBound(ArgumentUtils.MAX_SCREEN_SIDE);
    final Arbitrary<Integer> nearSides = nearBound(ArgumentUtils.MAX_SIDE);
    final Arbitrary<Integer> near = Arbitraries.oneOf(nearWalls, nearSides);
    final Arbitrary<String> nearPairs = near.flatMap(width -> near.map(height -> width + "x" + height));
    final int wall = ArgumentUtils.MAX_SCREEN_SIDE;
    final int side = ArgumentUtils.MAX_SIDE;
    final Arbitrary<Integer> bounds = Arbitraries.of(
      0,
      1,
      wall - 1,
      wall,
      wall + 1,
      2 * wall - 1,
      2 * wall,
      2 * wall + 1,
      side - 1,
      side,
      side + 1
    );
    final Arbitrary<String> boundPairs = bounds.flatMap(width -> bounds.map(height -> width + "x" + height));
    return Arbitraries.oneOf(shortSizes, anyText, pairs, nearPairs, boundPairs);
  }

  /**
   * Creates sides from nothing to three times a bound, from a fresh arbitrary: jqwik 1.9 shares the range between an
   * arbitrary and the ones configured from it.
   */
  private static Arbitrary<Integer> nearBound(final int bound) {
    final IntegerArbitrary integers = Arbitraries.integers();
    return integers.between(0, 3 * bound);
  }

  @Property(seed = SEED)
  void everyTypedSizeIsWithinTheBoundsOrRefused(@ForAll("typedSizes") final String typed) {
    assertBoundedOrRefused(typed, ArgumentUtils::parseDimensions, ArgumentUtils.MAX_SIDE);
  }

  @Property(seed = SEED)
  void everyTypedWallIsWithinTheBoundsOrRefused(@ForAll("typedSizes") final String typed) {
    assertBoundedOrRefused(typed, ArgumentUtils::parseScreenDimensions, ArgumentUtils.MAX_SCREEN_SIDE);
  }

  private static void assertBoundedOrRefused(final String typed, final Function<String, Pair<Integer, Integer>> parser, final int maximum) {
    final Pair<Integer, Integer> size;
    try {
      size = parser.apply(typed);
    } catch (final IllegalArgumentException refused) {
      return;
    }
    final int width = size.getFirst();
    final int height = size.getSecond();
    final boolean widthInBounds = width >= 1 && width <= maximum;
    final boolean heightInBounds = height >= 1 && height <= maximum;
    assertTrue(widthInBounds && heightInBounds, () -> "'" + typed + "' became " + width + "x" + height);
  }

  @Property(seed = SEED)
  void readsEveryWellFormedSizeBackExactly(
    @ForAll @IntRange(min = 1, max = ArgumentUtils.MAX_SIDE) final int width,
    @ForAll @IntRange(min = 1, max = ArgumentUtils.MAX_SIDE) final int height
  ) {
    final String typed = width + "x" + height;
    final Pair<Integer, Integer> size = ArgumentUtils.parseDimensions(typed);
    final int parsedWidth = size.getFirst();
    final int parsedHeight = size.getSecond();

    assertEquals(width, parsedWidth);
    assertEquals(height, parsedHeight);
  }

  @Property(seed = SEED)
  void refusesEverySideBeyondTheBound(
    @ForAll @IntRange(min = ArgumentUtils.MAX_SIDE + 1) final int tooLarge,
    @ForAll @IntRange(min = 1, max = ArgumentUtils.MAX_SIDE) final int fine
  ) {
    final String tooWide = tooLarge + "x" + fine;
    final String tooHigh = fine + "x" + tooLarge;

    assertThrows(IllegalArgumentException.class, () -> ArgumentUtils.parseDimensions(tooWide));
    assertThrows(IllegalArgumentException.class, () -> ArgumentUtils.parseDimensions(tooHigh));
  }

  /**
   * A wall is cleared when its video is released, all at once and without the byte budget of playback. The live pass
   * measured that burst; this bounds it for every wall a player can ask for: it fits the one bundle a client applies
   * in a single tick, and it is at most 4096 map packets of 16400 bytes.
   */
  @Property(seed = SEED)
  void theClearOfEveryAcceptedWallFitsOneBundle(@ForAll("typedSizes") final String typed) {
    final Pair<Integer, Integer> size;
    try {
      size = ArgumentUtils.parseScreenDimensions(typed);
    } catch (final IllegalArgumentException refused) {
      return;
    }
    final int width = size.getFirst();
    final int height = size.getSecond();
    final long maps = (long) width * height;
    final long clearBytes = maps * MAP_PACKET_BYTES;

    assertTrue(maps <= BUNDLE_LIMIT, () -> width + "x" + height + " maps do not fit one bundle");
    assertTrue(clearBytes <= BUNDLE_LIMIT * MAP_PACKET_BYTES, () -> "clearing " + width + "x" + height + " sends " + clearBytes + " bytes");
  }
}
