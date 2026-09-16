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
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.util.ReferenceCountUtil;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decides whether a freshly accepted connection belongs to a Minecraft client or to an HTTP client.
 *
 * <p>The handler sits at the very front of the pipeline and looks at the first bytes the peer sends. A Minecraft
 * handshake can never start with {@code "GET "} or {@code "HEAD "}, because its second byte is always the packet
 * id {@code 0x00}. That makes the decision unambiguous after at most five bytes:
 *
 * <ul>
 *   <li>HTTP clients receive the resource pack and the connection is closed afterward.</li>
 *   <li>Minecraft clients receive every byte unchanged, and the handler removes itself from the pipeline, so it
 *       adds no overhead to game connections.</li>
 * </ul>
 *
 * <p>A new handler is created for every connection, so the handler is never shared between threads.
 */
final class ResourcePackHttpHandler extends ChannelInboundHandlerAdapter {

  /**
   * The name of the handler inside the channel pipeline.
   */
  static final String NAME = "mcav_resource_pack_http";

  private static final Logger LOGGER = LoggerFactory.getLogger(ResourcePackHttpHandler.class);
  private static final byte[] GET = "GET ".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] HEAD = "HEAD ".getBytes(StandardCharsets.US_ASCII);
  private static final String STATUS_OK = "200 OK";
  private static final String STATUS_ERROR = "500 Internal Server Error";
  private static final String CONTENT_TYPE_ZIP = "application/zip";
  private static final String CONTENT_TYPE_TEXT = "text/plain";

  private final ResourcePackFile packFile;

  private @Nullable CompositeByteBuf cumulation;
  private boolean served;

  /**
   * Constructs a new handler for one connection.
   *
   * @param packFile the resource pack to serve to HTTP clients
   */
  ResourcePackHttpHandler(final ResourcePackFile packFile) {
    this.packFile = packFile;
  }

  /**
   * Inspects the incoming bytes until the type of the client is known, then either serves the resource pack or
   * passes the bytes on to Minecraft.
   *
   * @param context the context of this handler
   * @param message the received message, usually a {@link ByteBuf}
   */
  @Override
  public void channelRead(final ChannelHandlerContext context, final Object message) {
    Preconditions.checkNotNull(context);
    Preconditions.checkNotNull(message);
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
      // not enough bytes to decide yet, wait for the next read
      return;
    }

    if (type == RequestType.MINECRAFT) {
      this.passToMinecraft(context, data);
      return;
    }

    final boolean headOnly = type == RequestType.HEAD;
    this.serve(context, data, headOnly);
  }

  /**
   * Hands the connection back to Minecraft, which is the only protocol this connection will ever carry.
   */
  private void passToMinecraft(final ChannelHandlerContext context, final ByteBuf data) {
    this.cumulation = null;
    context.fireChannelRead(data);
    final ChannelPipeline pipeline = context.pipeline();
    pipeline.remove(this);
  }

  private void serve(final ChannelHandlerContext context, final ByteBuf data, final boolean headOnly) {
    this.cumulation = null;
    this.served = true;
    data.release();
    this.respond(context, headOnly);
  }

  private ByteBuf accumulate(final ChannelHandlerContext context, final ByteBuf buffer) {
    final CompositeByteBuf existing = this.cumulation;
    if (existing != null) {
      existing.addComponent(true, buffer);
      return existing;
    }

    final int readable = buffer.readableBytes();
    if (readable >= HEAD.length) {
      return buffer;
    }

    final ByteBufAllocator allocator = context.alloc();
    final CompositeByteBuf composite = allocator.compositeBuffer();
    composite.addComponent(true, buffer);
    this.cumulation = composite;
    return composite;
  }

  private void respond(final ChannelHandlerContext context, final boolean headOnly) {
    final byte[] body;
    try {
      body = this.packFile.read();
    } catch (final IOException exception) {
      LOGGER.warn("Could not read the resource pack for an HTTP download", exception);
      respondWithError(context);
      return;
    }

    final ByteBuf headers = createHeaders(STATUS_OK, CONTENT_TYPE_ZIP, body.length);
    if (headOnly) {
      final ChannelFuture headersFuture = context.writeAndFlush(headers);
      headersFuture.addListener(ChannelFutureListener.CLOSE);
      return;
    }

    final ByteBuf content = Unpooled.wrappedBuffer(body);
    // a connection that cannot take the headers is closed at once; the content write then fails and is released
    final ChannelFuture headersFuture = context.write(headers);
    headersFuture.addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
    final ChannelFuture contentFuture = context.writeAndFlush(content);
    contentFuture.addListener(ChannelFutureListener.CLOSE);
  }

  private static void respondWithError(final ChannelHandlerContext context) {
    final ByteBuf error = createHeaders(STATUS_ERROR, CONTENT_TYPE_TEXT, 0);
    final ChannelFuture errorFuture = context.writeAndFlush(error);
    errorFuture.addListener(ChannelFutureListener.CLOSE);
  }

  private static ByteBuf createHeaders(final String status, final String contentType, final int contentLength) {
    final String text =
      "HTTP/1.1 " +
      status +
      "\r\n" +
      "Content-Type: " +
      contentType +
      "\r\n" +
      "Content-Length: " +
      contentLength +
      "\r\n" +
      "Cache-Control: no-store\r\n" +
      "Connection: close\r\n" +
      "\r\n";
    return Unpooled.copiedBuffer(text, StandardCharsets.US_ASCII);
  }

  /**
   * Classifies the client based on the bytes received so far. The reader index of the buffer is not changed.
   *
   * @param buffer the bytes received so far
   * @return the type of the client, or {@link RequestType#INCOMPLETE} if more bytes are needed to decide
   */
  static RequestType classify(final ByteBuf buffer) {
    final int readable = buffer.readableBytes();
    final int start = buffer.readerIndex();
    final boolean couldBeGet = matchesPrefix(buffer, start, readable, GET);
    final boolean couldBeHead = matchesPrefix(buffer, start, readable, HEAD);

    if (couldBeGet && readable >= GET.length) {
      return RequestType.GET;
    }

    if (couldBeHead && readable >= HEAD.length) {
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

  /**
   * Releases any bytes that were buffered while waiting for the client type.
   *
   * @param context the context of this handler
   */
  @Override
  public void handlerRemoved(final ChannelHandlerContext context) {
    Preconditions.checkNotNull(context);
    this.releaseCumulation();
  }

  /**
   * Releases any buffered bytes when the connection closes before the client type was known.
   *
   * @param context the context of this handler
   */
  @Override
  public void channelInactive(final ChannelHandlerContext context) {
    Preconditions.checkNotNull(context);
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

  /**
   * The type of client that opened a connection.
   */
  enum RequestType {
    /**
     * Not enough bytes have been received to decide.
     */
    INCOMPLETE,

    /**
     * The client is a Minecraft client, or anything else that is not a supported HTTP request.
     */
    MINECRAFT,

    /**
     * The client sent an HTTP {@code GET} request.
     */
    GET,

    /**
     * The client sent an HTTP {@code HEAD} request.
     */
    HEAD,
  }
}
