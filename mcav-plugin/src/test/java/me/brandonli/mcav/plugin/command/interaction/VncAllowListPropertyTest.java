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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

/**
 * Properties of {@link VncAllowList}: a listed server is found by the text an entry names it with, a server that is not
 * listed is never found, and whatever a player types either names a host and a TCP port or nothing.
 */
final class VncAllowListPropertyTest {

  private static final String SEED = "20260928";

  @Provide
  Arbitrary<String> hosts() {
    final Arbitrary<String> names = Arbitraries.strings()
      .withChars("abcdefghijklmnopqrstuvwxyz0123456789.-")
      .ofMinLength(1)
      .ofMaxLength(40);
    final Arbitrary<String> addresses = Arbitraries.strings()
      .withChars("0123456789abcdef:")
      .ofMinLength(2)
      .ofMaxLength(39)
      .filter(text -> text.contains(":"));
    return Arbitraries.oneOf(names, addresses);
  }

  @Property(seed = SEED)
  void findsAListedServerByTheTextItsEntryNamesItWith(
    @ForAll("hosts") final String host,
    @ForAll @IntRange(min = 1, max = 65_535) final int port
  ) {
    final VncAllowList.Entry entry = new VncAllowList.Entry(host, port, "secret");
    final VncAllowList list = new VncAllowList(List.of(entry));
    assertSame(entry, list.find(entry.toString()));
    assertFalse(entry.toString().contains("secret"));
  }

  @Property(seed = SEED)
  void findsNoServerOnAnotherPort(@ForAll("hosts") final String host, @ForAll @IntRange(min = 1, max = 65_534) final int port) {
    final VncAllowList list = new VncAllowList(List.of(new VncAllowList.Entry(host, port, null)));
    assertNull(list.find(new VncAllowList.Entry(host, port + 1, null).toString()));
  }

  @Property(seed = SEED)
  void readsWhateverIsTypedAsAHostAndAPortOrNothing(@ForAll final String text) {
    final VncAllowList.Target target = VncAllowList.parse(text);
    if (target == null) {
      return;
    }
    assertFalse(target.host().isBlank());
    assertTrue(target.port() >= 1 && target.port() <= 65_535);
    assertEquals(target, VncAllowList.parse(new VncAllowList.Entry(target.host(), target.port(), null).toString()));
  }
}
