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

import static java.util.Objects.requireNonNull;

import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Where the pack's screens keep their pages and anchors in the transport strip, read from the constants the server
 * generates into the pack ({@code mcav:shaders/include/mcv2_config.glsl}): the screens own consecutive runs of page
 * slots, a page fills {@link #rowsPerPage} rows of the strip, and after the last slot comes one descriptor row per screen.
 */
final class PackLayout {

  /** The pixels of a page in the strip: four six-bit symbols make the three bytes of one pixel. */
  static final int PAGE_PIXELS = 4096;

  /** What {@link #screenOf} answers for a stream the pack has no screen of. */
  static final int NO_SCREEN = -1;

  /** What the server generates: 1 to 8 screens of 1 to 8 page slots each, in consecutive runs. */
  private static final int MAXIMUM_SCREENS = 8;
  private static final int MAXIMUM_SLOTS = 8;
  private static final long MAXIMUM_STREAM = 0xFFFF_FFFFL;

  private static final Pattern SCREENS = Pattern.compile("const int MCV2_SCREENS = (\\d{1,2});");

  private static final int COUNT_GROUP = 1;

  private static final String STREAMS = "uint MCV2_SCREEN_STREAMS";
  private static final String SLOTS = "int MCV2_SCREEN_SLOTS";
  private static final String FIRST_SLOTS = "int MCV2_SCREEN_FIRST_SLOTS";

  /** An element of a list: digits, with the unsigned suffix in a uint list; ten digits hold any uint. */
  private static final String UNSIGNED = "(\\d{1,10})u";
  private static final String SIGNED = "(\\d{1,10})";

  private static final String SEPARATOR = ", ";

  private static final String DECLARATION_START = "const ";
  private static final String LENGTH_START = "\\[";
  private static final String LENGTH_END = "]";
  private static final String CONSTRUCTOR = " = \\w+";
  private static final String ELEMENTS_START = "\\(";
  private static final String ELEMENTS_END = "\\);";

  private final long[] streams;

  private final int[] slots;

  private final int[] firstSlots;

  /**
   * Reads the layout the server generated. The pack comes from the server, so anything but a layout the server could
   * have generated is no layout at all.
   *
   * @param packConstants the text of the pack's {@code mcv2_config.glsl}
   * @return the layout, or empty when the text does not hold one
   */
  static Optional<PackLayout> parse(final String packConstants) {
    final Matcher screens = SCREENS.matcher(packConstants);
    if (!screens.find()) {
      return Optional.empty();
    }

    final String countText = requireNonNull(screens.group(COUNT_GROUP)); // checker framework
    final int count = Integer.parseInt(countText);
    if (count < 1 || count > MAXIMUM_SCREENS) {
      return Optional.empty();
    }

    final long @Nullable [] streams = numbers(packConstants, STREAMS, count, UNSIGNED, MAXIMUM_STREAM);
    final long @Nullable [] slots = numbers(packConstants, SLOTS, count, SIGNED, MAXIMUM_SLOTS);
    final long @Nullable [] firstSlots = numbers(packConstants, FIRST_SLOTS, count, SIGNED, MAXIMUM_SCREENS * MAXIMUM_SLOTS);
    if (streams == null || slots == null || firstSlots == null) {
      return Optional.empty();
    }

    final int[] slotCounts = toIntegers(slots);
    final int[] firsts = toIntegers(firstSlots);
    if (!areSlotsBackToBack(slotCounts, firsts)) {
      return Optional.empty();
    }

    return Optional.of(new PackLayout(streams, slotCounts, firsts));
  }

  private static int[] toIntegers(final long[] values) {
    return Arrays.stream(values).mapToInt(Math::toIntExact).toArray();
  }

  private static boolean areSlotsBackToBack(final int[] slotCounts, final int[] firstSlots) {
    int total = 0;
    for (int screen = 0; screen < slotCounts.length; screen++) {
      if (slotCounts[screen] < 1 || firstSlots[screen] != total) {
        return false;
      }
      total += slotCounts[screen];
    }
    return true;
  }

  private PackLayout(final long[] streams, final int[] slots, final int[] firstSlots) {
    this.streams = streams;
    this.slots = slots;
    this.firstSlots = firstSlots;
  }

  /** The values of a declared list of {@code count} elements, each at most {@code maximum}; null when there is none. */
  private static long @Nullable [] numbers(
    final String packConstants,
    final String declaration,
    final int count,
    final String element,
    final long maximum
  ) {
    final Pattern list = listPattern(declaration, count, element);
    final Matcher matcher = list.matcher(packConstants);
    if (!matcher.find()) {
      return null;
    }

    final long[] values = new long[count];
    for (int index = 0; index < count; index++) {
      final String value = requireNonNull(matcher.group(index + 1)); // checker framework
      values[index] = Long.parseLong(value);
      if (values[index] > maximum) {
        return null;
      }
    }
    return values;
  }

  private static Pattern listPattern(final String declaration, final int count, final String element) {
    final String quoted = Pattern.quote(declaration);
    final String length = LENGTH_START + count + LENGTH_END;
    final String elements = String.join(SEPARATOR, Collections.nCopies(count, element));
    final String expression = DECLARATION_START + quoted + length + CONSTRUCTOR + length + ELEMENTS_START + elements + ELEMENTS_END;
    return Pattern.compile(expression);
  }

  /** The rows of the strip a page fills at a screen width. */
  static int rowsPerPage(final int width) {
    return (PAGE_PIXELS + width - 1) / width;
  }

  /** How many page slots a screen has. */
  int slots(final int screen) {
    return this.slots[screen];
  }

  /** A screen's first page slot. */
  int firstSlot(final int screen) {
    return this.firstSlots[screen];
  }

  /** The number of screens. */
  int screens() {
    return this.streams.length;
  }

  /** All page slots of the pack. */
  int totalSlots() {
    return Arrays.stream(this.slots).sum();
  }

  /** The rows the strip covers at a screen width: every slot, then a descriptor row per screen. */
  int stripRows(final int width) {
    return this.totalSlots() * rowsPerPage(width) + this.screens();
  }

  /**
   * Finds the screen of a stream.
   *
   * @param stream a page's or an anchor's stream id
   * @return the screen, or {@link #NO_SCREEN} when the pack has none of that stream
   */
  int screenOf(final long stream) {
    for (int screen = 0; screen < this.streams.length; screen++) {
      if (this.streams[screen] == stream) {
        return screen;
      }
    }
    return NO_SCREEN;
  }
}
