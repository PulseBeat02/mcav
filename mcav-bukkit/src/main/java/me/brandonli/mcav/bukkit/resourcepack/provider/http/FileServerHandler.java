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

final class FileServerHandler extends ChannelInboundHandlerAdapter {

  private static final String RESPONSE_HEADERS_TEMPLATE =
    """
    HTTP/1.1 200 OK\r
    Content-Type: application/octet-stream\r
    Content-Length: %s\r
    Content-Disposition: attachment; filename="%s"\r
    Connection: keep-alive\r
    \r
    """;

  private final Path filePath;

  FileServerHandler(final Path filePath) {
    this.filePath = filePath;
  }

  /**
   * {@inheritDoc}
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

  private byte[] createHeader(final RandomAccessFile file) throws IOException {
    final long fileLength = file.length();
    final String fileName = IOUtils.getName(this.filePath);
    final String responseHeaders = String.format(RESPONSE_HEADERS_TEMPLATE, fileLength, fileName);
    return responseHeaders.getBytes();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) {
    throw new HttpServerException(cause.getMessage(), cause);
  }
}
