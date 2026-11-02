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
import org.checkerframework.checker.nullness.qual.Nullable;

final class DefaultVncSourceBuilder implements VncSourceBuilder {

  private static final int MINIMUM_PORT = 1;
  private static final int MAXIMUM_PORT = 65535;

  private @Nullable String host;
  private int port;
  private @Nullable String username;
  private @Nullable String password;
  private int width;
  private int height;
  private int targetFrameRate;

  DefaultVncSourceBuilder() {
    this.port = VncSource.DEFAULT_PORT;
    this.targetFrameRate = VncSource.DEFAULT_FRAME_RATE;
  }

  @Override
  public VncSourceBuilder host(final String host) {
    Preconditions.checkNotNull(host, "Host must not be null!");
    Preconditions.checkArgument(!host.isBlank(), "Host must not be blank!");

    this.host = host;
    return this;
  }

  @Override
  public VncSourceBuilder port(final int port) {
    Preconditions.checkArgument(port >= MINIMUM_PORT && port <= MAXIMUM_PORT, "Port %s must be between 1 and 65535!", port);

    this.port = port;
    return this;
  }

  @Override
  public VncSourceBuilder username(final String username) {
    Preconditions.checkNotNull(username, "Username must not be null!");

    this.username = username;
    return this;
  }

  @Override
  public VncSourceBuilder password(final String password) {
    Preconditions.checkNotNull(password, "Password must not be null!");

    this.password = password;
    return this;
  }

  @Override
  public VncSourceBuilder screenWidth(final int width) {
    Preconditions.checkArgument(width >= 0, "Width %s must not be negative!", width);

    this.width = width;
    return this;
  }

  @Override
  public VncSourceBuilder screenHeight(final int height) {
    Preconditions.checkArgument(height >= 0, "Height %s must not be negative!", height);

    this.height = height;
    return this;
  }

  @Override
  public VncSourceBuilder targetFrameRate(final int targetFrameRate) {
    Preconditions.checkArgument(targetFrameRate > 0, "Frame rate %s must be positive!", targetFrameRate);

    this.targetFrameRate = targetFrameRate;
    return this;
  }

  @Override
  public VncSource build() {
    final String configuredHost = this.host;
    if (configuredHost == null) {
      final String message = "Host must be set before the source is built!";
      throw new IllegalStateException(message);
    }

    return new DefaultVncSource(configuredHost, this.port, this.username, this.password, this.width, this.height, this.targetFrameRate);
  }
}
