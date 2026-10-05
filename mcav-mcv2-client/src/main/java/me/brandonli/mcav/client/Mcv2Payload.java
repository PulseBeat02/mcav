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
package me.brandonli.mcav.client;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** The one payload of the {@code mcav:mcv2} channel: a report's bytes, as the server reads them. */
final class Mcv2Payload implements CustomPacketPayload {

  /** The channel, in MCV2's namespace, which carries these reports and nothing else. */
  static final CustomPacketPayload.Type<Mcv2Payload> TYPE = new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("mcav", "mcv2"));

  /** Writes the report's bytes as they are, which is how a Bukkit server hands them to the plugin. */
  static final StreamCodec<ByteBuf, Mcv2Payload> CODEC = StreamCodec.of(Mcv2Payload::write, Mcv2Payload::read);

  private final byte[] report;

  /**
   * Constructs a payload.
   *
   * @param report the report's bytes, copied
   */
  Mcv2Payload(final byte[] report) {
    this.report = report.clone();
  }

  /** A copy of the report's bytes. */
  byte[] report() {
    return this.report.clone();
  }

  @Override
  public CustomPacketPayload.Type<Mcv2Payload> type() {
    return TYPE;
  }

  private static void write(final ByteBuf buffer, final Mcv2Payload payload) {
    buffer.writeBytes(payload.report);
  }

  private static Mcv2Payload read(final ByteBuf buffer) {
    final byte[] report = new byte[buffer.readableBytes()];
    buffer.readBytes(report);
    return new Mcv2Payload(report);
  }
}
