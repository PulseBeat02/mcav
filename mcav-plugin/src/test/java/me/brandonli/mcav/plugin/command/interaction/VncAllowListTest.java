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
package me.brandonli.mcav.plugin.command.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests {@link VncAllowList}: a command can name only a server an operator listed, by its host and port, and an entry
 * never shows its password.
 */
final class VncAllowListTest {

  private static final VncAllowList.Entry DESKTOP = new VncAllowList.Entry("Desktop.Example", 5901, "secret");

  private static final VncAllowList.Entry LOOPBACK = new VncAllowList.Entry("::1", 5902, null);

  private static final VncAllowList LIST = new VncAllowList(List.of(DESKTOP, LOOPBACK));

  @Test
  void parsesAHostAndAPort() {
    assertEquals(new VncAllowList.Target("desktop.example", 5901), VncAllowList.parse("desktop.example:5901"));
    assertEquals(new VncAllowList.Target("127.0.0.1", 1), VncAllowList.parse("127.0.0.1:1"));
    assertEquals(new VncAllowList.Target("example", 65_535), VncAllowList.parse("example:65535"));
  }

  @Test
  void parsesAnIpv6AddressInBrackets() {
    assertEquals(new VncAllowList.Target("::1", 5902), VncAllowList.parse("[::1]:5902"));
  }

  @ParameterizedTest
  @ValueSource(
    strings = {
      "",
      "desktop",
      "desktop:",
      ":5901",
      "::1:5902",
      "[]:5901",
      "desktop:0",
      "desktop:65536",
      "desktop:59o1",
      "desktop:+5901",
      "desktop:123456",
      " :5901",
      "[desktop]:5901",
      "[[::1]]:5902",
      "desk top:5901",
      "[::1:5902",
      "desk]top:5901",
      "desk[top:5901",
    }
  )
  void refusesTextThatIsNotAHostAndAPort(final String text) {
    assertNull(VncAllowList.parse(text));
  }

  @Test
  void findsAListedServerWhateverTheCaseOfItsHost() {
    assertSame(DESKTOP, LIST.find("DESKTOP.example:5901"));
    assertSame(LOOPBACK, LIST.find("[::1]:5902"));
  }

  @ParameterizedTest
  @ValueSource(strings = { "desktop.example:5902", "other.example:5901", "desktop.example", "[::1]:5901" })
  void findsNoServerThatIsNotListed(final String text) {
    assertNull(LIST.find(text));
  }

  @Test
  void anEmptyListAllowsNoServer() {
    assertNull(VncAllowList.NONE.find("desktop.example:5901"));
  }

  @Test
  void namesAServerWithoutItsPassword() {
    assertEquals("Desktop.Example:5901", DESKTOP.toString());
    assertEquals("[::1]:5902", LOOPBACK.toString());
  }

  @Test
  void findsAnIpv6AddressListedInBrackets() {
    final VncAllowList.Entry bracketed = new VncAllowList.Entry("[::1]", 5902, null);
    assertEquals("::1", bracketed.host());
    assertSame(bracketed, new VncAllowList(List.of(bracketed)).find("[::1]:5902"));
    assertEquals("[::1]:5902", bracketed.toString());
    final VncAllowList.Entry unbalanced = new VncAllowList.Entry("[::1", 5902, null);
    assertEquals("[::1", unbalanced.host(), "only a host in both brackets loses them");
  }

  @Test
  void refusesAnEntryWithoutAHostOrWithoutATcpPort() {
    assertThrows(IllegalArgumentException.class, () -> new VncAllowList.Entry(" ", 5901, null));
    assertThrows(IllegalArgumentException.class, () -> new VncAllowList.Entry("[]", 5901, null));
    assertThrows(IllegalArgumentException.class, () -> new VncAllowList.Entry("desktop", 0, null));
    assertThrows(IllegalArgumentException.class, () -> new VncAllowList.Entry("desktop", 65_536, null));
  }

  @Test
  void keepsItsEntriesWhenTheListItWasGivenChanges() {
    final List<VncAllowList.Entry> entries = new ArrayList<>(List.of(DESKTOP));
    final VncAllowList list = new VncAllowList(entries);
    entries.clear();
    assertSame(DESKTOP, list.find("desktop.example:5901"));
  }
}
