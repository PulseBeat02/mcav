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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.ChannelHandlerContext;
import java.nio.charset.StandardCharsets;

final class HttpByteBuf {

  private final ByteBuf inner;

  HttpByteBuf(final ByteBuf inner) {
    this.inner = inner;
  }

  ByteBuf getInner() {
    return this.inner;
  }

  static HttpByteBuf httpBuffer(final ChannelHandlerContext ctx) {
    final ByteBufAllocator allocator = ctx.alloc();
    final ByteBuf buffer = allocator.buffer();
    return new HttpByteBuf(buffer);
  }

  static HttpByteBuf buildHttpBuffer(final ChannelHandlerContext ctx, final HttpByteBufConsumer block) {
    final HttpByteBuf httpByteBuf = httpBuffer(ctx);
    block.accept(httpByteBuf);
    return httpByteBuf;
  }

  void writeStatusLine() {
    final String status = "HTTP/%s %d %s%n".formatted("1.1", 200, "OK");
    this.inner.writeCharSequence(status, StandardCharsets.US_ASCII);
  }

  void writeHeader() {
    final String write = "%s: %s%n".formatted("Content-Type", "application/zip");
    this.inner.writeCharSequence(write, StandardCharsets.US_ASCII);
  }

  void writeText(final String text) {
    this.inner.writeCharSequence("\n" + text, StandardCharsets.US_ASCII);
  }

  void writeBytes(final byte[] bytes) {
    this.inner.writeCharSequence("\n", StandardCharsets.US_ASCII);
    this.inner.writeBytes(bytes);
  }

  @FunctionalInterface
  interface HttpByteBufConsumer {
    void accept(HttpByteBuf httpByteBuf);
  }
}
