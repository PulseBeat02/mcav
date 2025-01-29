/*
 * This file is part of mcav, a media playback library for Minecraft
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
package me.brandonli.mcav.media.source;

import com.google.common.base.Preconditions;

/**
 * Implementation of the {@link VNCSource} interface, representing a VNC (Virtual Network Computing)
 * source with specific host, port, password, video metadata, and a connection name.
 * <p>
 * This class encapsulates all the required properties for a VNC connection and provides
 * basic accessor methods to retrieve these properties.
 */
public class VNCSourceImpl implements VNCSource {

  private final String host;
  private final int port;
  private final String password;
  private final int width;
  private final int height;
  private final int targetFrameRate;

  VNCSourceImpl(final String host, final int port, final String password, final int width, final int height, final int targetFrameRate) {
    this.host = host;
    this.port = port;
    this.password = password;
    this.width = width;
    this.height = height;
    this.targetFrameRate = targetFrameRate;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getHost() {
    return this.host;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getPort() {
    return this.port;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getPassword() {
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

  /**
   * {@inheritDoc}
   */
  @Override
  public int getTargetFrameRate() {
    return this.targetFrameRate;
  }

  /**
   * Abstract builder interface for creating VNCSource instances.
   */
  public interface Builder {
    /**
     * Sets the VNC server hostname or IP address.
     *
     * @param host the VNC server host
     * @return this builder
     */
    Builder host(String host);

    /**
     * Sets the VNC server port.
     *
     * @param port the VNC server port
     * @return this builder
     */
    Builder port(int port);

    /**
     * Sets the VNC password.
     *
     * @param password the password for VNC authentication
     * @return this builder
     */
    Builder password(String password);

    Builder screenWidth(int width);

    Builder screenHeight(int height);

    Builder targetFrameRate(int targetFrameRate);

    /**
     * Builds a new VNCSource instance with the configured properties.
     *
     * @return a new VNCSource instance
     */
    VNCSource build();
  }

  /**
   * Implementation of the Builder interface for VNCSourceImpl.
   */
  public static class BuilderImpl implements Builder {

    private String host;
    private int port;
    private String password;
    private int width;
    private int height;
    private int targetFrameRate;

    @Override
    public Builder host(final String host) {
      this.host = host;
      return this;
    }

    @Override
    public Builder port(final int port) {
      this.port = port;
      return this;
    }

    @Override
    public Builder password(final String password) {
      this.password = password;
      return this;
    }

    @Override
    public Builder screenWidth(final int width) {
      this.width = width;
      return this;
    }

    @Override
    public Builder screenHeight(final int height) {
      this.height = height;
      return this;
    }

    @Override
    public Builder targetFrameRate(final int targetFrameRate) {
      this.targetFrameRate = targetFrameRate;
      return this;
    }

    @Override
    public VNCSource build() {
      Preconditions.checkNotNull(this.host);
      Preconditions.checkArgument(this.port > 0 && this.port <= 65535, "Port must be between 1 and 65535");
      Preconditions.checkArgument(this.width >= 0, "Width must be non-negative");
      Preconditions.checkArgument(this.height >= 0, "Height must be non-negative");
      Preconditions.checkArgument(this.targetFrameRate > 0, "Target frame rate must be positive");
      return new VNCSourceImpl(this.host, this.port, this.password, this.width, this.height, this.targetFrameRate);
    }
  }

  /**
   * Creates a new builder for VNCSource.
   *
   * @return a new builder instance
   */
  static Builder builder() {
    return new BuilderImpl();
  }
}
