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
  void shouldFindAListedServerByTheTextACommandNamesItWith(
    @ForAll("hosts") final String host,
    @ForAll @IntRange(min = 1, max = 65_535) final int port
  ) {
    final VncAllowList.Entry entry = VncAllowList.Entry.of(host, port, "secret");
    final VncAllowList list = VncAllowList.of(List.of(entry));

    final String server = VncAllowList.format(entry.getHost(), entry.getPort());
    final VncAllowList.Entry found = list.find(server);
    assertSame(entry, found);

    final boolean contained = entry.toString().contains("secret");
    assertFalse(contained);
  }

  @Property(seed = SEED)
  void shouldFindNoServerOnAnotherPort(@ForAll("hosts") final String host, @ForAll @IntRange(min = 1, max = 65_534) final int port) {
    final VncAllowList list = VncAllowList.of(List.of(VncAllowList.Entry.of(host, port, null)));

    final String server = VncAllowList.format(host, port + 1);
    final VncAllowList.Entry found = list.find(server);
    assertNull(found);
  }

  @Property(seed = SEED)
  void shouldReadWhateverIsTypedAsAHostAndAPortOrNothing(@ForAll final String text) {
    final VncAllowList.Target target = VncAllowList.parse(text);
    if (target == null) {
      return;
    }

    final boolean blank = target.host().isBlank();
    assertFalse(blank);

    final boolean matches = target.port() >= 1 && target.port() <= 65_535;
    assertTrue(matches);

    final String server = VncAllowList.format(target.host(), target.port());
    final VncAllowList.Target parsed = VncAllowList.parse(server);
    assertEquals(target, parsed);
  }
}
