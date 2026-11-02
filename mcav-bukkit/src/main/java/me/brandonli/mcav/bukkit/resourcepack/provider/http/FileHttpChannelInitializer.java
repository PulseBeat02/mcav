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

// timeouts and a connection cap, because anyone can reach this port
final class FileHttpChannelInitializer extends ChannelInitializer<SocketChannel> {

  static final String READ_TIMEOUT_NAME = "mcav_read_timeout";
  static final String WRITE_TIMEOUT_NAME = "mcav_write_timeout";

  static final int READ_TIMEOUT_SECONDS = 30;
  static final int WRITE_TIMEOUT_SECONDS = 300;

  private static final int MAXIMUM_CONNECTIONS = 256;

  private static final String BUSY_RESPONSE = "HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";

  private final Path filePath;
  private final int maximumConnections;
  private final AtomicInteger openConnections;

  FileHttpChannelInitializer(final Path filePath) {
    this(filePath, MAXIMUM_CONNECTIONS);
  }

  @VisibleForTesting
  FileHttpChannelInitializer(final Path filePath, final int maximumConnections) {
    Preconditions.checkArgument(maximumConnections > 0, "Connection limit %s must be positive!", maximumConnections);

    this.filePath = filePath;
    this.maximumConnections = maximumConnections;
    this.openConnections = new AtomicInteger();
  }

  @Override
  public void initChannel(final SocketChannel channel) {
    Preconditions.checkNotNull(channel, "Channel must not be null!");

    final int open = this.openConnections.incrementAndGet();
    final ChannelFuture closed = channel.closeFuture();
    closed.addListener(future -> this.openConnections.decrementAndGet());

    if (open > this.maximumConnections) {
      this.refuse(channel);
      return;
    }

    final ChannelPipeline pipeline = channel.pipeline();
    final ReadTimeoutHandler readTimeout = new ReadTimeoutHandler(READ_TIMEOUT_SECONDS);
    final WriteTimeoutHandler writeTimeout = new ProgressWriteTimeoutHandler(WRITE_TIMEOUT_SECONDS);
    final FileServerHandler handler = new FileServerHandler(this.filePath);
    pipeline.addLast(READ_TIMEOUT_NAME, readTimeout);
    pipeline.addLast(WRITE_TIMEOUT_NAME, writeTimeout);
    pipeline.addLast(handler);
  }

  private void refuse(final SocketChannel channel) {
    final ByteBuf response = Unpooled.copiedBuffer(BUSY_RESPONSE, StandardCharsets.US_ASCII);
    final ChannelFuture written = channel.writeAndFlush(response);
    written.addListener(ChannelFutureListener.CLOSE);
  }

  @VisibleForTesting
  int getOpenConnections() {
    return this.openConnections.get();
  }
}
