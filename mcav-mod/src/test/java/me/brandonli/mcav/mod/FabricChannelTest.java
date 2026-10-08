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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

final class FabricChannelTest {

  @Test
  void isOpenOnceTheServerRegisteredTheChannelAndSendsThroughFabric() {
    try (final MockedStatic<ClientPlayNetworking> networking = mockStatic(ClientPlayNetworking.class)) {
      final FabricChannel channel = new FabricChannel();
      assertFalse(channel.isOpen());
      networking.when(() -> ClientPlayNetworking.canSend(Mcv2Payload.TYPE)).thenReturn(true);
      assertTrue(channel.isOpen());

      channel.send(new byte[] { 1, 1, 1, 0 });
      final ArgumentCaptor<Mcv2Payload> sent = ArgumentCaptor.forClass(Mcv2Payload.class);
      networking.verify(() -> ClientPlayNetworking.send(sent.capture()));
      assertArrayEquals(new byte[] { 1, 1, 1, 0 }, sent.getValue().report());
    }
  }
}
