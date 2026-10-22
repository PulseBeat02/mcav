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
package me.brandonli.mcav.http;

import java.net.InetAddress;
import java.nio.file.Path;

/**
 * Configures an {@link HttpResult} setting by setting, including the settings the factories of
 * {@link HttpResult} do not offer, such as the network interface the server listens on. Create one with
 * {@link HttpResult#builder()}. Every setting has a default:
 *
 * <ul>
 *   <li>domain: {@code localhost}</li>
 *   <li>port: 80</li>
 *   <li>web page: the copy bundled in the jar</li>
 *   <li>bind address: every network interface, because the browsers of players connect from other machines</li>
 * </ul>
 *
 * <p>For example, a server behind a reverse proxy on the same machine only needs the loopback interface:
 *
 * <pre><code>
 *   final HttpResultBuilder builder = HttpResult.builder();
 *   builder.domain("play.example.com");
 *   builder.port(8080);
 *   final InetAddress loopback = InetAddress.getLoopbackAddress();
 *   builder.bindAddress(loopback);
 *   final HttpResult http = builder.build();
 * </code></pre>
 *
 * <p>A builder can build several servers; changing it afterwards does not affect servers built before. Builders
 * are not thread-safe.
 */
public interface HttpResultBuilder {
  /**
   * Sets the host name listeners use, which decides the URL returned by {@link HttpResult#getFullUrl()}. It does
   * not decide which network interface the server listens on; see {@link #bindAddress(InetAddress)}.
   *
   * @param domain the non-null, nonblank host name or IP address, such as {@code play.example.com};
   *               it is stored unchanged and must not include a scheme or path
   * @return this builder
   * @throws IllegalArgumentException if {@code domain} is blank
   * @throws NullPointerException     if {@code domain} is null
   */
  HttpResultBuilder domain(final String domain);

  /**
   * Sets the port. Ports below 1024 may require elevated privileges depending on the host configuration.
   *
   * @param port the port, from 1 to 65535
   * @return this builder
   * @throws IllegalArgumentException if {@code port} is outside 1 through 65535
   */
  HttpResultBuilder port(final int port);

  /**
   * Serves the web page from a directory instead of the copy bundled in the jar, which is useful while developing
   * the page. Files outside the directory are never served.
   *
   * @param directory the non-null directory with the built page, such as {@code mcav-website/out};
   *                  existence is not checked here
   * @return this builder
   * @throws NullPointerException if {@code directory} is null
   */
  HttpResultBuilder directory(final Path directory);

  /**
   * Makes the server listen on one network interface only, instead of every interface of the machine. Use the
   * loopback address when a reverse proxy on the same machine forwards the players, or the address of one
   * network card to keep the server off the others.
   *
   * @param address a non-null local address of this machine, such as {@link InetAddress#getLoopbackAddress()};
   *                binding is attempted when the built server starts
   * @return this builder
   * @throws NullPointerException if {@code address} is null
   */
  HttpResultBuilder bindAddress(final InetAddress address);

  /**
   * Creates a server with the current settings.
   *
   * @return the server, not started yet
   */
  HttpResult build();
}
