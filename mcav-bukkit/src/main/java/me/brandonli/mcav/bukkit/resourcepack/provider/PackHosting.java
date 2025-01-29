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
package me.brandonli.mcav.bukkit.resourcepack.provider;

import java.nio.file.Path;
import me.brandonli.mcav.bukkit.resourcepack.provider.http.HttpHosting;
import me.brandonli.mcav.bukkit.resourcepack.provider.http.ServerPackHosting;
import me.brandonli.mcav.bukkit.resourcepack.provider.netty.InjectorHosting;
import me.brandonli.mcav.bukkit.resourcepack.provider.netty.NettyHosting;

/**
 * Represents a hosting solution for resource packs.
 * This interface is designed to outline the necessary functions and facilitate different implementations
 * (e.g., HTTP-based hosting or website-based hosting).
 */
public interface PackHosting {
  /**
   * Retrieves the raw URL of the resource pack hosted by this implementation.
   *
   * @return the raw, unmodified URL as a string
   */
  String getRawUrl();

  /**
   * Starts the resource pack hosting service. This method is responsible for initiating
   * the hosting process based on the specific implementation.
   * <p>
   * In
   */
  void start();

  /**
   * Shuts down the resource pack hosting service.
   * This method is responsible for stopping or deactivating the hosting process that
   * was previously started using the start method. It should release any resources,
   * terminate background processes, or close connections associated with the hosting.
   * The specific shutdown behavior depends on the implementation of the interface
   */
  void shutdown();

  /**
   * Retrieves the {@code Path} to the ZIP file associated with the resource pack.
   *
   * @return the file system path of the ZIP file containing the resource pack
   */
  Path getZip();

  /**
   * Creates an HTTP-based implementation of the {@code PackHosting} interface.
   * This method returns an instance of {@code ServerPackHosting}, which handles
   * hosting of resource packs over HTTP at the specified host name and port.
   *
   * @param path     the path to the resource pack ZIP file to be hosted
   * @param hostName the host name for the server (e.g., "localhost" or an IP address)
   * @param port     the port number on which the server will run
   * @return an instance of {@code PackHosting} for hosting the resource pack
   */
  static HttpHosting http(final Path path, final String hostName, final int port) {
    return new ServerPackHosting(path, hostName, port);
  }

  /**
   * Provides a resource pack hosting implementation that interfaces with an external website.
   *
   * @param path the path to the resource pack file to be hosted
   * @return an implementation of {@code PackHosting} specific to website-based hosting
   */
  static WebsiteHosting website(final Path path) {
    return new MCPackHosting(path);
  }

  /**
   * Provides an implementation of {@code InjectorHosting} for hosting resource packs using
   * injection mechanisms. This method creates and returns an instance of {@code NettyHosting},
   * which applies advanced injection techniques for hosting.
   *
   * @param path the file system path to the resource pack ZIP file to be hosted
   * @return an instance of {@code InjectorHosting} specialized for injection-based hosting
   */
  static InjectorHosting injector(final Path path) {
    return new NettyHosting(path);
  }
}
