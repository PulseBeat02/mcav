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
package me.brandonli.mcav.bukkit.resourcepack.provider.netty;

import com.google.common.base.Preconditions;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.stream.ChunkedStream;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.util.ReferenceCountUtil;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// a Minecraft handshake's second byte is always 0x00, so "GET " or "HEAD " decides within five bytes
final class ResourcePackHttpHandler extends ChannelInboundHandlerAdapter {

  static final String NAME = "mcav_resource_pack_http";
  static final String STALL_NAME = "mcav_resource_pack_stall";
  private static final String CHUNKS_NAME = "mcav_resource_pack_chunks";

  private static final String CONTEXT_NULL_MESSAGE = "Context must not be null!";

  private static final boolean OBSERVE_OUTPUT = true;

  static final long STALL_SECONDS = 60;
  private static final long IDLE_CHECK_DISABLED_SECONDS = 0;

  private static final int CHUNK_BYTES = 8 * 1024;

  private static final Logger LOGGER = LoggerFactory.getLogger(ResourcePackHttpHandler.class);

  private static final String GET_REQUEST_PREFIX = "GET ";
  private static final String HEAD_REQUEST_PREFIX = "HEAD ";
  private static final String REQUEST_PATH_TERMINATOR = " ";

  private static final byte[] GET_REQUEST_PREFIX_BYTES = GET_REQUEST_PREFIX.getBytes(StandardCharsets.US_ASCII);
  private static final byte[] HEAD_REQUEST_PREFIX_BYTES = HEAD_REQUEST_PREFIX.getBytes(StandardCharsets.US_ASCII);

  private static final String STATUS_OK = "200 OK";
  private static final String STATUS_ERROR = "500 Internal Server Error";

  private static final String CONTENT_TYPE_ZIP = "application/zip";
  private static final String CONTENT_TYPE_TEXT = "text/plain";

  private final ResourcePackFile packFile;
  private final byte[] getPrefix;
  private final byte[] headPrefix;

  private @Nullable CompositeByteBuf cumulation;
  private boolean served;

  ResourcePackHttpHandler(final ResourcePackFile packFile, final String requestPath) {
    this.packFile = packFile;
    final String getRequestLine = GET_REQUEST_PREFIX + requestPath + REQUEST_PATH_TERMINATOR;
    this.getPrefix = getRequestLine.getBytes(StandardCharsets.US_ASCII);
    final String headRequestLine = HEAD_REQUEST_PREFIX + requestPath + REQUEST_PATH_TERMINATOR;
    this.headPrefix = headRequestLine.getBytes(StandardCharsets.US_ASCII);
  }

  @Override
  public void channelRead(final ChannelHandlerContext context, final Object message) {
    Preconditions.checkNotNull(context, CONTEXT_NULL_MESSAGE);
    Preconditions.checkNotNull(message, "Message must not be null!");

    if (this.served) {
      ReferenceCountUtil.release(message);
      return;
    }

    if (!(message instanceof final ByteBuf buffer)) {
      context.fireChannelRead(message);
      return;
    }

    final ByteBuf data = this.accumulate(context, buffer);
    final RequestType type = classify(data);
    this.dispatch(context, data, type);
  }

  private void dispatch(final ChannelHandlerContext context, final ByteBuf data, final RequestType type) {
    if (type == RequestType.INCOMPLETE) {
      return;
    }

    if (type == RequestType.MINECRAFT) {
      this.passToMinecraft(context, data);
      return;
    }

    final boolean isHeadRequest = type == RequestType.HEAD;
    final byte[] expected = isHeadRequest ? this.headPrefix : this.getPrefix;
    final int readable = data.readableBytes();
    final int start = data.readerIndex();
    if (!matchesPrefix(data, start, readable, expected)) {
      // Another hosting instance may own this request; forward every accumulated byte unchanged.
      this.passToMinecraft(context, data);
      return;
    }

    if (readable < expected.length) {
      return;
    }

    this.serve(context, data, type);
  }

  private void passToMinecraft(final ChannelHandlerContext context, final ByteBuf data) {
    this.cumulation = null;
    context.fireChannelRead(data);
    final ChannelPipeline pipeline = context.pipeline();
    pipeline.remove(this);
  }

  private void serve(final ChannelHandlerContext context, final ByteBuf data, final RequestType type) {
    this.cumulation = null;
    this.served = true;
    data.release();
    this.replaceReadTimeout(context);
    this.respond(context, type);
  }

  // the game's 30-second read timeout would cut a download, which sends nothing after its request
  private void replaceReadTimeout(final ChannelHandlerContext context) {
    final ChannelPipeline pipeline = context.pipeline();
    final Map<String, ChannelHandler> handlers = pipeline.toMap();
    for (final Map.Entry<String, ChannelHandler> entry : handlers.entrySet()) {
      if (entry.getValue() instanceof ReadTimeoutHandler) {
        pipeline.remove(entry.getKey());
      }
    }

    // the stall handler counts a write once it is complete, and raises its first idle event however far a write still
    // in progress got, so the pack goes out a chunk at a time, each chunk a write of its own that passes through it
    pipeline.addBefore(context.name(), CHUNKS_NAME, new ChunkedWriteHandler());
    final IdleStateHandler stallHandler = new IdleStateHandler(
      OBSERVE_OUTPUT,
      IDLE_CHECK_DISABLED_SECONDS,
      STALL_SECONDS,
      IDLE_CHECK_DISABLED_SECONDS,
      TimeUnit.SECONDS
    );
    // before the chunks, so their writes pass through it, and its idle event reaches this handler
    pipeline.addBefore(CHUNKS_NAME, STALL_NAME, stallHandler);
  }

  @Override
  public void userEventTriggered(final ChannelHandlerContext context, final Object event) {
    if (this.served && event instanceof final IdleStateEvent idle && idle.state() == IdleState.WRITER_IDLE) {
      final ChannelFuture closing = context.close();
      closing.addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
      return;
    }

    context.fireUserEventTriggered(event);
  }

  private ByteBuf accumulate(final ChannelHandlerContext context, final ByteBuf buffer) {
    final CompositeByteBuf existing = this.cumulation;
    if (existing != null) {
      existing.addComponent(true, buffer);

      return existing;
    }

    final ByteBufAllocator allocator = context.alloc();
    final CompositeByteBuf composite = allocator.compositeBuffer();
    composite.addComponent(true, buffer);
    this.cumulation = composite;

    return composite;
  }

  private void respond(final ChannelHandlerContext context, final RequestType type) {
    final byte[] body;
    try {
      body = this.packFile.read();
    } catch (final IOException exception) {
      LOGGER.warn("Could not read the resource pack for an HTTP download.", exception);
      this.respondWithError(context);
      return;
    }

    final ByteBuf headers = this.createHeaders(STATUS_OK, CONTENT_TYPE_ZIP, body.length);
    if (type == RequestType.HEAD) {
      final ChannelFuture headersFuture = context.writeAndFlush(headers);
      headersFuture.addListener(ChannelFutureListener.CLOSE);
      return;
    }

    final InputStream stream = new ByteArrayInputStream(body);
    final ChunkedStream content = new ChunkedStream(stream, CHUNK_BYTES);
    final ChannelFuture headersFuture = context.write(headers);
    headersFuture.addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
    final ChannelFuture contentFuture = context.writeAndFlush(content);
    contentFuture.addListener(ChannelFutureListener.CLOSE);
  }

  private void respondWithError(final ChannelHandlerContext context) {
    final ByteBuf error = this.createHeaders(STATUS_ERROR, CONTENT_TYPE_TEXT, 0);
    final ChannelFuture errorFuture = context.writeAndFlush(error);
    errorFuture.addListener(ChannelFutureListener.CLOSE);
  }

  private ByteBuf createHeaders(final String status, final String contentType, final int contentLength) {
    final String text =
      "HTTP/1.1 %s\r\nContent-Type: %s\r\nContent-Length: %s\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".formatted(
        status,
        contentType,
        contentLength
      );
    return Unpooled.copiedBuffer(text, StandardCharsets.US_ASCII);
  }

  static RequestType classify(final ByteBuf buffer) {
    final int readable = buffer.readableBytes();
    final int start = buffer.readerIndex();
    final boolean couldBeGet = matchesPrefix(buffer, start, readable, GET_REQUEST_PREFIX_BYTES);
    final boolean couldBeHead = matchesPrefix(buffer, start, readable, HEAD_REQUEST_PREFIX_BYTES);
    if (couldBeGet && readable >= GET_REQUEST_PREFIX_BYTES.length) {
      return RequestType.GET;
    }

    if (couldBeHead && readable >= HEAD_REQUEST_PREFIX_BYTES.length) {
      return RequestType.HEAD;
    }

    if (couldBeGet || couldBeHead) {
      return RequestType.INCOMPLETE;
    }

    return RequestType.MINECRAFT;
  }

  private static boolean matchesPrefix(final ByteBuf buffer, final int start, final int readable, final byte[] pattern) {
    final int length = Math.min(readable, pattern.length);
    for (int index = 0; index < length; index++) {
      final byte actual = buffer.getByte(start + index);
      if (actual != pattern[index]) {
        return false;
      }
    }

    return true;
  }

  @Override
  public void handlerRemoved(final ChannelHandlerContext context) {
    Preconditions.checkNotNull(context, CONTEXT_NULL_MESSAGE);

    this.releaseCumulation();
  }

  @Override
  public void channelInactive(final ChannelHandlerContext context) {
    Preconditions.checkNotNull(context, CONTEXT_NULL_MESSAGE);

    this.releaseCumulation();
    context.fireChannelInactive();
  }

  private void releaseCumulation() {
    final CompositeByteBuf pending = this.cumulation;
    if (pending == null) {
      return;
    }

    this.cumulation = null;
    pending.release();
  }

  enum RequestType {
    INCOMPLETE,

    MINECRAFT,

    GET,

    HEAD,
  }
}
