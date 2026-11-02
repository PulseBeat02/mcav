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

import com.google.common.base.Preconditions;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where {@code /mcav video seek} jumps to: a time from the start of the video, such as {@code 90}, {@code 1:30} or
 * {@code 1:02:03}, or a time from the current position, such as {@code +10} or {@code -1:00}. Seconds may have up to
 * three decimals, minutes and seconds after a colon are below 60, and no time is longer than {@link #MAXIMUM_MILLISECONDS}.
 *
 * @param relative     whether the time counts from the current position
 * @param milliseconds the time in milliseconds, negative only for a jump back
 */
public record SeekPosition(boolean relative, long milliseconds) {
  /** The longest time: 99 hours, 59 minutes and 59.999 seconds, beyond any video a server plays. */
  public static final long MAXIMUM_MILLISECONDS = 359_999_999L;

  private static final Pattern FORMAT = Pattern.compile("([+-]?)(?:(?:(\\d{1,2}):)?(\\d{1,2}):)?(\\d{1,6})(?:\\.(\\d{1,3}))?");

  private static final long MILLISECONDS_PER_SECOND = 1_000L;

  private static final int SECONDS_PER_MINUTE = 60;

  private static final int MINUTES_PER_HOUR = 60;

  private static final int MILLISECONDS_DIGITS = 3;

  private static final int HOURS_GROUP = 2;
  private static final int MINUTES_GROUP = 3;
  private static final int SECONDS_GROUP = 4;
  private static final int FRACTION_GROUP = 5;

  private static final String FRACTION_PADDING = "00";

  private static final String BACKWARD_SIGN = "-";

  /**
   * Parses a position.
   *
   * @param text the position as the player typed it
   * @return the position, or empty if the text is not one
   * @throws NullPointerException if an argument is null
   */
  public static Optional<SeekPosition> parse(final String text) {
    Preconditions.checkNotNull(text, "Text must not be null!");

    final Matcher matcher = FORMAT.matcher(text);
    if (!matcher.matches()) {
      return Optional.empty();
    }

    final String sign = Objects.requireNonNull(matcher.group(1));
    final String hours = matcher.group(HOURS_GROUP);
    final String minutes = matcher.group(MINUTES_GROUP);
    final String seconds = Objects.requireNonNull(matcher.group(SECONDS_GROUP));
    final String fraction = matcher.group(FRACTION_GROUP);
    final long secondCount = Long.parseLong(seconds);
    final long minuteCount = minutes == null ? 0 : Long.parseLong(minutes);
    if ((minutes != null && secondCount >= SECONDS_PER_MINUTE) || (hours != null && minuteCount >= MINUTES_PER_HOUR)) {
      return Optional.empty();
    }

    final long hourCount = hours == null ? 0 : Long.parseLong(hours);
    final long wholeSeconds = (hourCount * MINUTES_PER_HOUR + minuteCount) * SECONDS_PER_MINUTE + secondCount;
    final long fractionMilliseconds =
      fraction == null ? 0 : Long.parseLong((fraction + FRACTION_PADDING).substring(0, MILLISECONDS_DIGITS));
    final long milliseconds = wholeSeconds * MILLISECONDS_PER_SECOND + fractionMilliseconds;
    if (milliseconds > MAXIMUM_MILLISECONDS) {
      return Optional.empty();
    }

    final boolean relative = !sign.isEmpty();
    final SeekPosition position = new SeekPosition(relative, sign.equals(BACKWARD_SIGN) ? -milliseconds : milliseconds);
    return Optional.of(position);
  }

  /**
   * Finds the time to seek to.
   *
   * @param current the current position in milliseconds, which a relative position counts from
   * @return the time from the start of the video in milliseconds, at least 0
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
   */
  public static String format(final long milliseconds) {
    Preconditions.checkArgument(milliseconds >= 0, "Time %s must not be negative!", milliseconds);

    final long totalSeconds = milliseconds / MILLISECONDS_PER_SECOND;
    final long seconds = totalSeconds % SECONDS_PER_MINUTE;
    final long totalMinutes = totalSeconds / SECONDS_PER_MINUTE;
    final long minutes = totalMinutes % MINUTES_PER_HOUR;
    final long hours = totalMinutes / MINUTES_PER_HOUR;
    return hours > 0
      ? String.format(Locale.getDefault(Locale.Category.FORMAT), "%d:%02d:%02d", hours, minutes, seconds)
      : String.format(Locale.getDefault(Locale.Category.FORMAT), "%d:%02d", minutes, seconds);
  }
}
