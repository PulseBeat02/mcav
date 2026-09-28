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
package me.brandonli.mcav.sandbox.utils;

import java.nio.file.Path;
import java.util.function.Function;
import me.brandonli.mcav.bukkit.resourcepack.provider.PackHosting;
import me.brandonli.mcav.bukkit.utils.ServerAddress;

/**
 * Where the players download the MCV2 resource pack from, {@code mcv2.pack.hosting} of the configuration.
 */
public enum Mcv2Hosting {
  /**
   * The Minecraft server's own port: nothing to set up, and the right choice for a server players join directly. It
   * does not work behind a proxy such as Velocity or BungeeCord, where the players' connections reach the proxy's
   * port, not this server's.
   */
  INJECTOR,
  /**
   * A dedicated HTTP server on a port of its own, which the players must be able to reach: the choice behind a proxy
   * when the port can be opened.
   */
  HTTP,
  /**
   * An upload to mc-packs.net: no port at all, for a server behind a proxy or a firewall that opens none. The pack is
   * public there.
   */
  WEBSITE;

  /**
   * Gets the hosting of a written pack.
   *
   * @param host the host name or address players reach the HTTP server by, for {@link #HTTP}; empty for the address
   *             the server finds for itself, looked up when a pack is hosted
   * @param port the port of the HTTP server, for {@link #HTTP}
   * @return what hosts a pack zip
   */
  public Function<Path, PackHosting> hosting(final String host, final int port) {
    return switch (this) {
      case INJECTOR -> PackHosting::injector;
      case HTTP -> zip -> PackHosting.http(zip, host.isEmpty() ? ServerAddress.getPublicIPAddress() : host, port);
      case WEBSITE -> PackHosting::website;
    };
  }
}
