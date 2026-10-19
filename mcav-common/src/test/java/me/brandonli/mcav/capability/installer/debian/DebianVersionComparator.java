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
package me.brandonli.mcav.capability.installer.debian;

import com.google.common.base.CharMatcher;
import java.util.Comparator;
import java.util.List;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Compares Debian versions as dpkg does: by epoch, then upstream version, then revision. Each part is a series of
// runs of non-digits, compared character by character with ~ before everything, even the end, and letters before
// other characters, each followed by a run of digits, compared as a number.
final class DebianVersionComparator implements Comparator<String> {

  private static final long DEFAULT_EPOCH = 0L;
  private static final char EPOCH_SEPARATOR = ':';
  private static final char REVISION_SEPARATOR = '-';

  private static final Pattern RUN = Pattern.compile("(\\D*)(\\d*)");
  private static final int NON_DIGITS_GROUP = 1;
  private static final int DIGITS_GROUP = 2;
  private static final Run EMPTY_RUN = new Run("", "");
  private static final CharMatcher ZERO = CharMatcher.is('0');

  private static final char TILDE = '~';
  private static final int TILDE_ORDER = -1;
  private static final int END_ORDER = 0;
  private static final int NON_LETTER_OFFSET = 256;

  @Override
  public int compare(final String first, final String second) {
    final long firstEpoch = this.readEpoch(first);
    final long secondEpoch = this.readEpoch(second);
    final int epochOrder = Long.compare(firstEpoch, secondEpoch);
    if (epochOrder != 0) {
      return epochOrder;
    }

    final String firstUpstream = this.readUpstream(first);
    final String secondUpstream = this.readUpstream(second);
    final int upstreamOrder = this.compareParts(firstUpstream, secondUpstream);
    if (upstreamOrder != 0) {
      return upstreamOrder;
    }

    final String firstRevision = this.readRevision(first);
    final String secondRevision = this.readRevision(second);
    return this.compareParts(firstRevision, secondRevision);
  }

  private long readEpoch(final String version) {
    final int separator = version.indexOf(EPOCH_SEPARATOR);
    if (separator < 0) {
      return DEFAULT_EPOCH;
    }

    final String epoch = version.substring(0, separator);
    return Long.parseLong(epoch);
  }

  private String readUpstream(final String version) {
    final String withoutEpoch = this.removeEpoch(version);
    final int separator = withoutEpoch.lastIndexOf(REVISION_SEPARATOR);
    if (separator < 0) {
      return withoutEpoch;
    }

    return withoutEpoch.substring(0, separator);
  }

  // a version without a revision compares as if its revision were 0
  private String readRevision(final String version) {
    final String withoutEpoch = this.removeEpoch(version);
    final int separator = withoutEpoch.lastIndexOf(REVISION_SEPARATOR);
    if (separator < 0) {
      return "";
    }

    return withoutEpoch.substring(separator + 1);
  }

  private String removeEpoch(final String version) {
    final int separator = version.indexOf(EPOCH_SEPARATOR);
    return version.substring(separator + 1);
  }

  private int compareParts(final String first, final String second) {
    final List<Run> firstRuns = this.splitRuns(first);
    final List<Run> secondRuns = this.splitRuns(second);
    final int count = Math.max(firstRuns.size(), secondRuns.size());
    for (int i = 0; i < count; i++) {
      final Run firstRun = this.getRun(firstRuns, i);
      final Run secondRun = this.getRun(secondRuns, i);
      final int order = this.compareRuns(firstRun, secondRun);
      if (order != 0) {
        return order;
      }
    }

    return 0;
  }

  private List<Run> splitRuns(final String part) {
    final Matcher matcher = RUN.matcher(part);
    return matcher.results().filter(this::isNotEmpty).map(this::toRun).toList();
  }

  private boolean isNotEmpty(final MatchResult result) {
    final String text = result.group();
    return !text.isEmpty();
  }

  private Run toRun(final MatchResult result) {
    final String nonDigits = result.group(NON_DIGITS_GROUP);
    final String digits = result.group(DIGITS_GROUP);
    return new Run(nonDigits, digits);
  }

  private Run getRun(final List<Run> runs, final int index) {
    if (index < runs.size()) {
      return runs.get(index);
    }

    return EMPTY_RUN;
  }

  private int compareRuns(final Run first, final Run second) {
    final String firstNonDigits = first.nonDigits();
    final String secondNonDigits = second.nonDigits();
    final int nonDigitOrder = this.compareNonDigits(firstNonDigits, secondNonDigits);
    if (nonDigitOrder != 0) {
      return nonDigitOrder;
    }

    final String firstDigits = first.digits();
    final String secondDigits = second.digits();
    return this.compareDigits(firstDigits, secondDigits);
  }

  private int compareNonDigits(final String first, final String second) {
    final int length = Math.max(first.length(), second.length());
    for (int i = 0; i < length; i++) {
      final int firstOrder = this.order(first, i);
      final int secondOrder = this.order(second, i);
      if (firstOrder != secondOrder) {
        return Integer.compare(firstOrder, secondOrder);
      }
    }

    return 0;
  }

  private int order(final String nonDigits, final int index) {
    if (index >= nonDigits.length()) {
      return END_ORDER;
    }

    final char character = nonDigits.charAt(index);
    if (character == TILDE) {
      return TILDE_ORDER;
    }

    if (Character.isLetter(character)) {
      return character;
    }

    return character + NON_LETTER_OFFSET;
  }

  private int compareDigits(final String first, final String second) {
    final String firstNumber = ZERO.trimLeadingFrom(first);
    final String secondNumber = ZERO.trimLeadingFrom(second);
    final int lengthOrder = Integer.compare(firstNumber.length(), secondNumber.length());
    if (lengthOrder != 0) {
      return lengthOrder;
    }

    return firstNumber.compareTo(secondNumber);
  }

  private record Run(String nonDigits, String digits) {}
}
