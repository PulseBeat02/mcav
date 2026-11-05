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
package me.brandonli.mcav.json.ytdlp.format;

import static java.util.Objects.requireNonNull;

import com.google.common.base.MoreObjects;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;
import java.lang.reflect.Type;
import java.util.Objects;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * One entry of the replay heat map of a video, as listed by yt-dlp.
 *
 * <p>Gson fills instances from the JSON yt-dlp prints. Values yt-dlp did not report are empty, zero or false; a
 * primitive zero or false does not tell a missing value from a reported one.
 *
 * <p>This class is immutable and thread-safe.
 *
 * @since 1.0.0
 */
@JsonAdapter(Heatmap.Deserializer.class)
public final class Heatmap {

  @SerializedName("start_time")
  private final double startTime;

  @SerializedName("end_time")
  private final double endTime;

  private final double value;

  private Heatmap(final Fields fields) {
    this.startTime = Objects.requireNonNullElse(fields.startTime(), 0.0);
    this.endTime = Objects.requireNonNullElse(fields.endTime(), 0.0);
    this.value = Objects.requireNonNullElse(fields.value(), 0.0);
  }

  /**
   * Returns where the entry starts, in seconds from the start of the video.
   *
   * @return where the entry starts, in seconds from the start of the video
   * @since 1.0.0
   */
  public double getStartTime() {
    return this.startTime;
  }

  /**
   * Returns where the entry ends, in seconds from the start of the video.
   *
   * @return where the entry ends, in seconds from the start of the video
   * @since 1.0.0
   */
  public double getEndTime() {
    return this.endTime;
  }

  /**
   * Returns how often this part of the video was replayed, relative to the most replayed part, from 0 to 1.
   *
   * @return how often this part of the video was replayed, relative to the most replayed part, from 0 to 1
   * @since 1.0.0
   */
  public double getValue() {
    return this.value;
  }

  /**
   * Returns whether {@code other} is a heat map entry with the same values.
   *
   * @param other the object to compare with
   * @return whether every value matches
   * @since 1.0.0
   */
  @Override
  public boolean equals(final @Nullable Object other) {
    if (this == other) {
      return true;
    }

    if (!(other instanceof final Heatmap heatmap)) {
      return false;
    }

    return (
      Double.compare(this.startTime, heatmap.startTime) == 0 &&
      Double.compare(this.endTime, heatmap.endTime) == 0 &&
      Double.compare(this.value, heatmap.value) == 0
    );
  }

  /**
   * Returns a hash code built from the start time, the end time and the value of this heat map entry.
   *
   * @return the hash code
   * @since 1.0.0
   */
  @Override
  public int hashCode() {
    return Objects.hash(this.startTime, this.endTime, this.value);
  }

  /**
   * Returns a debugging representation with the start time, the end time and the value of this heat map entry.
   *
   * @return the representation
   * @since 1.0.0
   */
  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
      .add("startTime", this.startTime)
      .add("endTime", this.endTime)
      .add("value", this.value)
      .toString();
  }

  static final class Deserializer implements JsonDeserializer<Heatmap> {

    @Override
    public Heatmap deserialize(final JsonElement json, final Type type, final JsonDeserializationContext context) {
      final @Nullable Fields parsed = context.deserialize(json, Fields.class);
      final Fields fields = requireNonNull(parsed); // checker framework
      return new Heatmap(fields);
    }
  }

  private record Fields(
    @SerializedName("start_time") @Nullable Double startTime,
    @SerializedName("end_time") @Nullable Double endTime,
    @Nullable Double value
  ) {}
}
