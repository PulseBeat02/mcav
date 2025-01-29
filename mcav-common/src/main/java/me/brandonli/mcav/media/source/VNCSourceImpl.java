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

import me.brandonli.mcav.media.player.metadata.VideoMetadata;

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
  private final VideoMetadata videoMetadata;
  private final String name;

  VNCSourceImpl(final String host, final int port, final String password, final VideoMetadata videoMetadata, final String name) {
    this.host = host;
    this.port = port;
    this.password = password;
    this.videoMetadata = videoMetadata;
    this.name = name;
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

  /**
   * {@inheritDoc}
   */
  @Override
  public VideoMetadata getVideoMetadata() {
    return this.videoMetadata;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getName() {
    return this.name;
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

    /**
     * Sets the video metadata.
     *
     * @param videoMetadata the video metadata
     * @return this builder
     */
    Builder videoMetadata(VideoMetadata videoMetadata);

    /**
     * Sets the name for this VNC connection.
     *
     * @param name the connection name
     * @return this builder
     */
    Builder name(String name);

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
    private VideoMetadata videoMetadata;
    private String name;

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
    public Builder videoMetadata(final VideoMetadata videoMetadata) {
      this.videoMetadata = videoMetadata;
      return this;
    }

    @Override
    public Builder name(final String name) {
      this.name = name;
      return this;
    }

    @Override
    public VNCSource build() {
      return new VNCSourceImpl(host, port, password, videoMetadata, name);
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
