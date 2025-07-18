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
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;

@Sharable
abstract class Injector extends ChannelDuplexHandler {

  abstract boolean isRelevant(InjectorContext ctx);

  abstract boolean onRead(ChannelHandlerContext ctx, ByteBuf buf);

  /**
   * {@inheritDoc}
   */
  @Override
  public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
    final ByteBuf buf = (ByteBuf) msg;
    final ChannelPipeline pipeline = ctx.pipeline();
    final InjectorContext context = new InjectorContext(pipeline, buf);
    if (!this.isRelevant(context)) {
      super.channelRead(ctx, msg);
      return;
    }
    final boolean shouldDelegate = !this.onRead(ctx, buf);
    if (shouldDelegate) {
      super.channelRead(ctx, msg);
    }
  }
}
