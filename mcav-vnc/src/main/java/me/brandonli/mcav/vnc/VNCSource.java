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
 * requested from the server. The default builder produces immutable values that can be shared across threads.
 * Creating a source performs no network I/O and does not create a connection.
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
   * @return the TCP port, from 1 to 65535
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
   * @return the nonnegative configured width in pixels, or 0 to use each remote frame's width
   */
  int getScreenWidth();

  /**
   * Gets the height the frames are scaled to.
   *
   * @return the nonnegative configured height in pixels, or 0 to use each remote frame's height
   */
  int getScreenHeight();

  /**
   * Gets the frame rate requested from the server. The server sends fewer frames when nothing changes.
   *
   * @return the positive requested frame rate in frames per second; this is not a delivery guarantee
   */
  int getTargetFrameRate();

  /**
   * Builds a diagnostic resource identifier from the host and port, without credentials.
   *
   * @return {@code vnc://} followed by the host verbatim, a colon and the decimal port; this does not
   *         escape or bracket the host and is not a general-purpose URI serializer
   */
  @Override
  default String getResource() {
    final String host = this.getHost();
    final int port = this.getPort();
    return "vnc://" + host + ":" + port;
  }

  /**
   * Gets the source kind.
   *
   * @return {@code vnc}
   */
  @Override
  default String getName() {
    return "vnc";
  }

  /**
   * Collects the settings of a {@link VNCSource}. The host is required; the port defaults to
   * {@link #DEFAULT_PORT}, the frame size to the size of the remote screen, and the frame rate to
   * {@link #DEFAULT_FRAME_RATE}. Builders are mutable and not thread-safe; built sources keep their own settings.
   */
  interface Builder {
    /**
     * Sets the host name or address of the server.
     *
     * @param host the non-null, nonblank host name or address, retained without trimming
     * @return this builder
     * @throws NullPointerException if {@code host} is null
     * @throws IllegalArgumentException if {@code host} is blank
     */
    Builder host(final String host);

    /**
     * Sets the port of the server.
     *
     * @param port the port from 1 to 65535
     * @return this builder
     * @throws IllegalArgumentException if {@code port} is outside 1 through 65535
     */
    Builder port(final int port);

    /**
     * Sets the user name sent to the server.
     *
     * @param username the non-null user name; an empty string makes the default player omit this credential
     * @return this builder
     * @throws NullPointerException if {@code username} is null
     */
    Builder username(final String username);

    /**
     * Sets the password sent to the server.
     *
     * @param password the non-null password; an empty string makes the default player omit this credential
     * @return this builder
     * @throws NullPointerException if {@code password} is null
     */
    Builder password(final String password);

    /**
     * Sets the width the frames are scaled to.
     *
     * @param width the width in pixels, or 0 to keep the width of the remote screen
     * @return this builder
     * @throws IllegalArgumentException if {@code width} is negative
     */
    Builder screenWidth(final int width);

    /**
     * Sets the height the frames are scaled to.
     *
     * @param height the height in pixels, or 0 to keep the height of the remote screen
     * @return this builder
     * @throws IllegalArgumentException if {@code height} is negative
     */
    Builder screenHeight(final int height);

    /**
     * Sets the frame rate requested from the server.
     *
     * @param targetFrameRate the strictly positive frame rate in frames per second
     * @return this builder
     * @throws IllegalArgumentException if {@code targetFrameRate} is zero or negative
     */
    Builder targetFrameRate(final int targetFrameRate);

    /**
     * Builds the source.
     *
     * @return a new immutable source; subsequent builder changes do not affect it
     * @throws IllegalStateException if no host was set
     */
    VNCSource build();
  }
}
