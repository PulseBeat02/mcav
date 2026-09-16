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

import com.google.common.base.Preconditions;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.timeout.ReadTimeoutHandler;
import java.nio.file.Path;

/**
 * Sets up every accepted connection of the {@link FileHttpServer} with a read timeout and its own
 * {@link FileServerHandler}.
 *
 * <p>A client that does not send a complete request within {@value #READ_TIMEOUT_SECONDS} seconds is
 * disconnected, so slow or idle clients cannot keep connections open forever. The file handler removes the
 * timeout once the request is complete, so slow downloads are never cut off.
 */
final class FileHttpChannelInitializer extends ChannelInitializer<SocketChannel> {

  /**
   * The name of the read timeout handler inside the channel pipeline.
   */
  static final String READ_TIMEOUT_NAME = "mcav_read_timeout";

  /**
   * The number of seconds a client may stay silent before its request is complete.
   */
  static final int READ_TIMEOUT_SECONDS = 30;

  private final Path filePath;

  /**
   * Constructs a new initializer.
   *
   * @param filePath the file served to every connection
   */
  FileHttpChannelInitializer(final Path filePath) {
    this.filePath = filePath;
  }

  /**
   * Adds a read timeout and a new file handler to the connection.
   *
   * @param channel the accepted connection
   */
  @Override
  public void initChannel(final SocketChannel channel) {
    Preconditions.checkNotNull(channel);
    final ChannelPipeline pipeline = channel.pipeline();
    final ReadTimeoutHandler timeout = new ReadTimeoutHandler(READ_TIMEOUT_SECONDS);
    final FileServerHandler handler = new FileServerHandler(this.filePath);
    pipeline.addLast(READ_TIMEOUT_NAME, timeout);
    pipeline.addLast(handler);
  }
}
