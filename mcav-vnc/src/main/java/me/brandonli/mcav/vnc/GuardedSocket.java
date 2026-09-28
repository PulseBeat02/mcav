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
package me.brandonli.mcav.vnc;

import com.google.common.base.Preconditions;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;

/**
 * A connected socket whose streams pass through an {@link RfbGuard}: the VNC client reads only what the guard let
 * through. The client uses a socket only for its streams, and closing this closes the connection.
 */
final class GuardedSocket extends Socket {

  private final Socket socket;

  private final InputStream input;

  private final OutputStream output;

  /**
   * Guards a connected socket.
   *
   * @param socket the connection
   * @throws IOException if its streams cannot be opened
   */
  GuardedSocket(final Socket socket) throws IOException {
    Preconditions.checkNotNull(socket, "Socket must not be null");
    this.socket = socket;
    final RfbGuard guard = new RfbGuard();
    this.input = guard.serverInput(socket.getInputStream());
    this.output = guard.clientOutput(socket.getOutputStream());
  }

  @Override
  public InputStream getInputStream() {
    return this.input;
  }

  @Override
  public OutputStream getOutputStream() {
    return this.output;
  }

  @Override
  public boolean getTcpNoDelay() throws SocketException {
    return this.socket.getTcpNoDelay();
  }

  @Override
  public boolean isConnected() {
    return this.socket.isConnected();
  }

  @Override
  public boolean isClosed() {
    return this.socket.isClosed();
  }

  @Override
  public synchronized void close() throws IOException {
    this.socket.close();
  }
}
