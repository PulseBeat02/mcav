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

import static java.util.Objects.requireNonNull;

import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Where {@code /mcav video seek} jumps to: a time from the start of the video, such as {@code 90}, {@code 1:30} or
 * {@code 1:02:03}, or a time from the current position, such as {@code +10} or {@code -1:00}. Seconds may have up to
 * three decimals, minutes and seconds after a colon are below 60, and no time is longer than {@link #MAXIMUM_MILLISECONDS}.
 *
 * <p>This class is immutable and thread-safe.
 *
 * @since 1.0.0
 */
public final class SeekPosition {

  /**
   * The longest time: 99 hours, 59 minutes and 59.999 seconds, beyond any video a server plays.
   *
   * @since 1.0.0
   */
  public static final long MAXIMUM_MILLISECONDS = 359_999_999L;

  private static final Pattern FORMAT = Pattern.compile("([+-]?)(?:(?:(\\d{1,2}):)?(\\d{1,2}):)?(\\d{1,6})(?:\\.(\\d{1,3}))?");

  private static final long MILLISECONDS_PER_SECOND = 1_000L;
  private static final int SECONDS_PER_MINUTE = 60;
  private static final int MINUTES_PER_HOUR = 60;

  private static final int MILLISECONDS_DIGITS = 3;

  private static final int SIGN_GROUP = 1;
  private static final int HOURS_GROUP = 2;
  private static final int MINUTES_GROUP = 3;
  private static final int SECONDS_GROUP = 4;
  private static final int FRACTION_GROUP = 5;

  private static final char FRACTION_PADDING_DIGIT = '0';

  private static final String BACKWARD_SIGN = "-";

  private final boolean relative;
  private final long milliseconds;

  private SeekPosition(final boolean relative, final long milliseconds) {
    this.relative = relative;
    this.milliseconds = milliseconds;
  }

  /**
   * Creates a position.
   *
   * @param relative     whether the time counts from the current position
   * @param milliseconds the time in milliseconds, negative only for a jump back
   * @return the position
   * @since 1.0.0
   */
  public static SeekPosition of(final boolean relative, final long milliseconds) {
    return new SeekPosition(relative, milliseconds);
  }

  /**
   * Parses a position.
   *
   * @param text the position as the player typed it
   * @return the position, or empty if the text is not one
   * @throws NullPointerException if {@code text} is null
   * @since 1.0.0
   */
  public static Optional<SeekPosition> parse(final String text) {
    Preconditions.checkNotNull(text, "Text must not be null!");

    final Matcher matcher = FORMAT.matcher(text);
    if (!matcher.matches()) {
      return Optional.empty();
    }

    final String sign = requireNonNull(matcher.group(SIGN_GROUP)); // checker framework
    final String hours = matcher.group(HOURS_GROUP);
    final String minutes = matcher.group(MINUTES_GROUP);
    final String seconds = requireNonNull(matcher.group(SECONDS_GROUP)); // checker framework
    final String fraction = matcher.group(FRACTION_GROUP);
    final long secondCount = Long.parseLong(seconds);
    final long minuteCount = minutes == null ? 0 : Long.parseLong(minutes);
    final boolean secondsOverflow = minutes != null && secondCount >= SECONDS_PER_MINUTE;
    final boolean minutesOverflow = hours != null && minuteCount >= MINUTES_PER_HOUR;
    if (secondsOverflow || minutesOverflow) {
      return Optional.empty();
    }

    final long hourCount = hours == null ? 0 : Long.parseLong(hours);
    final long wholeSeconds = (hourCount * MINUTES_PER_HOUR + minuteCount) * SECONDS_PER_MINUTE + secondCount;
    final long fractionMilliseconds = parseFractionMilliseconds(fraction);
    final long milliseconds = wholeSeconds * MILLISECONDS_PER_SECOND + fractionMilliseconds;
    if (milliseconds > MAXIMUM_MILLISECONDS) {
      return Optional.empty();
    }

    final boolean relative = !sign.isEmpty();
    final long signedMilliseconds = sign.equals(BACKWARD_SIGN) ? -milliseconds : milliseconds;
    final SeekPosition position = new SeekPosition(relative, signedMilliseconds);
    return Optional.of(position);
  }

  private static long parseFractionMilliseconds(final @Nullable String fraction) {
    if (fraction == null) {
      return 0;
    }

    final String padded = Strings.padEnd(fraction, MILLISECONDS_DIGITS, FRACTION_PADDING_DIGIT);
    return Long.parseLong(padded);
  }

  /**
   * Finds the time to seek to.
   *
   * @param current the current position in milliseconds, which a relative position counts from
   * @return the time from the start of the video in milliseconds, at least 0
   * @since 1.0.0
   */
  public long resolve(final long current) {
    final long target = this.relative ? current + this.milliseconds : this.milliseconds;
    return Math.max(0, target);
  }

  /**
   * Formats a time as {@code h:mm:ss}, or {@code m:ss} below an hour, the way players read it.
   *
   * @param milliseconds the time in milliseconds, at least 0
   * @return the time, without its milliseconds
   * @throws IllegalArgumentException if {@code milliseconds} is negative
   * @since 1.0.0
   */
  public static String format(final long milliseconds) {
    Preconditions.checkArgument(milliseconds >= 0, "Time %s must not be negative!", milliseconds);

    final long totalSeconds = milliseconds / MILLISECONDS_PER_SECOND;
    final long seconds = totalSeconds % SECONDS_PER_MINUTE;
    final long totalMinutes = totalSeconds / SECONDS_PER_MINUTE;
    final long minutes = totalMinutes % MINUTES_PER_HOUR;
    final long hours = totalMinutes / MINUTES_PER_HOUR;
    final Locale locale = Locale.getDefault(Locale.Category.FORMAT);
    if (hours > 0) {
      return String.format(locale, "%d:%02d:%02d", hours, minutes, seconds);
    }

    return String.format(locale, "%d:%02d", minutes, seconds);
  }

  /**
   * Returns whether the time counts from the current position.
   *
   * @return true if the time counts from the current position, false if from the start
   * @since 1.0.0
   */
  public boolean isRelative() {
    return this.relative;
  }

  /**
   * Returns the time.
   *
   * @return the time in milliseconds, negative only for a jump back
   * @since 1.0.0
   */
  public long getMilliseconds() {
    return this.milliseconds;
  }

  /**
   * Returns whether {@code other} is a position with the same starting point and time.
   *
   * @param other the object to compare with
   * @return whether both match
   * @since 1.0.0
   */
  @Override
  public boolean equals(final @Nullable Object other) {
    if (this == other) {
      return true;
    }

    if (!(other instanceof final SeekPosition position)) {
      return false;
    }

    return this.relative == position.relative && this.milliseconds == position.milliseconds;
  }

  /**
   * Returns a hash code built from the starting point and the time.
   *
   * @return the hash code
   * @since 1.0.0
   */
  @Override
  public int hashCode() {
    return Objects.hash(this.relative, this.milliseconds);
  }

  /**
   * Returns a debugging representation with the starting point and the time.
   *
   * @return the representation
   * @since 1.0.0
   */
  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this).add("relative", this.relative).add("milliseconds", this.milliseconds).toString();
  }
}
