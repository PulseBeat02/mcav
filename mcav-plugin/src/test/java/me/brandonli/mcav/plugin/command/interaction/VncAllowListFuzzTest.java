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

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.util.List;
import org.junit.jupiter.api.Tag;

/**
 * Fuzzes the server a player names in {@code /mcav vnc create}: whatever the text, it is read as a host and a TCP port
 * or refused, and it finds a listed server only when it names that server's host and port.
 */
@Tag("fuzz")
final class VncAllowListFuzzTest {

  private static final VncAllowList.Entry LISTED = new VncAllowList.Entry("desktop.example", 5901, "secret");

  private static final VncAllowList LIST = new VncAllowList(List.of(LISTED));

  @FuzzTest(maxDuration = "30s")
  void shouldFindOnlyTheListedServer(final FuzzedDataProvider data) {
    final String text = data.consumeRemainingAsString();
    final VncAllowList.Target target = VncAllowList.parse(text);
    final VncAllowList.Entry found = LIST.find(text);
    if (found != null) {
      final boolean matches = target != null && target.port() == LISTED.port() && target.host().equalsIgnoreCase(LISTED.host());
      assertTrue(matches, text);
    }
    if (target != null) {
      final boolean secondMatches = !target.host().isBlank() && target.port() >= 1 && target.port() <= 65_535;
      assertTrue(secondMatches, text);
    }
  }
}
