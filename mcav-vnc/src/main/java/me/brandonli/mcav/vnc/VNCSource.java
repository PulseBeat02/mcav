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

import me.brandonli.mcav.media.source.DynamicSource;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A VNC server to stream: its address, optional credentials, the size frames are scaled to, and the frame rate
 * requested from the server.
 *
 * <pre><code>
 *   final VNCSource.Builder builder = VNCSource.builder();
 *   builder.host("localhost");
 *   builder.port(5900);
 *   builder.password("secret");
 *   builder.screenWidth(1280);
 *   builder.screenHeight(720);
 *   builder.targetFrameRate(30);
 *   final VNCSource source = builder.build();
 * </code></pre>
 */
public interface VNCSource extends DynamicSource {
  /**
   * The port VNC servers listen on unless configured otherwise.
   */
  int DEFAULT_PORT = 5900;

  /**
   * The frame rate requested when none is set.
   */
  int DEFAULT_FRAME_RATE = 30;

  /**
   * Creates a builder for a source.
   *
   * @return the builder
   */
  static Builder builder() {
    return new VNCSourceImpl.BuilderImpl();
  }

  /**
   * Gets the host name or address of the server.
   *
   * @return the host
   */
  String getHost();

  /**
   * Gets the port of the server.
   *
   * @return the port
   */
  int getPort();

  /**
   * Gets the user name for servers that require one.
   *
   * @return the user name, or null if none is sent
   */
  @Nullable String getUsername();

  /**
   * Gets the password for servers that require one.
   *
   * @return the password, or null if none is sent
   */
  @Nullable String getPassword();

  /**
   * Gets the width the frames are scaled to.
   *
   * @return the width in pixels
   */
  int getScreenWidth();

  /**
   * Gets the height the frames are scaled to.
   *
   * @return the height in pixels
   */
  int getScreenHeight();

  /**
   * Gets the frame rate requested from the server. The server sends fewer frames when nothing changes.
   *
   * @return the frame rate in frames per second
   */
  int getTargetFrameRate();

  @Override
  default String getResource() {
    final String host = this.getHost();
    final int port = this.getPort();
    return "vnc://" + host + ":" + port;
  }

  @Override
  default String getName() {
    return "vnc";
  }

  /**
   * Collects the settings of a {@link VNCSource}. The host is required; the port defaults to
   * {@link #DEFAULT_PORT}, the frame size to the size of the remote screen, and the frame rate to
   * {@link #DEFAULT_FRAME_RATE}.
   */
  interface Builder {
    /**
     * Sets the host name or address of the server.
     *
     * @param host the host
     * @return this builder
     */
    Builder host(final String host);

    /**
     * Sets the port of the server.
     *
     * @param port the port from 1 to 65535
     * @return this builder
     */
    Builder port(final int port);

    /**
     * Sets the user name sent to the server.
     *
     * @param username the user name
     * @return this builder
     */
    Builder username(final String username);

    /**
     * Sets the password sent to the server.
     *
     * @param password the password
     * @return this builder
     */
    Builder password(final String password);

    /**
     * Sets the width the frames are scaled to.
     *
     * @param width the width in pixels, or 0 to keep the width of the remote screen
     * @return this builder
     */
    Builder screenWidth(final int width);

    /**
     * Sets the height the frames are scaled to.
     *
     * @param height the height in pixels, or 0 to keep the height of the remote screen
     * @return this builder
     */
    Builder screenHeight(final int height);

    /**
     * Sets the frame rate requested from the server.
     *
     * @param targetFrameRate the frame rate in frames per second
     * @return this builder
     */
    Builder targetFrameRate(final int targetFrameRate);

    /**
     * Builds the source.
     *
     * @return the source
     * @throws IllegalStateException if no host was set
     */
    VNCSource build();
  }
}
