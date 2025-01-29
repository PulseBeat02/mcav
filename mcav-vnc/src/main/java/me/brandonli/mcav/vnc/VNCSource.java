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
package me.brandonli.mcav.vnc;

import me.brandonli.mcav.media.source.DynamicSource;

/**
 * Represents a VNC (Virtual Network Computing) source.
 */
public interface VNCSource extends DynamicSource {
  /**
   * {@inheritDoc}
   */
  @Override
  default String getResource() {
    return "vnc://" + this.getHost() + ":" + this.getPort() + (this.getPassword() != null ? "@" + this.getPassword() : "");
  }

  /**
   * Gets the username for the VNC connection.
   *
   * @return the username for the VNC connection
   */
  String getUsername();

  /**
   * Gets the VNC server hostname or IP address.
   *
   * @return the VNC server host
   */
  String getHost();

  /**
   * Gets the VNC server port.
   *
   * @return the VNC server port
   */
  int getPort();

  /**
   * Gets the VNC password if required.
   *
   * @return the password or null if not needed
   */
  String getPassword();

  /**
   * Gets the width of the VNC screen.
   *
   * @return the screen width
   */
  int getScreenWidth();

  /**
   * Gets the height of the VNC screen.
   *
   * @return the screen height
   */
  int getScreenHeight();

  /**
   * Gets the target frame rate for the VNC connection.
   *
   * @return the target frame rate
   */
  int getTargetFrameRate();

  /**
   * {@inheritDoc}
   */
  @Override
  default String getName() {
    return "vnc";
  }

  /**
   * Creates a new {@link VNCSourceImpl.Builder} instance to facilitate the
   * configuration and construction of a {@link VNCSource} object.
   *
   * @return a new instance of {@link VNCSourceImpl.Builder}
   */
  static VNCSourceImpl.Builder vnc() {
    return VNCSourceImpl.builder();
  }
}
