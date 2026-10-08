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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

final class NeoForgeChannelTest {

  @Test
  void isOpenWhileTheConnectionOfTheSessionHasTheChannel() {
    final Connection connection = mock(Connection.class);
    try (final MockedStatic<NetworkRegistry> registry = mockStatic(NetworkRegistry.class)) {
      final NeoForgeChannel channel = new NeoForgeChannel();
      assertFalse(channel.isOpen(), "no session, no channel");
      registry.verifyNoInteractions();

      channel.open(connection);
      assertFalse(channel.isOpen(), "the server has not registered the channel yet");
      registry.when(() -> NetworkRegistry.hasChannel(connection, ConnectionProtocol.PLAY, Mcv2Payload.TYPE.id())).thenReturn(true);
      assertTrue(channel.isOpen());

      channel.close();
      assertFalse(channel.isOpen());
    }
  }

  @Test
  void sendsThroughNeoForgesDistributor() {
    try (final MockedStatic<ClientPacketDistributor> distributor = mockStatic(ClientPacketDistributor.class)) {
      new NeoForgeChannel().send(new byte[] { 1, 1, 2, 0 });
      final ArgumentCaptor<Mcv2Payload> sent = ArgumentCaptor.forClass(Mcv2Payload.class);
      distributor.verify(() -> ClientPacketDistributor.sendToServer(sent.capture()));
      assertArrayEquals(new byte[] { 1, 1, 2, 0 }, sent.getValue().report());
    }
  }
}
