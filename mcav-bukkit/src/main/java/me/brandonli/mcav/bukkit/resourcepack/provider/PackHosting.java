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

import com.google.common.base.Preconditions;
import java.nio.file.Path;
import me.brandonli.mcav.bukkit.resourcepack.provider.http.HttpHosting;
import me.brandonli.mcav.bukkit.resourcepack.provider.http.ServerPackHosting;
import me.brandonli.mcav.bukkit.resourcepack.provider.netty.InjectorHosting;
import me.brandonli.mcav.bukkit.resourcepack.provider.netty.NettyHosting;

/**
 * Makes a resource pack zip downloadable for players, so its URL can be sent to them, for example in a
 * {@link net.kyori.adventure.resource.ResourcePackRequest} with
 * {@link net.kyori.adventure.audience.Audience#sendResourcePacks(net.kyori.adventure.resource.ResourcePackRequest)}.
 *
 * <p>Three strategies are available:
 *
 * <ul>
 *   <li>{@link #injector(Path)} serves the pack on the port of the Minecraft server itself. This needs no setup and
 *       is the best choice for most servers, but it does not work behind a proxy.</li>
 *   <li>{@link #http(Path, String, int)} serves the pack from a dedicated HTTP server on its own port.</li>
 *   <li>{@link #website(Path)} uploads the pack to mc-packs.net, which needs no open port at all.</li>
 * </ul>
 *
 * <p>Call {@link #start()} before sending the URL to players and {@link #shutdown()} when the pack is no longer
 * needed.
 */
public interface PackHosting {
  /**
   * Gets the URL players download the resource pack from.
   *
   * @return the URL of the resource pack
   * @throws IllegalStateException if the URL is only known after {@link #start()} and the hosting was not started
   */
  String getRawUrl();

  /**
   * Starts hosting the resource pack. Depending on the strategy, this may block while a server binds or the pack
   * is uploaded, so avoid calling it on the main thread.
   */
  void start();

  /**
   * Stops hosting the resource pack and releases all resources.
   */
  void shutdown();

  /**
   * Gets the resource pack zip that is hosted.
   *
   * @return the path to the resource pack zip
   */
  Path getZip();

  /**
   * Creates a hosting strategy that serves the resource pack from a dedicated HTTP server on its own port.
   *
   * @param path     the path to the resource pack zip
   * @param hostName the host name or address players use to reach the server
   * @param port     the port the HTTP server listens on, from 1 to 65535
   * @return the hosting strategy
   * @throws IllegalArgumentException if the port is out of range
   */
  static HttpHosting http(final Path path, final String hostName, final int port) {
    Preconditions.checkNotNull(path, "Resource pack path must not be null");
    Preconditions.checkNotNull(hostName, "Host name must not be null");
    return new ServerPackHosting(path, hostName, port);
  }

  /**
   * Creates a hosting strategy that uploads the resource pack to mc-packs.net.
   *
   * @param path the path to the resource pack zip
   * @return the hosting strategy
   */
  static WebsiteHosting website(final Path path) {
    Preconditions.checkNotNull(path, "Resource pack path must not be null");
    return new MCPackHosting(path);
  }

  /**
   * Creates a hosting strategy that serves the resource pack on the port of the Minecraft server.
   *
   * @param path the path to the resource pack zip
   * @return the hosting strategy
   */
  static InjectorHosting injector(final Path path) {
    Preconditions.checkNotNull(path, "Resource pack path must not be null");
    return new NettyHosting(path);
  }
}
