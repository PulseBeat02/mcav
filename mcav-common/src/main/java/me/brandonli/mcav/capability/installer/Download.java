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

import me.brandonli.mcav.utils.os.Arch;
import me.brandonli.mcav.utils.os.Bits;
import me.brandonli.mcav.utils.os.OS;
import me.brandonli.mcav.utils.os.Platform;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Represents a download link for a specific platform.
 */
public final class Download {

  private final Platform platform;
  private final String url;
  private final @Nullable String hash;

  /**
   * Constructs a new Download object with the specified platform, URL, and hash.
   *
   * @param platform the platform for the download
   * @param url      the URL for the download
   * @param hash     the expected hash of the file (SHA-256)
   */
  public Download(final Platform platform, final String url, final @Nullable String hash) {
    this.platform = platform;
    this.url = url;
    this.hash = hash;
  }

  /**
   * Constructs a new Download object with the specified platform and URL.
   * No hash verification will be performed.
   *
   * @param platform the platform for the download
   * @param url      the URL for the download
   */
  public Download(final Platform platform, final String url) {
    this(platform, url, null);
  }

  /**
   * Constructs a new Download object with the specified OS, architecture, bits, URL, and hash.
   *
   * @param os   the operating system
   * @param arch the architecture
   * @param bits the bits (32 or 64)
   * @param url  the URL for the download
   * @param hash the expected hash of the file (SHA-256)
   */
  public Download(final OS os, final Arch arch, final Bits bits, final String url, final String hash) {
    this(Platform.ofPlatform(os, arch, bits), url, hash);
  }

  /**
   * Constructs a new Download object with the specified OS, architecture, bits, and URL.
   * No hash verification will be performed.
   *
   * @param os   the operating system
   * @param arch the architecture
   * @param bits the bits (32 or 64)
   * @param url  the URL for the download
   */
  public Download(final OS os, final Arch arch, final Bits bits, final String url) {
    this(Platform.ofPlatform(os, arch, bits), url, null);
  }

  /**
   * Gets the platform for this download.
   *
   * @return the platform
   */
  public Platform getPlatform() {
    return this.platform;
  }

  /**
   * Gets the URL for this download.
   *
   * @return the URL
   */
  public String getUrl() {
    return this.url;
  }

  /**
   * Gets the expected hash for this download.
   *
   * @return the hash, or null if no hash verification is required
   */
  public @Nullable String getHash() {
    return this.hash;
  }
}
