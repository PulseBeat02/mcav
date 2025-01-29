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

import static java.util.Objects.requireNonNull;

import io.netty.channel.ChannelHandlerContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class ResourcePackInjector extends HttpInjector {

  private static final String INJECTOR_SYSTEM_PROPERTY = "mcav.resourcepack";

  @Override
  HttpByteBuf intercept(final ChannelHandlerContext ctx, final HttpRequest request) {
    try {
      final HttpByteBuf buf = HttpByteBuf.httpBuffer(ctx);
      final Path zip = this.getZipPath();
      final byte[] bytes = Files.readAllBytes(zip);
      buf.writeStatusLine();
      buf.writeHeader();
      buf.writeBytes(bytes);
      return buf;
    } catch (final IOException e) {
      throw new InjectorException(e.getMessage(), e);
    }
  }

  private Path getZipPath() {
    final String property = requireNonNull(System.getProperty(INJECTOR_SYSTEM_PROPERTY));
    return Path.of(property);
  }
}
