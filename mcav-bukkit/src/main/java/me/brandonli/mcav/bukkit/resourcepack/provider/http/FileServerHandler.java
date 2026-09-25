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
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Answers exactly one HTTP request per connection with the served file.
 *
 * <p>The handler reads the request until the end of its headers, looks only at the request method, and answers
 * {@code GET} with the file and {@code HEAD} with its headers. Any other method is rejected. The connection is
 * closed after the response, which keeps the handler simple and is exactly what Minecraft clients expect when
 * they download a resource pack. The file body is sent as a zero-copy file region.
 *
 * <p>The file is opened once per request, and both the {@code Content-Length} header and the body come from that
 * open file. A pack that is replaced while a download starts therefore never produces a response whose length
 * does not match its body. Once the request is complete, the read timeout of the connection is removed, so a slow
 * download is never cut off.
 *
 * <p>A new handler is created for every connection, so it is never shared between threads.
 */
final class FileServerHandler extends ChannelInboundHandlerAdapter {

  private static final Logger LOGGER = LoggerFactory.getLogger(FileServerHandler.class);
  private static final ChannelFutureListener LOG_CLOSE_FAILURE = FileServerHandler::logCloseFailure;
  private static final int MAX_HEADER_BYTES = 8192;
  private static final byte[] HEADER_TERMINATOR = { '\r', '\n', '\r', '\n' };
  private static final String STATUS_OK = "200 OK";
  private static final String CONTENT_TYPE = "application/zip";
  private static final String DEFAULT_FILE_NAME = "resourcepack.zip";

  private final Path filePath;
  private final String fileName;
  private final FileOpener opener;

  private @Nullable ByteBuf requestBuffer;
  private boolean responded;

  /**
   * Constructs a new handler for one connection.
   *
   * @param filePath the file to serve
   */
  FileServerHandler(final Path filePath) {
    this(filePath, FileServerHandler::openFile);
  }

  /**
   * Constructs a new handler for one connection that opens the file with the specified opener.
   *
   * @param filePath the file to serve
   * @param opener   opens the file for reading
   */
  @VisibleForTesting
  FileServerHandler(final Path filePath, final FileOpener opener) {
    this.filePath = filePath;
    this.fileName = getFileName(filePath);
    this.opener = opener;
  }

  private static FileChannel openFile(final Path path) throws IOException {
    return FileChannel.open(path, StandardOpenOption.READ);
  }

  /**
   * Gets the name of the served file for the {@code Content-Disposition} header.
   *
   * @param path the path of the served file
   * @return the file name, or a generic name if the path has none, such as a root directory
   */
  @VisibleForTesting
  static String getFileName(final Path path) {
    final Path fileNamePath = path.getFileName();
    return fileNamePath == null ? DEFAULT_FILE_NAME : fileNamePath.toString();
  }

  /**
   * Allocates the buffer that collects the request headers.
   *
   * @param context the context of this handler
   */
  @Override
  public void handlerAdded(final ChannelHandlerContext context) {
    Preconditions.checkNotNull(context);
    final ByteBufAllocator allocator = context.alloc();
    this.requestBuffer = allocator.buffer(256, MAX_HEADER_BYTES);
  }

  /**
   * Releases the request buffer. Calling this method again after the buffer was released has no effect.
   *
   * @param context the context of this handler
   */
  @Override
  public void handlerRemoved(final ChannelHandlerContext context) {
    Preconditions.checkNotNull(context);
    final ByteBuf buffer = this.requestBuffer;
    if (buffer != null) {
      this.requestBuffer = null;
      buffer.release();
    }
  }

  /**
   * Collects request bytes until the headers are complete, then answers the request. The received message is
   * always released, and everything that arrives after the response was sent is dropped.
   *
   * @param context the context of this handler
   * @param message the received bytes
   */
  @Override
  public void channelRead(final ChannelHandlerContext context, final Object message) {
    Preconditions.checkNotNull(context);
    Preconditions.checkNotNull(message);
    try {
      final ByteBuf buffer = this.requestBuffer;
      if (this.responded || buffer == null || !(message instanceof final ByteBuf data)) {
        return;
      }

      this.collect(context, buffer, data);
    } finally {
      ReferenceCountUtil.release(message);
    }
  }

  private void collect(final ChannelHandlerContext context, final ByteBuf buffer, final ByteBuf data) {
    final int incoming = data.readableBytes();
    final int available = buffer.maxWritableBytes();
    if (incoming > available) {
      this.respondWithStatus(context, "431 Request Header Fields Too Large");
      return;
    }

    buffer.writeBytes(data);
    final int headerEnd = indexOfHeaderEnd(buffer);
    if (headerEnd < 0) {
      return;
    }

    stopReadTimeout(context);
    final String method = readMethod(buffer);
    this.respond(context, method);
  }

  // The request is complete, so the client has nothing more to send while it downloads the file. The write timeout of
  // the pipeline takes over from here and closes a download that stalls.
  private static void stopReadTimeout(final ChannelHandlerContext context) {
    final ChannelPipeline pipeline = context.pipeline();
    final ChannelHandler timeout = pipeline.get(FileHttpChannelInitializer.READ_TIMEOUT_NAME);
    if (timeout != null) {
      pipeline.remove(timeout);
    }
  }

  private static int indexOfHeaderEnd(final ByteBuf buffer) {
    final int start = buffer.readerIndex();
    final int writerIndex = buffer.writerIndex();
    final int end = writerIndex - HEADER_TERMINATOR.length;
    for (int index = start; index <= end; index++) {
      final boolean matches = isHeaderTerminatorAt(buffer, index);
      if (matches) {
        return index;
      }
    }
    return -1;
  }

  private static boolean isHeaderTerminatorAt(final ByteBuf buffer, final int index) {
    for (int offset = 0; offset < HEADER_TERMINATOR.length; offset++) {
      final byte actual = buffer.getByte(index + offset);
      if (actual != HEADER_TERMINATOR[offset]) {
        return false;
      }
    }
    return true;
  }

  private static String readMethod(final ByteBuf buffer) {
    final int start = buffer.readerIndex();
    final int end = buffer.writerIndex();
    int spaceIndex = start;
    while (spaceIndex < end) {
      final byte current = buffer.getByte(spaceIndex);
      if (current == ' ') {
        break;
      }
      spaceIndex++;
    }

    final int length = spaceIndex - start;
    return buffer.toString(start, length, StandardCharsets.US_ASCII);
  }

  private void respond(final ChannelHandlerContext context, final String method) {
    final boolean isGet = method.equals("GET");
    final boolean isHead = method.equals("HEAD");
    if (!isGet && !isHead) {
      this.respondWithStatus(context, "405 Method Not Allowed");
      return;
    }

    final FileChannel channel;
    try {
      channel = this.opener.open(this.filePath);
    } catch (final IOException exception) {
      this.respondWithStatus(context, "404 Not Found");
      return;
    }

    this.respondWithFile(context, channel, isHead);
  }

  private void respondWithFile(final ChannelHandlerContext context, final FileChannel channel, final boolean headOnly) {
    final long fileLength;
    try {
      fileLength = channel.size();
    } catch (final IOException exception) {
      closeQuietly(channel);
      this.respondWithStatus(context, "404 Not Found");
      return;
    }

    this.responded = true;
    this.sendFile(context, channel, fileLength, headOnly);
  }

  /**
   * Sends the headers and, unless only the headers were requested, the file itself without copying it into memory.
   * The file region closes the channel once it was sent.
   */
  private void sendFile(final ChannelHandlerContext context, final FileChannel channel, final long fileLength, final boolean headOnly) {
    final ByteBuf headers = createHeaders(fileLength, this.fileName);
    if (headOnly) {
      closeQuietly(channel);
      final ChannelFuture headersFuture = context.writeAndFlush(headers);
      headersFuture.addListener(ChannelFutureListener.CLOSE);
      return;
    }

    final DefaultFileRegion region = new DefaultFileRegion(channel, 0, fileLength);
    // a connection that cannot take the headers is closed at once; the body write then fails and releases the file
    final ChannelFuture headersFuture = context.write(headers);
    headersFuture.addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
    final ChannelFuture bodyFuture = context.writeAndFlush(region);
    bodyFuture.addListener(ChannelFutureListener.CLOSE);
  }

  // the file was only read, so a failure to close it loses nothing
  private static void closeQuietly(final FileChannel channel) {
    try {
      channel.close();
    } catch (final IOException exception) {
      // nothing to do
    }
  }

  private void respondWithStatus(final ChannelHandlerContext context, final String status) {
    this.responded = true;
    final String text = "HTTP/1.1 " + status + "\r\n" + "Content-Length: 0\r\n" + "Connection: close\r\n" + "\r\n";
    final ByteBuf buffer = Unpooled.copiedBuffer(text, StandardCharsets.US_ASCII);
    final ChannelFuture future = context.writeAndFlush(buffer);
    future.addListener(ChannelFutureListener.CLOSE);
  }

  private static ByteBuf createHeaders(final long contentLength, final String fileName) {
    final String safeFileName = fileName.replaceAll("[\\p{Cntrl}\"\\\\]", "");
    final String text =
      "HTTP/1.1 " +
      STATUS_OK +
      "\r\n" +
      "Content-Type: " +
      CONTENT_TYPE +
      "\r\n" +
      "Content-Length: " +
      contentLength +
      "\r\n" +
      "Content-Disposition: attachment; filename=\"" +
      safeFileName +
      "\"\r\n" +
      "Cache-Control: no-store\r\n" +
      "Connection: close\r\n" +
      "\r\n";
    return Unpooled.copiedBuffer(text, StandardCharsets.US_ASCII);
  }

  /**
   * Closes the connection when anything goes wrong, including a read timeout. Errors of a single download must
   * never affect the server.
   *
   * @param context the context of this handler
   * @param cause   the error
   */
  @Override
  public void exceptionCaught(final ChannelHandlerContext context, final Throwable cause) {
    Preconditions.checkNotNull(context);
    Preconditions.checkNotNull(cause);
    final ChannelFuture closing = context.close();
    closing.addListener(LOG_CLOSE_FAILURE);
  }

  /**
   * Logs why a connection could not be closed after an error. Nothing else is left to do for such a connection, so
   * the failure is only recorded for debugging.
   */
  private static void logCloseFailure(final ChannelFuture closing) {
    final boolean closed = closing.isSuccess();
    if (closed) {
      return;
    }
    final Throwable failure = closing.cause();
    LOGGER.debug("Could not close a resource pack download connection after an error", failure);
  }

  /**
   * Opens the served file for reading.
   */
  @FunctionalInterface
  interface FileOpener {
    /**
     * Opens a file for reading.
     *
     * @param path the file to open
     * @return the open file, which the caller closes
     * @throws IOException if the file cannot be opened
     */
    FileChannel open(Path path) throws IOException;
  }
}
