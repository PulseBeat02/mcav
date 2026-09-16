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
package me.brandonli.mcav.bukkit.resourcepack.provider.http;

import me.brandonli.mcav.bukkit.resourcepack.provider.PackHosting;

/**
 * Hosts a resource pack on a dedicated HTTP server with its own host name and port.
 */
public interface HttpHosting extends PackHosting {
  /**
   * The format of the URL of the resource pack, with placeholders for the host name and the port.
   */
  String HOST_URL = "http://%s:%s";

  /**
   * Gets the host name or address players use to reach the HTTP server.
   *
   * @return the host name
   */
  String getHostName();

  /**
   * Gets the port the HTTP server listens on.
   *
   * @return the port
   */
  int getPort();

  /**
   * Gets the URL of the resource pack, built from the host name and the port.
   *
   * @return the URL in the format {@code http://<hostName>:<port>}
   */
  @Override
  default String getRawUrl() {
    final String hostName = this.getHostName();
    final int port = this.getPort();
    return HOST_URL.formatted(hostName, port);
  }
}
