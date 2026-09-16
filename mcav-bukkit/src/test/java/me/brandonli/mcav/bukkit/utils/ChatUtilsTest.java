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
package me.brandonli.mcav.bukkit.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import me.brandonli.mcav.bukkit.testing.FakeServer;
import me.brandonli.mcav.bukkit.testing.UtilityClassAssertions;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link ChatUtils}.
 */
final class ChatUtilsTest {

  private static final int RED = 0xFFFF0000;
  private static final int GREEN = 0xFF00FF00;
  private static final int BLUE = 0xFF0000FF;

  /**
   * Gets the color of every visible character of the component, skipping line breaks.
   */
  private static List<Integer> getCharacterColors(final Component component) {
    final List<Integer> colors = new ArrayList<>();
    final FormattedText.StyledContentConsumer<Void> consumer = (style, content) -> {
      final TextColor color = style.getColor();
      final int value = color == null ? -1 : color.getValue();
      for (int index = 0; index < content.length(); index++) {
        final char character = content.charAt(index);
        if (character != '\n') {
          colors.add(value);
        }
      }
      return Optional.empty();
    };
    component.visit(consumer, Style.EMPTY);
    return colors;
  }

  @Test
  void createsUniqueInvisibleStringsFromTheHexDigitsOfTheIndex() {
    final String zero = ChatUtils.getUniqueString(0);
    final String ten = ChatUtils.getUniqueString(10);
    final String large = ChatUtils.getUniqueString(0x1f0);
    final Set<String> entries = new HashSet<>();
    for (int index = 0; index < 300; index++) {
      final String entry = ChatUtils.getUniqueString(index);
      entries.add(entry);
    }
    final int uniqueCount = entries.size();
    assertEquals("§0", zero);
    assertEquals("§a", ten);
    assertEquals("§1§f§0", large);
    assertEquals(300, uniqueCount);
    assertThrows(IllegalArgumentException.class, () -> ChatUtils.getUniqueString(-1));
  }

  @Test
  void writesAColorCodeOnlyWhenTheColorChanges() {
    final int[] pixels = { 0xFF112233, 0x00112233, 0xFF000000, 0x7F445566 };
    final String line = ChatUtils.createRawLine(pixels, "#", 4, 0);
    assertEquals("§x§1§1§2§2§3§3##§x§0§0§0§0§0§0#§x§4§4§5§5§6§6#", line);
  }

  @Test
  void convertsTheRequestedRowOnly() {
    final int[] pixels = { RED, RED, GREEN, BLUE };
    final String line = ChatUtils.createRawLine(pixels, "ab", 2, 1);
    assertEquals("§x§0§0§f§f§0§0ab§x§0§0§0§0§f§fab", line);
  }

  @Test
  void rejectsRowsOutsideOfTheImage() {
    final int[] pixels = new int[4];
    assertThrows(NullPointerException.class, () -> ChatUtils.createRawLine(null, "#", 2, 0));
    assertThrows(NullPointerException.class, () -> ChatUtils.createRawLine(pixels, null, 2, 0));
    assertThrows(IllegalArgumentException.class, () -> ChatUtils.createRawLine(pixels, "#", 0, 0));
    assertThrows(IllegalArgumentException.class, () -> ChatUtils.createRawLine(pixels, "#", 2, -1));
    assertThrows(IllegalArgumentException.class, () -> ChatUtils.createRawLine(pixels, "#", 2, 2));
  }

  @Test
  void createsOneColoredLineOfTextPerRow() {
    final int[] pixels = { RED, RED, GREEN, BLUE };
    final Component component = ChatUtils.createChatComponent(pixels, "#", 2, 2);
    final String text = component.getString();
    final List<Integer> colors = getCharacterColors(component);
    final List<Integer> expectedColors = List.of(0xFF0000, 0xFF0000, 0x00FF00, 0x0000FF);
    assertEquals("##\n##", text);
    assertEquals(expectedColors, colors);
  }

  @Test
  void usesTheFirstPixelsWhenThereIsMoreDataThanNeeded() {
    final int[] pixels = { BLUE, GREEN, RED };
    final Component component = ChatUtils.createChatComponent(pixels, "█", 1, 2);
    final String text = component.getString();
    final List<Integer> colors = getCharacterColors(component);
    final List<Integer> expectedColors = List.of(0x0000FF, 0x00FF00);
    assertEquals("█\n█", text);
    assertEquals(expectedColors, colors);
  }

  @Test
  void rejectsImagesThatDoNotMatchThePixels() {
    final int[] pixels = new int[4];
    assertThrows(NullPointerException.class, () -> ChatUtils.createChatComponent(null, "#", 2, 2));
    assertThrows(NullPointerException.class, () -> ChatUtils.createChatComponent(pixels, null, 2, 2));
    assertThrows(IllegalArgumentException.class, () -> ChatUtils.createChatComponent(pixels, "#", 0, 2));
    assertThrows(IllegalArgumentException.class, () -> ChatUtils.createChatComponent(pixels, "#", 2, 0));
    assertThrows(IllegalArgumentException.class, () -> ChatUtils.createChatComponent(pixels, "#", 3, 2));
  }

  @Test
  void clearsTheChatOfOnlineViewersByPushingOutTheHistory() throws ReflectiveOperationException {
    final UUID online = UUID.randomUUID();
    final UUID offline = UUID.randomUUID();
    try (final FakeServer server = FakeServer.start()) {
      server.addPlayer(online);
      server.injectModule();
      final List<UUID> viewers = List.of(online, offline);
      ChatUtils.clearChat(viewers);
      final List<Packet<?>> packets = server.getSentPackets(online);
      final int packetCount = packets.size();
      final Packet<?> packet = packets.getFirst();
      final ClientboundSystemChatPacket chatPacket = assertInstanceOf(ClientboundSystemChatPacket.class, packet);
      final Component content = chatPacket.content();
      final String text = content.getString();
      final boolean overlay = chatPacket.overlay();
      final String expected = "\n".repeat(99);
      assertEquals(1, packetCount);
      assertEquals(expected, text);
      assertFalse(overlay);
      assertThrows(NullPointerException.class, () -> ChatUtils.clearChat(null));
    }
  }

  @Test
  void cannotBeInstantiated() {
    UtilityClassAssertions.assertNotInstantiable(ChatUtils.class);
  }
}
