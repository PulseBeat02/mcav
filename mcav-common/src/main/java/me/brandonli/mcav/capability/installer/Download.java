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
package me.brandonli.mcav.capability.installer;

import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import java.util.Objects;
import java.util.Optional;
import me.brandonli.mcav.utils.os.Arch;
import me.brandonli.mcav.utils.os.Bits;
import me.brandonli.mcav.utils.os.Os;
import me.brandonli.mcav.utils.os.Platform;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A downloadable file for one platform, optionally protected by a SHA-256 hash.
 *
 * <p>Installers are given a list of downloads and pick the one whose platform matches the machine they run on.
 * Downloads are usually read from the JSON resources in the {@code installers} folder, but can be created by hand
 * as well. Instances are immutable.
 *
 * <p>A download may name a mirror: a second URL of the same file, checked against the same hash. The Debian installers
 * try it before the URL.
 *
 * <p>This class is immutable and thread-safe.
 *
 * @since 1.0.0
 */
public final class Download {

  private final Platform platform;
  private final String url;
  private final @Nullable String hash;
  private final @Nullable String mirror;

  private Download(final Platform platform, final String url, final @Nullable String hash, final @Nullable String mirror) {
    this.platform = platform;
    this.url = url;
    this.hash = hash;
    this.mirror = mirror;
  }

  /**
   * Returns a download of the file at {@code url} that can also be fetched from {@code mirror}.
   *
   * @param platform the platform the file is built for
   * @param url      the non-null, nonblank URL of the file; URI syntax is checked when downloading
   * @param hash     the expected SHA-256 hash of the file in hexadecimal, or null to skip verification
   * @param mirror   a nonblank second URL of the same file, checked against the same hash, or null for none
   * @return the download
   * @throws IllegalArgumentException if {@code url} or {@code mirror} is blank
   * @throws NullPointerException     if {@code platform} or {@code url} is null
   * @since 1.0.0
   */
  public static Download of(final Platform platform, final String url, final @Nullable String hash, final @Nullable String mirror) {
    Preconditions.checkNotNull(platform, "Platform must not be null!");
    Preconditions.checkNotNull(url, "URL must not be null!");
    Preconditions.checkArgument(!url.isBlank(), "URL must not be blank!");
    Preconditions.checkArgument(mirror == null || !mirror.isBlank(), "Mirror must not be blank!");

    return new Download(platform, url, hash, mirror);
  }

  /**
   * Returns a download of the file at {@code url} without a mirror.
   *
   * @param platform the platform the file is built for
   * @param url      the non-null, nonblank URL of the file; URI syntax is checked when downloading
   * @param hash     the expected SHA-256 hash of the file in hexadecimal, or null to skip verification
   * @return the download
   * @throws IllegalArgumentException if {@code url} is blank
   * @throws NullPointerException     if {@code platform} or {@code url} is null
   * @since 1.0.0
   */
  public static Download of(final Platform platform, final String url, final @Nullable String hash) {
    return of(platform, url, hash, null);
  }

  /**
   * Returns a download of the file at {@code url} without hash verification or a mirror.
   *
   * @param platform the platform the file is built for
   * @param url      the non-null, nonblank URL of the file; URI syntax is checked when downloading
   * @return the download
   * @throws IllegalArgumentException if {@code url} is blank
   * @throws NullPointerException     if {@code platform} or {@code url} is null
   * @since 1.0.0
   */
  public static Download of(final Platform platform, final String url) {
    return of(platform, url, null);
  }

  /**
   * Returns a download of the file at {@code url} for the platform made of the given parts, without a mirror.
   *
   * @param operatingSystem the operating system the file is built for
   * @param architecture    the CPU architecture the file is built for
   * @param bits            the bitness the file is built for
   * @param url             the non-null, nonblank URL of the file; URI syntax is checked when downloading
   * @param hash            the expected SHA-256 hash of the file in hexadecimal, or null to skip verification
   * @return the download
   * @throws IllegalArgumentException if {@code url} is blank
   * @throws NullPointerException     if {@code operatingSystem}, {@code architecture}, {@code bits} or {@code url} is null
   * @since 1.0.0
   */
  public static Download of(
    final Os operatingSystem,
    final Arch architecture,
    final Bits bits,
    final String url,
    final @Nullable String hash
  ) {
    final Platform platform = Platform.ofPlatform(operatingSystem, architecture, bits);
    return of(platform, url, hash);
  }

  /**
   * Returns a download of the file at {@code url} for the platform made of the given parts, without hash verification or
   * a mirror.
   *
   * @param operatingSystem the operating system the file is built for
   * @param architecture    the CPU architecture the file is built for
   * @param bits            the bitness the file is built for
   * @param url             the non-null, nonblank URL of the file; URI syntax is checked when downloading
   * @return the download
   * @throws IllegalArgumentException if {@code url} is blank
   * @throws NullPointerException     if {@code operatingSystem}, {@code architecture}, {@code bits} or {@code url} is null
   * @since 1.0.0
   */
  public static Download of(final Os operatingSystem, final Arch architecture, final Bits bits, final String url) {
    return of(operatingSystem, architecture, bits, url, null);
  }

  /**
   * Returns the platform the file is built for.
   *
   * @return the platform
   * @since 1.0.0
   */
  public Platform getPlatform() {
    return this.platform;
  }

  /**
   * Returns the URL of the file.
   *
   * @return the URL
   * @since 1.0.0
   */
  public String getUrl() {
    return this.url;
  }

  /**
   * Returns the expected SHA-256 hash of the file.
   *
   * @return the hash in hexadecimal, or empty if the file is not verified
   * @since 1.0.0
   */
  public Optional<String> getHash() {
    return Optional.ofNullable(this.hash);
  }

  /**
   * Returns the mirror, a second URL of the same file that is checked against the same hash.
   *
   * @return the mirror URL, or empty if the file has none
   * @since 1.0.0
   */
  public Optional<String> getMirror() {
    return Optional.ofNullable(this.mirror);
  }

  /**
   * Returns whether {@code other} is a download of the same URL for the same platform with the same hash and mirror.
   *
   * @param other the object to compare with
   * @return whether the platform, the URL, the hash and the mirror match
   * @since 1.0.0
   */
  @Override
  public boolean equals(final @Nullable Object other) {
    if (this == other) {
      return true;
    }

    if (!(other instanceof final Download download)) {
      return false;
    }

    return (
      this.platform.equals(download.platform) &&
      this.url.equals(download.url) &&
      Objects.equals(this.hash, download.hash) &&
      Objects.equals(this.mirror, download.mirror)
    );
  }

  /**
   * Returns a hash code built from the platform, the URL, the hash and the mirror.
   *
   * @return the hash code
   * @since 1.0.0
   */
  @Override
  public int hashCode() {
    return Objects.hash(this.platform, this.url, this.hash, this.mirror);
  }

  /**
   * Returns a debugging representation with the platform, the URL, the hash and the mirror.
   *
   * @return the representation
   * @since 1.0.0
   */
  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
      .add("platform", this.platform)
      .add("url", this.url)
      .add("hash", this.hash)
      .add("mirror", this.mirror)
      .toString();
  }
}
