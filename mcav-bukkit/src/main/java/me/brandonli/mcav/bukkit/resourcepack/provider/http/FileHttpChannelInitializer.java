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
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Builds the pipeline of a resource pack download: a read timeout while the request arrives, a write timeout while the
 * pack is sent, and the handler that answers it.
 *
 * <p>The timeouts and the limit on connections exist because this server listens on a port of its own, which anyone
 * can reach. Without them a client that sends half a request, or that stops reading its download, would hold a socket
 * and an open file of the server until it disconnects, which it never has to do.
 */
final class FileHttpChannelInitializer extends ChannelInitializer<SocketChannel> {

  static final String READ_TIMEOUT_NAME = "mcav_read_timeout";

  static final String WRITE_TIMEOUT_NAME = "mcav_write_timeout";

  static final int READ_TIMEOUT_SECONDS = 30;

  /**
   * How long a download may stall before the connection is closed. A client that reads its pack at any usable speed
   * is far below this, and one that stops reading is closed instead of holding the socket and the file.
   */
  static final int WRITE_TIMEOUT_SECONDS = 300;

  /**
   * How many downloads may run at once. Players download a pack as they join, so a real server stays far below this,
   * while a client that opens connections without finishing them can tie up no more than this many.
   */
  static final int MAX_CONNECTIONS = 256;

  private static final String BUSY_RESPONSE = "HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";

  private final Path filePath;
  private final int maxConnections;
  private final AtomicInteger openConnections;

  FileHttpChannelInitializer(final Path filePath) {
    this(filePath, MAX_CONNECTIONS);
  }

  @VisibleForTesting
  FileHttpChannelInitializer(final Path filePath, final int maxConnections) {
    Preconditions.checkArgument(maxConnections > 0, "The connection limit must be positive but was %s", maxConnections);
    this.filePath = filePath;
    this.maxConnections = maxConnections;
    this.openConnections = new AtomicInteger();
  }

  @Override
  public void initChannel(final SocketChannel channel) {
    Preconditions.checkNotNull(channel);
    final int open = this.openConnections.incrementAndGet();
    final ChannelFuture closed = channel.closeFuture();
    closed.addListener(future -> this.openConnections.decrementAndGet());
    if (open > this.maxConnections) {
      refuse(channel);
      return;
    }

    final ChannelPipeline pipeline = channel.pipeline();
    final ReadTimeoutHandler readTimeout = new ReadTimeoutHandler(READ_TIMEOUT_SECONDS);
    final WriteTimeoutHandler writeTimeout = new WriteTimeoutHandler(WRITE_TIMEOUT_SECONDS);
    final FileServerHandler handler = new FileServerHandler(this.filePath);
    pipeline.addLast(READ_TIMEOUT_NAME, readTimeout);
    pipeline.addLast(WRITE_TIMEOUT_NAME, writeTimeout);
    pipeline.addLast(handler);
  }

  private static void refuse(final SocketChannel channel) {
    final ByteBuf response = Unpooled.copiedBuffer(BUSY_RESPONSE, StandardCharsets.US_ASCII);
    final ChannelFuture written = channel.writeAndFlush(response);
    written.addListener(ChannelFutureListener.CLOSE);
  }

  @VisibleForTesting
  int getOpenConnections() {
    return this.openConnections.get();
  }
}
