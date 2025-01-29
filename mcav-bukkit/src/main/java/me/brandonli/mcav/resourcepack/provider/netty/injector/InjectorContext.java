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
package me.brandonli.mcav.resourcepack.provider.netty.injector;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelPipeline;

public final class InjectorContext {

  private final ChannelPipeline pipeline;
  private final ByteBuf message;

  public InjectorContext(final ChannelPipeline pipeline, final ByteBuf message) {
    this.pipeline = pipeline;
    this.message = message;
  }

  public ChannelPipeline getPipeline() {
    return this.pipeline;
  }

  public ByteBuf getMessage() {
    return this.message;
  }
}
