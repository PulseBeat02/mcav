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

import com.google.common.base.Preconditions;
import me.brandonli.mcav.utils.os.Arch;
import me.brandonli.mcav.utils.os.Bits;
import me.brandonli.mcav.utils.os.OS;
import me.brandonli.mcav.utils.os.Platform;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A downloadable file for one platform, optionally protected by a SHA-256 hash.
 *
 * <p>Installers are given a list of downloads and pick the one whose platform matches the machine they run on.
 * Downloads are usually read from the JSON resources in the {@code installers} folder, but can be created by hand
 * as well. Instances are immutable.
 */
public final class Download {

  private final Platform platform;
  private final String url;
  private final @Nullable String hash;

  /**
   * Constructs a new download.
   *
   * @param platform the platform the file is built for
   * @param url      the URL of the file
   * @param hash     the expected SHA-256 hash of the file in hexadecimal, or null to skip verification
   */
  public Download(final Platform platform, final String url, final @Nullable String hash) {
    Preconditions.checkNotNull(platform, "Platform must not be null");
    Preconditions.checkNotNull(url, "URL must not be null");
    Preconditions.checkArgument(!url.isBlank(), "URL must not be blank");
    this.platform = platform;
    this.url = url;
    this.hash = hash;
  }

  /**
   * Constructs a new download without hash verification.
   *
   * @param platform the platform the file is built for
   * @param url      the URL of the file
   */
  public Download(final Platform platform, final String url) {
    this(platform, url, null);
  }

  /**
   * Constructs a new download.
   *
   * @param operatingSystem the operating system the file is built for
   * @param architecture    the CPU architecture the file is built for
   * @param bits            the bitness the file is built for
   * @param url             the URL of the file
   * @param hash            the expected SHA-256 hash of the file in hexadecimal, or null to skip verification
   */
  public Download(final OS operatingSystem, final Arch architecture, final Bits bits, final String url, final @Nullable String hash) {
    final Platform platform = Platform.ofPlatform(operatingSystem, architecture, bits);
    this(platform, url, hash);
  }

  /**
   * Constructs a new download without hash verification.
   *
   * @param operatingSystem the operating system the file is built for
   * @param architecture    the CPU architecture the file is built for
   * @param bits            the bitness the file is built for
   * @param url             the URL of the file
   */
  public Download(final OS operatingSystem, final Arch architecture, final Bits bits, final String url) {
    final Platform platform = Platform.ofPlatform(operatingSystem, architecture, bits);
    this(platform, url, null);
  }

  /**
   * Gets the platform the file is built for.
   *
   * @return the platform
   */
  public Platform getPlatform() {
    return this.platform;
  }

  /**
   * Gets the URL of the file.
   *
   * @return the URL
   */
  public String getUrl() {
    return this.url;
  }

  /**
   * Gets the expected SHA-256 hash of the file.
   *
   * @return the hash in hexadecimal, or null if the file is not verified
   */
  public @Nullable String getHash() {
    return this.hash;
  }
}
