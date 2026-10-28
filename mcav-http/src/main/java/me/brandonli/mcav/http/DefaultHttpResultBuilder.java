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

final class DefaultHttpResultBuilder implements HttpResultBuilder {

  private static final String DEFAULT_DOMAIN = "localhost";
  private static final int DEFAULT_PORT = 80;

  private static final int MAXIMUM_PORT = 65535;

  private String domain;
  private int port;
  private @Nullable Path directory;
  private @Nullable InetAddress bindAddress;

  DefaultHttpResultBuilder() {
    this.domain = DEFAULT_DOMAIN;
    this.port = DEFAULT_PORT;
  }

  @Override
  public HttpResultBuilder domain(final String domain) {
    Preconditions.checkNotNull(domain, "Domain must not be null!");
    Preconditions.checkArgument(!domain.isBlank(), "Domain must not be blank!");

    this.domain = domain;
    return this;
  }

  @Override
  public HttpResultBuilder port(final int port) {
    Preconditions.checkArgument(port > 0 && port <= MAXIMUM_PORT, "Port %s must be between 1 and 65535!", port);

    this.port = port;
    return this;
  }

  @Override
  public HttpResultBuilder directory(final Path directory) {
    Preconditions.checkNotNull(directory, "Directory must not be null!");

    this.directory = directory;
    return this;
  }

  @Override
  public HttpResultBuilder bindAddress(final InetAddress address) {
    Preconditions.checkNotNull(address, "Bind address must not be null!");

    this.bindAddress = address;
    return this;
  }

  @Override
  public HttpResult build() {
    return new SpringHttpResult(this.domain, this.port, this.directory, this.bindAddress, List.of());
  }
}
