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

import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.stream.ChunkedWriteHandler;
import java.nio.file.Path;

/**
 * A ChannelInitializer implementation for initializing HTTP channels
 * in a File-based HTTP server.
 * <p>
 * This class is responsible for configuring the {@link ChannelPipeline} of a
 * {@link SocketChannel} to handle HTTP file transmission. It is specifically
 * designed to work with a {@link FileHttpServer}, ensuring the proper handling
 * of HTTP file requests and responses.
 * <p>
 * The initializer adds the following handlers to the pipeline:
 * - {@link ChunkedWriteHandler} for handling chunked file writes.
 * - {@link FileServerHandler} for serving the specified file to clients.
 * <p>
 * The file to be served is obtained through the {@link FileHttpServer#getFilePath()} method.
 * <p>
 * This class is immutable and thread-safe.
 */
public final class FileHttpChannelInitializer extends ChannelInitializer<SocketChannel> {

  private final FileHttpServer server;

  /**
   * Constructs a new instance of FileHttpChannelInitializer.
   * <p>
   * This initializer is responsible for configuring the pipeline of HTTP channels
   * used in the {@link FileHttpServer}. It integrates custom handlers into the
   * channel pipeline to handle file-based HTTP requests and responses.
   *
   * @param server the {@link FileHttpServer} instance to associate with this initializer,
   *               providing access to necessary server properties such as file paths
   *               and configurations.
   */
  public FileHttpChannelInitializer(final FileHttpServer server) {
    this.server = server;
  }

  /**
   * Initializes the {@link ChannelPipeline} of the given {@link SocketChannel}.
   * <p>
   * This method configures the pipeline of the provided channel to handle HTTP file requests.
   * It adds the following handlers:
   * - {@link ChunkedWriteHandler}: A handler for writing large data streams in chunks.
   * - {@link FileServerHandler}: A custom handler for serving files from the provided file path.
   *
   * @param ch the {@link SocketChannel} whose pipeline is to be initialized
   */
  @Override
  public void initChannel(final SocketChannel ch) {
    final Path file = this.server.getFilePath();
    final ChannelPipeline p = ch.pipeline();
    final ChunkedWriteHandler handler = new ChunkedWriteHandler();
    final FileServerHandler fileHandler = new FileServerHandler(file);
    p.addLast(handler);
    p.addLast(fileHandler);
  }
}
