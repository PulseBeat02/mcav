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
import java.util.Optional;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The version of yt-dlp that produced the metadata.
 *
 * <p>Gson fills instances from the JSON yt-dlp prints. Values yt-dlp did not report are empty, zero or false; a
 * primitive zero or false does not tell a missing value from a reported one.
 *
 * <p>This class is immutable and thread-safe.
 *
 * @since 1.0.0
 */
@JsonAdapter(Version.Deserializer.class)
public final class Version {

  private final @Nullable String version;

  @SerializedName("current_git_head")
  private final @Nullable Object currentGitHead;

  @SerializedName("release_git_head")
  private final @Nullable String releaseGitHead;

  private final @Nullable String repository;

  private Version(final Fields fields) {
    this.version = fields.version();
    this.currentGitHead = JsonValueUtils.toUnmodifiable(fields.currentGitHead());
    this.releaseGitHead = fields.releaseGitHead();
    this.repository = fields.repository();
  }

  /**
   * Returns the version of yt-dlp, such as {@code 2026.08.19}, or empty if yt-dlp did not report it.
   *
   * @return the version of yt-dlp, such as {@code 2026.08.19}, or empty if yt-dlp did not report it
   * @since 1.0.0
   */
  public Optional<String> getVersion() {
    return Optional.ofNullable(this.version);
  }

  /**
   * Returns the Git commit the running yt-dlp was built from, or empty for a release build.
   *
   * @return the Git commit the running yt-dlp was built from, or empty for a release build
   * @since 1.0.0
   */
  public Optional<Object> getCurrentGitHead() {
    return Optional.ofNullable(this.currentGitHead);
  }

  /**
   * Returns the Git commit of the release, or empty if yt-dlp did not report it.
   *
   * @return the Git commit of the release, or empty if yt-dlp did not report it
   * @since 1.0.0
   */
  public Optional<String> getReleaseGitHead() {
    return Optional.ofNullable(this.releaseGitHead);
  }

  /**
   * Returns the repository the build came from, such as {@code yt-dlp/yt-dlp}, or empty if yt-dlp did not report
   * it.
   *
   * @return the repository the build came from, such as {@code yt-dlp/yt-dlp}, or empty if yt-dlp did not report it
   * @since 1.0.0
   */
  public Optional<String> getRepository() {
    return Optional.ofNullable(this.repository);
  }

  /**
   * Returns whether {@code other} is a version description with the same values.
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

    if (!(other instanceof final Version version)) {
      return false;
    }

    return (
      Objects.equals(this.version, version.version) &&
      Objects.equals(this.currentGitHead, version.currentGitHead) &&
      Objects.equals(this.releaseGitHead, version.releaseGitHead) &&
      Objects.equals(this.repository, version.repository)
    );
  }

  /**
   * Returns a hash code built from the version, both Git heads and the repository.
   *
   * @return the hash code
   * @since 1.0.0
   */
  @Override
  public int hashCode() {
    return Objects.hash(this.version, this.currentGitHead, this.releaseGitHead, this.repository);
  }

  /**
   * Returns a debugging representation with the version, both Git heads and the repository.
   *
   * @return the representation
   * @since 1.0.0
   */
  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
      .add("version", this.version)
      .add("currentGitHead", this.currentGitHead)
      .add("releaseGitHead", this.releaseGitHead)
      .add("repository", this.repository)
      .toString();
  }

  static final class Deserializer implements JsonDeserializer<Version> {

    @Override
    public Version deserialize(final JsonElement json, final Type type, final JsonDeserializationContext context) {
      final @Nullable Fields parsed = context.deserialize(json, Fields.class);
      final Fields fields = requireNonNull(parsed); // checker framework
      return new Version(fields);
    }
  }

  private record Fields(
    @Nullable String version,
    @SerializedName("current_git_head") @Nullable Object currentGitHead,
    @SerializedName("release_git_head") @Nullable String releaseGitHead,
    @Nullable String repository
  ) {}
}
