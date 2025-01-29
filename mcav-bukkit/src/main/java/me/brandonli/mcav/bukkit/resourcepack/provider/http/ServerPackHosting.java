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
package me.brandonli.mcav.bukkit.resourcepack.provider.http;

import static java.util.Objects.requireNonNull;

import java.nio.file.Path;

/**
 * Concrete implementation of {@link HttpHosting} that hosts a resource pack zip file on a specific HTTP server.
 */
public class ServerPackHosting implements HttpHosting {

  private final Path zip;
  private final String hostName;
  private final int port;

  private FileHttpServer server;

  /**
   * Initializes a new instance of the {@code ServerPackHosting} class to host a resource pack zip file on a specific HTTP server.
   *
   * @param zip      the path to the resource pack ZIP file to be hosted
   * @param hostName the hostname of the server
   * @param port     the port number for the server
   */
  public ServerPackHosting(final Path zip, final String hostName, final int port) {
    this.zip = zip;
    this.hostName = requireNonNull(hostName);
    this.port = port;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void start() {
    this.server = new FileHttpServer(this.port, this.zip);
    this.server.start();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void shutdown() {
    if (this.server == null) {
      return;
    }
    this.server.stop();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Path getZip() {
    return this.zip;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getHostName() {
    return this.hostName;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getPort() {
    return this.port;
  }
}
