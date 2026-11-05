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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import java.nio.file.Path;

/**
 * Hosts a resource pack on a dedicated HTTP server that listens on its own port.
 *
 * <p>Use this when the Minecraft port cannot be shared, for example behind a proxy. The port must be reachable by
 * the players, so it usually has to be opened in the firewall and forwarded. The resource pack file is read for
 * every download, so it can be rebuilt while the server is running.
 *
 * <p>The advertised host name affects the URL only; the listener binds the configured port on all local interfaces.
 * This class is thread-safe: start and shutdown are serialized by the owned server. Shutdown waits for its listener and
 * event loops to close, so avoid calling it from an event-loop callback. The instance can be started again afterward.
 * The source file remains caller-owned, and file/read failures are reported to downloaders rather than checked when the
 * listener starts.
 *
 * @since 1.0.0
 */
public final class ServerPackHosting implements HttpHosting {

  private static final int MAXIMUM_PORT = 65535;

  private final Path zip;
  private final String hostName;
  private final int port;
  private final FileHttpServer server;

  /**
   * Creates a hosting that serves {@code zip} on {@code port}. Nothing is served until
   * {@link #start()} is called.
   *
   * @param zip      the path to the resource pack zip to serve
   * @param hostName the host name or address players use to reach this server
   * @param port     the port to listen on, from 1 to 65535
   * @throws IllegalArgumentException if the port is out of range
   * @throws NullPointerException     if the zip path or advertised host name is null
   * @since 1.0.0
   */
  public ServerPackHosting(final Path zip, final String hostName, final int port) {
    this(zip, hostName, port, new FileHttpServer(port, zip));
  }

  @VisibleForTesting
  ServerPackHosting(final Path zip, final String hostName, final int port, final FileHttpServer server) {
    Preconditions.checkNotNull(zip, "Resource pack path must not be null!");
    Preconditions.checkNotNull(hostName, "Host name must not be null!");
    Preconditions.checkArgument(port > 0 && port <= MAXIMUM_PORT, "Port %s must be between 1 and %s inclusive!", port, MAXIMUM_PORT);
    Preconditions.checkNotNull(server, "Server must not be null!");

    this.zip = zip;
    this.hostName = hostName;
    this.port = port;
    this.server = server;
  }

  /**
   * Starts the HTTP server. Calling this method while the server is running has no effect.
   *
   * @throws HttpServerException if the port is already in use or cannot be bound
   * @since 1.0.0
   */
  @Override
  public void start() {
    this.server.start();
  }

  /**
   * Stops the HTTP server. Calling this method while the server is stopped has no effect.
   *
   * @since 1.0.0
   */
  @Override
  public void shutdown() {
    this.server.stop();
  }

  /**
   * Returns the resource pack zip that is served. The file is read again for every download.
   *
   * @return the path to the resource pack zip
   * @since 1.0.0
   */
  @Override
  public Path getZip() {
    return this.zip;
  }

  /**
   * Returns the host name or address players use to reach this server, as passed to the constructor.
   *
   * @return the host name
   * @since 1.0.0
   */
  @Override
  public String getHostName() {
    return this.hostName;
  }

  /**
   * Returns the port players use to reach this server, as passed to the constructor.
   *
   * @return the port, from 1 to 65535
   * @since 1.0.0
   */
  @Override
  public int getPort() {
    return this.port;
  }
}
