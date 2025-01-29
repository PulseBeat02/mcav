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
package me.brandonli.mcav.bukkit.resourcepack.provider;

import java.nio.file.Path;
import me.brandonli.mcav.bukkit.resourcepack.provider.http.HttpHosting;
import me.brandonli.mcav.bukkit.resourcepack.provider.http.ServerPackHosting;
import me.brandonli.mcav.bukkit.resourcepack.provider.netty.InjectorHosting;
import me.brandonli.mcav.bukkit.resourcepack.provider.netty.NettyHosting;

/**
 * Represents a hosting solution for resource packs.
 */
public interface PackHosting {
  /**
   * Retrieves the raw URL of the resource pack hosted by this implementation.
   *
   * @return the raw, unmodified URL as a string
   */
  String getRawUrl();

  /**
   * Starts the resource pack hosting service.
   */
  void start();

  /**
   * Shuts down the resource pack hosting service.
   */
  void shutdown();

  /**
   * Retrieves the {@code Path} to the ZIP file associated with the resource pack.
   *
   * @return the file system path of the ZIP file containing the resource pack
   */
  Path getZip();

  /**
   * Provides a resource pack hosting implementation that serves the pack over HTTP.
   *
   * @param path     the path to the resource pack file to be hosted
   * @param hostName the hostname for the HTTP server
   * @param port     the port on which the HTTP server will listen
   * @return an implementation of {@code HttpHosting} for serving resource packs over HTTP
   */
  static HttpHosting http(final Path path, final String hostName, final int port) {
    return new ServerPackHosting(path, hostName, port);
  }

  /**
   * Provides a resource pack hosting implementation that interfaces with MCPack's website.
   *
   * @param path the path to the resource pack file to be hosted
   * @return an implementation of {@code PackHosting} specific to website-based hosting
   */
  static WebsiteHosting website(final Path path) {
    return new MCPackHosting(path);
  }

  /**
   * Provides a resource pack hosting implementation that uses Netty for serving the pack.
   *
   * @param path the path to the resource pack file to be hosted
   * @return an implementation of {@code InjectorHosting} for serving resource packs using Netty
   */
  static InjectorHosting injector(final Path path) {
    return new NettyHosting(path);
  }
}
