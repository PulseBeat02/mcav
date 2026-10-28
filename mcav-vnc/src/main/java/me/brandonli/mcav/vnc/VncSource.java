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

import java.util.Optional;
import me.brandonli.mcav.media.source.DynamicSource;

/**
 * A VNC server to stream: its address, optional credentials, the size frames are scaled to, and the frame rate
 * requested from the server. The default builder produces immutable values that can be shared across threads.
 * Creating a source performs no network I/O and does not create a connection.
 *
 * {@snippet :
 * final String host = "localhost";
 * final String password = "secret";
 * final int width = 1280;
 * final int height = 720;
 * final VncSourceBuilder builder = VncSource.builder();
 * final VncSource source = builder.host(host).password(password).screenWidth(width).screenHeight(height).build();
 * }
 *
 * <p>Implementations in this library are immutable and thread-safe.
 *
 * @since 1.0.0
 */
public interface VncSource extends DynamicSource {
  /**
   * The port VNC servers listen on unless configured otherwise.
   *
   * @since 1.0.0
   */
  int DEFAULT_PORT = 5900;

  /**
   * The frame rate requested when none is set.
   *
   * @since 1.0.0
   */
  int DEFAULT_FRAME_RATE = 30;

  /**
   * Creates a builder for a source.
   *
   * @return the builder
   * @since 1.0.0
   */
  static VncSourceBuilder builder() {
    return new DefaultVncSourceBuilder();
  }

  /**
   * Returns the host name or address of the server.
   *
   * @return the host
   * @since 1.0.0
   */
  String getHost();

  /**
   * Returns the port of the server.
   *
   * @return the TCP port, from 1 to 65535
   * @since 1.0.0
   */
  int getPort();

  /**
   * Returns the user name for servers that require one.
   *
   * @return the user name, or empty if none was set
   * @since 1.0.0
   */
  Optional<String> getUsername();

  /**
   * Returns the password for servers that require one.
   *
   * @return the password, or empty if none was set
   * @since 1.0.0
   */
  Optional<String> getPassword();

  /**
   * Returns the width the frames are scaled to.
   *
   * @return the nonnegative configured width in pixels, or 0 to use each remote frame's width
   * @since 1.0.0
   */
  int getScreenWidth();

  /**
   * Returns the height the frames are scaled to.
   *
   * @return the nonnegative configured height in pixels, or 0 to use each remote frame's height
   * @since 1.0.0
   */
  int getScreenHeight();

  /**
   * Returns the frame rate requested from the server. The server sends fewer frames when nothing changes.
   *
   * @return the positive requested frame rate in frames per second; this is not a delivery guarantee
   * @since 1.0.0
   */
  int getTargetFrameRate();

  /**
   * Builds a diagnostic resource identifier from the host and port, without credentials.
   *
   * @return {@code vnc://} followed by the host verbatim, a colon and the decimal port; this does not
   *         escape or bracket the host and is not a general-purpose URI serializer
   * @since 1.0.0
   */
  @Override
  default String getResource() {
    final String host = this.getHost();
    final int port = this.getPort();
    return "vnc://%s:%s".formatted(host, port);
  }

  /**
   * Returns the source kind.
   *
   * @return {@code vnc}
   * @since 1.0.0
   */
  @Override
  default String getName() {
    return "vnc";
  }
}
