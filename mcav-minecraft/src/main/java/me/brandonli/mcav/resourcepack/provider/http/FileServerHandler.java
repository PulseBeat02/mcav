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
package me.brandonli.mcav.resourcepack.provider.http;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import me.brandonli.mcav.utils.IOUtils;

/**
 * A handler for managing file server operations over a Netty channel.
 * Handles incoming channel activity to serve files to clients.
 * Implements file transfer using HTTP headers and ensures proper connection closure post-transfer.
 */
public final class FileServerHandler extends ChannelInboundHandlerAdapter {

  private static final String RESPONSE_HEADERS_TEMPLATE =
    "HTTP/1.1 200 OK\r\n" +
    "Content-Type: application/octet-stream\r\n" +
    "Content-Length: %s\r\n" +
    "Content-Disposition: attachment; filename=\"%s\"\r\n" +
    "Connection: keep-alive\r\n" +
    "\r\n";

  private final Path filePath;

  /**
   * Constructs a new instance of {@code FileServerHandler}.
   * This handler is responsible for serving files to clients over a Netty channel.
   *
   * @param filePath the {@link Path} to the file that this handler will serve to clients.
   *                 It must point to a valid and accessible file on the file system.
   */
  public FileServerHandler(final Path filePath) {
    this.filePath = filePath;
  }

  /**
   * Handles channel activation and initiates file transfer over the Netty channel.
   * When the channel becomes active, this method reads the specified file, constructs
   * HTTP headers, writes the headers and file content to the channel, and schedules
   * the connection for closure upon completion of the transfer.
   *
   * @param ctx the {@code ChannelHandlerContext} that provides pipeline operations
   *            and allows communication with the Channel.
   * @throws Exception if any error occurs during file reading or data transmission.
   */
  @Override
  public void channelActive(final ChannelHandlerContext ctx) throws Exception {
    final String path = this.filePath.toString();
    try (final RandomAccessFile file = new RandomAccessFile(path, "r")) {
      final byte[] responseHeaders = this.createHeader(file);
      final byte[] fileContent = Files.readAllBytes(this.filePath);
      final ByteBuf buf = Unpooled.copiedBuffer(responseHeaders);
      ctx.write(buf);
      final ByteBuf copied = Unpooled.copiedBuffer(fileContent);
      final ChannelFuture future = ctx.writeAndFlush(copied);
      future.addListener(ChannelFutureListener.CLOSE);
    }
  }

  /**
   * Creates an HTTP response header as a byte array for serving a file.
   * The response header includes information such as the content length
   * and the name of the file.
   *
   * @param file the RandomAccessFile representing the file being served.
   *             It is used to determine the file's size.
   * @return a byte array containing the HTTP response header formatted
   * with file details.
   * @throws IOException if an I/O error occurs while accessing the file's length.
   */
  public byte[] createHeader(final RandomAccessFile file) throws IOException {
    final long fileLength = file.length();
    final String fileName = IOUtils.getName(this.filePath);
    final String responseHeaders = String.format(RESPONSE_HEADERS_TEMPLATE, fileLength, fileName);
    return responseHeaders.getBytes();
  }

  /**
   * Handles any exceptions raised during channel operations.
   * Logs or processes the exception and rethrows it as an assertion error.
   *
   * @param ctx   the {@link ChannelHandlerContext} providing context information about the channel
   * @param cause the {@link Throwable} that was caught during processing
   */
  @Override
  public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) {
    throw new AssertionError(cause);
  }
}
