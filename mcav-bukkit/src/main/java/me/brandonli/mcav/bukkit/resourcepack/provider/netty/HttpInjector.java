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
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;

@Sharable
abstract class HttpInjector extends Injector {

  abstract HttpByteBuf intercept(final ChannelHandlerContext ctx, final HttpRequest request);

  @Override
  boolean isRelevant(final InjectorContext ctx) {
    return isRequestGet(ctx.getMessage());
  }

  @Override
  final boolean onRead(final ChannelHandlerContext ctx, final ByteBuf buf) {
    final HttpRequest request = HttpRequest.parse(buf);
    final HttpByteBuf response = this.intercept(ctx, request);
    final ByteBuf inner = response.getInner();
    ctx.writeAndFlush(inner).addListener(ChannelFutureListener.CLOSE);
    return true;
  }

  private static boolean isRequestMethod(final ByteBuf buf, final String method) {
    for (int i = 0; i < method.length(); i++) {
      final char charAt = method.charAt(i);
      final int byteValue = buf.getUnsignedByte(i);
      if (charAt != (char) byteValue) {
        return false;
      }
    }
    return true;
  }

  private static boolean isRequestGet(final ByteBuf buf) {
    return isRequestMethod(buf, "GET ");
  }
}
