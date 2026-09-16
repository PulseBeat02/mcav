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
package me.brandonli.mcav.bukkit.media.result;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.UUID;
import me.brandonli.mcav.bukkit.media.config.ChatConfiguration;
import me.brandonli.mcav.bukkit.testing.FakeServer;
import me.brandonli.mcav.bukkit.testing.Images;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.metadata.OriginalVideoMetadata;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link ChatResult}.
 */
final class ChatResultTest {

  private static final UUID VIEWER = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID OFFLINE = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final String CLEARED_CHAT = "\n".repeat(99);

  private FakeServer server;
  private ChatResult result;

  @BeforeEach
  void startServer() throws ReflectiveOperationException {
    this.server = FakeServer.start();
    this.server.addPlayer(VIEWER);
    this.server.injectModule();
    final ChatConfiguration.Builder<?> builder = ChatConfiguration.builder();
    builder.viewers(List.of(VIEWER, OFFLINE));
    builder.character("#");
    builder.chatWidth(2);
    builder.chatHeight(1);
    final ChatConfiguration configuration = builder.build();
    this.result = new ChatResult(configuration);
  }

  @AfterEach
  void stopServer() {
    this.server.close();
  }

  private String getFirstMessage() {
    final List<Packet<?>> packets = this.server.getSentPackets(VIEWER);
    final Packet<?> packet = packets.getFirst();
    final ClientboundSystemChatPacket chatPacket = assertInstanceOf(ClientboundSystemChatPacket.class, packet);
    final boolean overlay = chatPacket.overlay();

    assertFalse(overlay, "messages go into the chat, not the action bar");

    final Component content = chatPacket.content();
    return content.getString();
  }

  @Test
  void startsOnAnEmptyChat() {
    this.result.start();

    final String message = this.getFirstMessage();

    assertEquals(CLEARED_CHAT, message);
  }

  @Test
  void sendsEveryFrameAsOneMessageSizedToTheChat() {
    final ImageBuffer image = Images.solid(40, 20, 0xFF336699);
    final OriginalVideoMetadata metadata = mock(OriginalVideoMetadata.class);

    final boolean handled = this.result.applyFilter(image, metadata);

    final String message = this.getFirstMessage();
    final int width = image.getWidth();
    final int height = image.getHeight();
    final List<Packet<?>> packets = this.server.getSentPackets(VIEWER);
    final int packetCount = packets.size();

    assertTrue(handled);
    assertEquals("##", message);
    assertEquals(2, width);
    assertEquals(1, height);
    assertEquals(1, packetCount);
  }

  @Test
  void clearsTheChatWhenReleased() {
    this.result.release();

    final String message = this.getFirstMessage();

    assertEquals(CLEARED_CHAT, message);
  }

  @Test
  void rejectsMissingArguments() {
    final OriginalVideoMetadata metadata = mock(OriginalVideoMetadata.class);

    assertThrows(NullPointerException.class, () -> new ChatResult(null));
    assertThrows(NullPointerException.class, () -> this.result.applyFilter(null, metadata));

    try (final ImageBuffer image = Images.solid(1, 1, 0)) {
      assertThrows(NullPointerException.class, () -> this.result.applyFilter(image, null));
    }

    final List<Packet<?>> packets = this.server.getSentPackets(VIEWER);
    final boolean nothingSent = packets.isEmpty();

    assertTrue(nothingSent);
  }
}
