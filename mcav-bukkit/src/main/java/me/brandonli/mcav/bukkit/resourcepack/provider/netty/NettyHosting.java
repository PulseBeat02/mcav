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
package me.brandonli.mcav.bukkit.resourcepack.provider.netty;

import java.nio.file.Path;
import me.brandonli.mcav.bukkit.utils.NetworkUtils;
import org.bukkit.Bukkit;
import org.checkerframework.checker.initialization.qual.UnderInitialization;

/**
 * Represents the Netty hosting implementation for the Bukkit server. Injects into the Netty pipeline.
 */
public final class NettyHosting implements InjectorHosting {

  private final Path zip;
  private final String url;

  /**
   * Constructs a new NettyHosting instance with the specified zip file.
   *
   * @param zip the path to the zip file containing the Netty injector
   */
  public NettyHosting(final Path zip) {
    this.zip = zip;
    this.url = this.getPackUrl();
  }

  private String getPackUrl(@UnderInitialization NettyHosting this) {
    final String ip = NetworkUtils.getPublicIPAddress();
    final int port = Bukkit.getPort();
    return "http://%s:%s".formatted(ip, port);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getRawUrl() {
    return this.url;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void start() {
    try {
      final ReflectBukkitInjector injector = new ReflectBukkitInjector(this.zip);
      injector.inject();
    } catch (final AssertionError e) {
      final ByteBuddyBukkitInjector injector = new ByteBuddyBukkitInjector(this.zip);
      injector.injectAgentIntoServer();
      throw new InjectorException(e.getMessage(), e);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void shutdown() {}

  /**
   * {@inheritDoc}
   */
  @Override
  public Path getZip() {
    return this.zip;
  }
}
