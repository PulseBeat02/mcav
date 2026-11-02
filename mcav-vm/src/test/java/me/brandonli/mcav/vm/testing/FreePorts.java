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
package me.brandonli.mcav.vm.testing;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;

public final class FreePorts {

  private static final InetAddress LOOPBACK = InetAddress.ofLiteral("127.0.0.1");

  private static final int EPHEMERAL_PORT = 0;
  private static final int BACKLOG = 1;

  private FreePorts() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated!");
  }

  public static int find() {
    try (final ServerSocket socket = new ServerSocket(EPHEMERAL_PORT, BACKLOG, LOOPBACK)) {
      return socket.getLocalPort();
    } catch (final IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }
}
