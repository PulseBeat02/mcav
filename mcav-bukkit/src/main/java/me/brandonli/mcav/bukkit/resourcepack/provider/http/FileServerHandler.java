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
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.DefaultFileRegion;
import io.netty.util.ReferenceCountUtil;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.regex.Pattern;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// one request per connection, as Minecraft clients expect; the length and the body come from one open file
final class FileServerHandler extends ChannelInboundHandlerAdapter {

  private static final String CONTEXT_NULL_MESSAGE = "Context must not be null!";

  private static final Logger LOGGER = LoggerFactory.getLogger(FileServerHandler.class);

  private static final ChannelFutureListener LOG_CLOSE_FAILURE = FileServerHandler::logCloseFailure;

  private static final int MAXIMUM_HEADER_BYTES = 8192;
  private static final int INITIAL_REQUEST_BUFFER_BYTES = 256;

  private static final byte[] HEADER_TERMINATOR = { '\r', '\n', '\r', '\n' };

  private static final String RESPONSE_TEMPLATE = "HTTP/1.1 %s\r\n%sConnection: close\r\n\r\n";

  private static final String STATUS_OK = "200 OK";
  private static final String STATUS_NOT_FOUND = "404 Not Found";
  private static final String STATUS_METHOD_NOT_ALLOWED = "405 Method Not Allowed";
  private static final String STATUS_HEADER_TOO_LARGE = "431 Request Header Fields Too Large";

  private static final String CONTENT_TYPE = "application/zip";

  private static final String DEFAULT_FILE_NAME = "resourcepack.zip";

  private static final String GET_METHOD = "GET";
  private static final String HEAD_METHOD = "HEAD";
  private static final byte REQUEST_LINE_SEPARATOR = ' ';

  private static final long FILE_START_POSITION = 0;

  private static final Pattern UNSAFE_FILE_NAME_CHARACTERS = Pattern.compile("[\\p{Cntrl}\"\\\\]");

  private final Path filePath;
  private final String fileName;
  private final FileOpener opener;

  private @Nullable ByteBuf requestBuffer;
  private boolean responded;

  FileServerHandler(final Path filePath) {
    this(filePath, FileServerHandler::openFile);
  }

  @VisibleForTesting
  FileServerHandler(final Path filePath, final FileOpener opener) {
    this.filePath = filePath;
    this.fileName = getFileName(filePath);
    this.opener = opener;
  }

  private static FileChannel openFile(final Path path) throws IOException {
    return FileChannel.open(path, StandardOpenOption.READ);
  }

  @VisibleForTesting
  static String getFileName(final Path path) {
    final Path fileNamePath = path.getFileName();
    return fileNamePath == null ? DEFAULT_FILE_NAME : fileNamePath.toString();
  }

  @Override
  public void handlerAdded(final ChannelHandlerContext context) {
    Preconditions.checkNotNull(context, CONTEXT_NULL_MESSAGE);

    final ByteBufAllocator allocator = context.alloc();
    this.requestBuffer = allocator.buffer(INITIAL_REQUEST_BUFFER_BYTES, MAXIMUM_HEADER_BYTES);
  }

  @Override
  public void handlerRemoved(final ChannelHandlerContext context) {
    Preconditions.checkNotNull(context, CONTEXT_NULL_MESSAGE);

    final ByteBuf buffer = this.requestBuffer;
    if (buffer != null) {
      this.requestBuffer = null;
      buffer.release();
    }
  }

  @Override
  public void channelRead(final ChannelHandlerContext context, final Object message) {
    Preconditions.checkNotNull(context, CONTEXT_NULL_MESSAGE);
    Preconditions.checkNotNull(message, "Message must not be null!");

    try {
      final ByteBuf buffer = this.requestBuffer;
      if (this.responded || buffer == null || !(message instanceof final ByteBuf data)) {
        return;
      }

      this.collectAndRespond(context, buffer, data);
    } finally {
      ReferenceCountUtil.release(message);
    }
  }

  private void collectAndRespond(final ChannelHandlerContext context, final ByteBuf buffer, final ByteBuf data) {
    // only the headers count against the limit, so a read that ends them may carry more, which is never looked at
    final int incoming = data.readableBytes();
    final int available = buffer.maxWritableBytes();
    final int taken = Math.min(incoming, available);
    buffer.writeBytes(data, taken);

    final int headerEnd = this.indexOfHeaderEnd(buffer);
    if (headerEnd < 0) {
      if (data.isReadable()) {
        this.respondWithStatus(context, STATUS_HEADER_TOO_LARGE);
      }

      return;
    }

    this.stopReadTimeout(context);
    final String method = this.readMethod(buffer);
    this.respond(context, method);
  }

  // The request is complete, so the client sends nothing more while it downloads; the pipeline's write timeout closes a stalled download.
  private void stopReadTimeout(final ChannelHandlerContext context) {
    final ChannelPipeline pipeline = context.pipeline();
    final ChannelHandler timeout = pipeline.get(FileHttpChannelInitializer.READ_TIMEOUT_NAME);
    if (timeout != null) {
      pipeline.remove(timeout);
    }
  }

  private int indexOfHeaderEnd(final ByteBuf buffer) {
    final int start = buffer.readerIndex();
    final int writerIndex = buffer.writerIndex();
    final int end = writerIndex - HEADER_TERMINATOR.length;
    for (int index = start; index <= end; index++) {
      final boolean matches = this.isHeaderTerminatorAt(buffer, index);
      if (matches) {
        return index;
      }
    }

    return -1;
  }

  private boolean isHeaderTerminatorAt(final ByteBuf buffer, final int index) {
    for (int offset = 0; offset < HEADER_TERMINATOR.length; offset++) {
      final byte actual = buffer.getByte(index + offset);
      if (actual != HEADER_TERMINATOR[offset]) {
        return false;
      }
    }

    return true;
  }

  private String readMethod(final ByteBuf buffer) {
    final int start = buffer.readerIndex();
    final int end = buffer.writerIndex();
    int spaceIndex = start;
    while (spaceIndex < end) {
      final byte current = buffer.getByte(spaceIndex);
      if (current == REQUEST_LINE_SEPARATOR) {
        break;
      }
      spaceIndex++;
    }

    final int length = spaceIndex - start;
    return buffer.toString(start, length, StandardCharsets.US_ASCII);
  }

  private void respond(final ChannelHandlerContext context, final String method) {
    final boolean isGet = method.equals(GET_METHOD);
    final boolean isHead = method.equals(HEAD_METHOD);
    if (!isGet && !isHead) {
      this.respondWithStatus(context, STATUS_METHOD_NOT_ALLOWED);
      return;
    }

    final FileChannel channel;
    try {
      channel = this.opener.open(this.filePath);
    } catch (final IOException exception) {
      this.respondWithStatus(context, STATUS_NOT_FOUND);
      return;
    }

    this.respondWithFile(context, channel, method);
  }

  private void respondWithFile(final ChannelHandlerContext context, final FileChannel channel, final String method) {
    final long fileLength;
    try {
      fileLength = channel.size();
    } catch (final IOException exception) {
      this.closeQuietly(channel);
      this.respondWithStatus(context, STATUS_NOT_FOUND);
      return;
    }

    this.responded = true;
    this.sendFile(context, channel, fileLength, method);
  }

  private void sendFile(final ChannelHandlerContext context, final FileChannel channel, final long fileLength, final String method) {
    final ByteBuf headers = this.createHeaders(fileLength, this.fileName);
    if (method.equals(HEAD_METHOD)) {
      this.closeQuietly(channel);
      final ChannelFuture headersFuture = context.writeAndFlush(headers);
      headersFuture.addListener(ChannelFutureListener.CLOSE);
      return;
    }

    final DefaultFileRegion region = new DefaultFileRegion(channel, FILE_START_POSITION, fileLength);
    final ChannelFuture headersFuture = context.write(headers);
    headersFuture.addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
    final ChannelFuture bodyFuture = context.writeAndFlush(region);
    bodyFuture.addListener(ChannelFutureListener.CLOSE);
  }

  private void closeQuietly(final FileChannel channel) {
    try {
      channel.close();
    } catch (final IOException exception) {
      // The channel is read-only, so a failed close cannot lose written data.
    }
  }

  private void respondWithStatus(final ChannelHandlerContext context, final String status) {
    this.responded = true;
    final String text = RESPONSE_TEMPLATE.formatted(status, "Content-Length: 0\r\n");
    final ByteBuf buffer = Unpooled.copiedBuffer(text, StandardCharsets.US_ASCII);
    final ChannelFuture future = context.writeAndFlush(buffer);
    future.addListener(ChannelFutureListener.CLOSE);
  }

  private ByteBuf createHeaders(final long contentLength, final String fileName) {
    final String safeFileName = UNSAFE_FILE_NAME_CHARACTERS.matcher(fileName).replaceAll("");
    final String fileHeaders =
      "Content-Type: %s\r\nContent-Length: %s\r\nContent-Disposition: attachment; filename=\"%s\"\r\nCache-Control: no-store\r\n".formatted(
        CONTENT_TYPE,
        contentLength,
        safeFileName
      );
    final String text = RESPONSE_TEMPLATE.formatted(STATUS_OK, fileHeaders);
    return Unpooled.copiedBuffer(text, StandardCharsets.US_ASCII);
  }

  @Override
  public void exceptionCaught(final ChannelHandlerContext context, final Throwable cause) {
    Preconditions.checkNotNull(context, CONTEXT_NULL_MESSAGE);
    Preconditions.checkNotNull(cause, "Cause must not be null!");

    final ChannelFuture closing = context.close();
    closing.addListener(LOG_CLOSE_FAILURE);
  }

  private static void logCloseFailure(final ChannelFuture closing) {
    final boolean closed = closing.isSuccess();
    if (closed) {
      return;
    }

    final Throwable failure = closing.cause();
    LOGGER.debug("Could not close a resource pack download connection after an error.", failure);
  }

  @FunctionalInterface
  interface FileOpener {
    FileChannel open(final Path path) throws IOException;
  }
}
