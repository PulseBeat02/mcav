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
 * Represents an HTTP hosting interface for resource packs.
 */
public interface HttpHosting extends PackHosting {
  /**
   * The format string for the host URL, which includes placeholders for the hostname and port.
   */
  String HOST_URL = "http://%s:%s";

  /**
   * Returns the hostname of the HTTP server being used for hosting the resource pack.
   *
   * @return the hostname
   */
  String getHostName();

  /**
   * Retrieves the port number on which the server is configured to run.
   *
   * @return the port number
   */
  int getPort();

  /**
   * Constructs and returns the raw URL formatted with the current host name and port.
   *
   * @return the raw URL with the format {@code http://<hostName>:<port>}
   */
  @Override
  default String getRawUrl() {
    return String.format(HOST_URL, this.getHostName(), this.getPort());
  }
}
