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

/**
 * Represents a VNC (Virtual Network Computing) source with properties such as
 * host, port, password, video metadata, and connection name.
 * This interface provides methods to access the necessary details
 * for establishing and identifying a VNC connection.
 */
public interface VNCSource extends DynamicSource {
  @Override
  default String getResource() {
    return "vnc://" + this.getHost() + ":" + this.getPort() + (this.getPassword() != null ? "@" + this.getPassword() : "");
  }

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

  int getScreenWidth();

  int getScreenHeight();

  int getTargetFrameRate();

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
