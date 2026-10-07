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

import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The channel to the server, through NeoForge's network registry, which follows the channels the server registers. The
 * connection is the one of the session the player logged in to; ticks and the login events run on the client thread.
 */
final class NeoForgeChannel implements ReportChannel {

  private @Nullable Connection connection;

  /**
   * Follows the connection of a session the player just logged in to.
   *
   * @param connection the connection
   */
  void open(final Connection connection) {
    this.connection = connection;
  }

  /** Forgets the connection of a session the player left. */
  void close() {
    this.connection = null;
  }

  @Override
  public boolean isOpen() {
    final Connection current = this.connection;
    return current != null && NetworkRegistry.hasChannel(current, ConnectionProtocol.PLAY, Mcv2Payload.TYPE.id());
  }

  @Override
  public void send(final byte[] report) {
    ClientPacketDistributor.sendToServer(new Mcv2Payload(report));
  }
}
