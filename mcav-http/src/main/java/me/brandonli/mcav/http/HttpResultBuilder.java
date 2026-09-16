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

import com.google.common.base.Preconditions;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.List;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Configures an {@link HttpResult} setting by setting, including the settings the factories of
 * {@link HttpResult} do not offer, such as the network interface the server listens on. Create one with
 * {@link HttpResult#builder()}. Every setting has a default:
 *
 * <ul>
 *   <li>domain: {@value #DEFAULT_DOMAIN}</li>
 *   <li>port: {@value #DEFAULT_PORT}</li>
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
public final class HttpResultBuilder {

  /**
   * The host name used when none is set.
   */
  static final String DEFAULT_DOMAIN = "localhost";

  /**
   * The port used when none is set.
   */
  static final int DEFAULT_PORT = 80;

  private String domain;
  private int port;
  private @Nullable Path directory;
  private @Nullable InetAddress bindAddress;

  HttpResultBuilder() {
    this.domain = DEFAULT_DOMAIN;
    this.port = DEFAULT_PORT;
  }

  /**
   * Sets the host name listeners use, which decides the URL returned by {@link HttpResult#getFullUrl()}. It does
   * not decide which network interface the server listens on; see {@link #bindAddress(InetAddress)}.
   *
   * @param domain the host name or IP address, such as {@code play.example.com}
   * @return this builder
   */
  public HttpResultBuilder domain(final String domain) {
    Preconditions.checkNotNull(domain, "Domain must not be null");
    Preconditions.checkArgument(!domain.isBlank(), "Domain must not be blank");
    this.domain = domain;
    return this;
  }

  /**
   * Sets the port. On Linux and macOS, ports below 1024 need administrator rights, so use a higher port.
   *
   * @param port the port, from 1 to 65535
   * @return this builder
   */
  public HttpResultBuilder port(final int port) {
    Preconditions.checkArgument(port > 0 && port <= 65535, "Port must be between 1 and 65535 but was %s", port);
    this.port = port;
    return this;
  }

  /**
   * Serves the web page from a directory instead of the copy bundled in the jar, which is useful while developing
   * the page. Files outside the directory are never served.
   *
   * @param directory the directory with the built page, such as {@code mcav-website/out}
   * @return this builder
   */
  public HttpResultBuilder directory(final Path directory) {
    Preconditions.checkNotNull(directory, "Directory must not be null");
    this.directory = directory;
    return this;
  }

  /**
   * Makes the server listen on one network interface only, instead of every interface of the machine. Use the
   * loopback address when a reverse proxy on the same machine forwards the players, or the address of one
   * network card to keep the server off the others.
   *
   * @param address a local address of this machine, such as {@link InetAddress#getLoopbackAddress()}
   * @return this builder
   */
  public HttpResultBuilder bindAddress(final InetAddress address) {
    Preconditions.checkNotNull(address, "Bind address must not be null");
    this.bindAddress = address;
    return this;
  }

  /**
   * Creates a server with the current settings.
   *
   * @return the server, not started yet
   */
  public HttpResult build() {
    return new HttpResultImpl(this.domain, this.port, this.directory, this.bindAddress, List.of());
  }
}
