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
package me.brandonli.mcav.plugin.utils;

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

final class ArgumentUtilsPropertyTest {

  private static final String SEED = "20260925";
  // the protocol's limit of packets in one bundle, which the largest wall is chosen to fit
  private static final int BUNDLE_LIMIT = 4096;

  private static final String DIMENSION_SEPARATOR = "x";

  @Provide
  Arbitrary<String> typedSizes() {
    // every arbitrary starts from a fresh base: jqwik 1.10 shares settings between an arbitrary and those made from it
    final StringArbitrary sizeStrings = Arbitraries.strings();
    final StringArbitrary sizeCharacters = sizeStrings.withChars("0123456789x+-X ,.");
    final StringArbitrary shortSizes = sizeCharacters.ofMaxLength(24);
    final StringArbitrary textStrings = Arbitraries.strings();
    final StringArbitrary anyText = textStrings.ofMaxLength(40);
    final IntegerArbitrary integers = Arbitraries.integers();
    final Arbitrary<String> numbers = integers.map(String::valueOf);
    final Arbitrary<String> pairs = numbers.flatMap(width -> numbers.map(height -> width + DIMENSION_SEPARATOR + height));
    final Arbitrary<Integer> nearWalls = this.nearBound(ArgumentUtils.MAXIMUM_SCREEN_SIDE);
    final Arbitrary<Integer> nearSides = this.nearBound(ArgumentUtils.MAXIMUM_SIDE);
    final Arbitrary<Integer> near = Arbitraries.oneOf(nearWalls, nearSides);
    final Arbitrary<String> nearPairs = near.flatMap(width -> near.map(height -> width + DIMENSION_SEPARATOR + height));
    final int wall = ArgumentUtils.MAXIMUM_SCREEN_SIDE;
    final int side = ArgumentUtils.MAXIMUM_SIDE;
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
    final Arbitrary<String> boundPairs = bounds.flatMap(width -> bounds.map(height -> width + DIMENSION_SEPARATOR + height));
    return Arbitraries.oneOf(shortSizes, anyText, pairs, nearPairs, boundPairs);
  }

  // a fresh arbitrary: jqwik 1.10 shares ranges between an arbitrary and those configured from it
  private Arbitrary<Integer> nearBound(final int bound) {
    final IntegerArbitrary integers = Arbitraries.integers();
    return integers.between(0, 3 * bound);
  }

  @Property(seed = SEED)
  void shouldKeepEveryTypedSizeWithinTheBoundsOrRefuseIt(@ForAll("typedSizes") final String typed) {
    this.assertBoundedOrRefused(typed, ArgumentUtils::parseDimensions, ArgumentUtils.MAXIMUM_SIDE);
  }

  @Property(seed = SEED)
  void shouldKeepEveryTypedWallWithinTheBoundsOrRefuseIt(@ForAll("typedSizes") final String typed) {
    this.assertBoundedOrRefused(typed, ArgumentUtils::parseScreenDimensions, ArgumentUtils.MAXIMUM_SCREEN_SIDE);
  }

  private void assertBoundedOrRefused(final String typed, final Function<String, Pair<Integer, Integer>> parser, final int maximum) {
    final Pair<Integer, Integer> size;
    try {
      size = parser.apply(typed);
    } catch (final IllegalArgumentException exception) {
      return;
    }
    final int width = size.getFirst();
    final int height = size.getSecond();
    final boolean widthInBounds = width >= 1 && width <= maximum;
    final boolean heightInBounds = height >= 1 && height <= maximum;
    assertTrue(widthInBounds && heightInBounds, () -> "'%s' became %sx%s".formatted(typed, width, height));
  }

  @Property(seed = SEED)
  void shouldReadEveryWellFormedSizeBackExactly(
    @ForAll @IntRange(min = 1, max = ArgumentUtils.MAXIMUM_SIDE) final int width,
    @ForAll @IntRange(min = 1, max = ArgumentUtils.MAXIMUM_SIDE) final int height
  ) {
    final String typed = width + DIMENSION_SEPARATOR + height;
    final Pair<Integer, Integer> size = ArgumentUtils.parseDimensions(typed);
    final int parsedWidth = size.getFirst();
    final int parsedHeight = size.getSecond();

    assertEquals(width, parsedWidth);
    assertEquals(height, parsedHeight);
  }

  @Property(seed = SEED)
  void shouldRefuseEverySideBeyondTheBound(
    @ForAll @IntRange(min = ArgumentUtils.MAXIMUM_SIDE + 1) final int tooLarge,
    @ForAll @IntRange(min = 1, max = ArgumentUtils.MAXIMUM_SIDE) final int fine
  ) {
    final String tooWide = tooLarge + DIMENSION_SEPARATOR + fine;
    final String tooHigh = fine + DIMENSION_SEPARATOR + tooLarge;

    assertThrows(IllegalArgumentException.class, () -> ArgumentUtils.parseDimensions(tooWide));
    assertThrows(IllegalArgumentException.class, () -> ArgumentUtils.parseDimensions(tooHigh));
  }

  // a release clears the wall at once, without playback's byte budget, so the burst must fit one bundle
  @Property(seed = SEED)
  void shouldFitTheClearOfEveryAcceptedWallInOneBundle(@ForAll("typedSizes") final String typed) {
    final Pair<Integer, Integer> size;
    try {
      size = ArgumentUtils.parseScreenDimensions(typed);
    } catch (final IllegalArgumentException exception) {
      return;
    }
    final int width = size.getFirst();
    final int height = size.getSecond();
    final long maps = (long) width * height;

    assertTrue(maps <= BUNDLE_LIMIT, () -> "%sx%s maps do not fit one bundle".formatted(width, height));
  }
}
