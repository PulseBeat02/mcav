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
package me.brandonli.mcav.vnc;

import com.google.common.base.Preconditions;
import java.util.Objects;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The default {@link VNCSource}.
 */
public final class VNCSourceImpl implements VNCSource {

  private final String host;
  private final int port;
  private final @Nullable String username;
  private final @Nullable String password;
  private final int width;
  private final int height;
  private final int targetFrameRate;

  VNCSourceImpl(
    final String host,
    final int port,
    final @Nullable String username,
    final @Nullable String password,
    final int width,
    final int height,
    final int targetFrameRate
  ) {
    this.host = host;
    this.port = port;
    this.username = username;
    this.password = password;
    this.width = width;
    this.height = height;
    this.targetFrameRate = targetFrameRate;
  }

  @Override
  public @Nullable String getUsername() {
    return this.username;
  }

  @Override
  public String getHost() {
    return this.host;
  }

  @Override
  public int getPort() {
    return this.port;
  }

  @Override
  public @Nullable String getPassword() {
    return this.password;
  }

  @Override
  public int getScreenWidth() {
    return this.width;
  }

  @Override
  public int getScreenHeight() {
    return this.height;
  }

  @Override
  public int getTargetFrameRate() {
    return this.targetFrameRate;
  }

  @Override
  public boolean equals(final @Nullable Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof final VNCSourceImpl source)) {
      return false;
    }
    return (
      this.host.equals(source.host) &&
      this.port == source.port &&
      Objects.equals(this.username, source.username) &&
      Objects.equals(this.password, source.password) &&
      this.width == source.width &&
      this.height == source.height &&
      this.targetFrameRate == source.targetFrameRate
    );
  }

  /**
   * Hashes the source values for use in hash-based collections. Representative distinct sources should not all
   * collapse to one hash; individual collisions remain valid and the exact hash algorithm is not guaranteed.
   *
   * @return the hash of this source's values
   */
  @Override
  public int hashCode() {
    return Objects.hash(this.host, this.port, this.username, this.password, this.width, this.height, this.targetFrameRate);
  }

  @Override
  public String toString() {
    final String resource = this.getResource();
    return "VNCSource[" + resource + ", " + this.width + "x" + this.height + "@" + this.targetFrameRate + "]";
  }

  /**
   * The default {@link VNCSource.Builder}.
   */
  static final class BuilderImpl implements Builder {

    private @Nullable String host;
    private int port;
    private @Nullable String username;
    private @Nullable String password;
    private int width;
    private int height;
    private int targetFrameRate;

    BuilderImpl() {
      this.port = DEFAULT_PORT;
      this.targetFrameRate = DEFAULT_FRAME_RATE;
    }

    @Override
    public Builder host(final String host) {
      Preconditions.checkNotNull(host, "Host must not be null");
      Preconditions.checkArgument(!host.isBlank(), "Host must not be blank");
      this.host = host;
      return this;
    }

    @Override
    public Builder port(final int port) {
      Preconditions.checkArgument(port > 0 && port <= 65535, "Port must be between 1 and 65535 but was %s", port);
      this.port = port;
      return this;
    }

    @Override
    public Builder username(final String username) {
      Preconditions.checkNotNull(username, "Username must not be null");
      this.username = username;
      return this;
    }

    @Override
    public Builder password(final String password) {
      Preconditions.checkNotNull(password, "Password must not be null");
      this.password = password;
      return this;
    }

    @Override
    public Builder screenWidth(final int width) {
      Preconditions.checkArgument(width >= 0, "Width must not be negative but was %s", width);
      this.width = width;
      return this;
    }

    @Override
    public Builder screenHeight(final int height) {
      Preconditions.checkArgument(height >= 0, "Height must not be negative but was %s", height);
      this.height = height;
      return this;
    }

    @Override
    public Builder targetFrameRate(final int targetFrameRate) {
      Preconditions.checkArgument(targetFrameRate > 0, "Frame rate must be positive but was %s", targetFrameRate);
      this.targetFrameRate = targetFrameRate;
      return this;
    }

    @Override
    public VNCSource build() {
      final String configuredHost = this.host;
      if (configuredHost == null) {
        throw new IllegalStateException("A host is required");
      }
      return new VNCSourceImpl(configuredHost, this.port, this.username, this.password, this.width, this.height, this.targetFrameRate);
    }
  }
}
