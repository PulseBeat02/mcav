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
package me.brandonli.mcav.mod;

import com.google.common.base.MoreObjects;
import io.netty.buffer.ByteBuf;
import java.util.Arrays;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.checkerframework.checker.nullness.qual.Nullable;

final class Mcv2Payload implements CustomPacketPayload {

  private static final Identifier CHANNEL = Identifier.fromNamespaceAndPath(Mcv2Shaders.NAMESPACE, "mcv2");

  static final CustomPacketPayload.Type<Mcv2Payload> TYPE = new CustomPacketPayload.Type<>(CHANNEL);

  // the bytes as they are, which is how a Bukkit server hands them to the plugin
  static final StreamCodec<ByteBuf, Mcv2Payload> CODEC = StreamCodec.of(Mcv2Payload::write, Mcv2Payload::read);

  private final byte[] report;

  Mcv2Payload(final byte[] report) {
    this.report = report.clone();
  }

  byte[] getReport() {
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

  @Override
  public boolean equals(final @Nullable Object other) {
    if (this == other) {
      return true;
    }

    if (!(other instanceof final Mcv2Payload payload)) {
      return false;
    }

    return Arrays.equals(this.report, payload.report);
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(this.report);
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this).add("report", Arrays.toString(this.report)).toString();
  }
}
