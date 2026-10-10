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
package me.brandonli.mcav.plugin.listener;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import me.brandonli.mcav.plugin.testing.TestServer;
import net.kyori.adventure.text.Component;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Tests {@link OnlinePlayers}: the set starts from the players online and follows every join and quit.
 */
final class OnlinePlayersTest {

  private static Player player(final UUID uuid) {
    final Player player = mock(Player.class);
    when(player.getUniqueId()).thenReturn(uuid);
    return player;
  }

  @Test
  void followsThePlayersFromThoseOnlineAtTheStart() {
    final Server server = TestServer.reset();
    final UUID alice = UUID.randomUUID();
    final UUID bob = UUID.randomUUID();
    doReturn(List.of(player(alice)))
      .when(server)
      .getOnlinePlayers();
    final Plugin plugin = mock(Plugin.class);
    final OnlinePlayers online = new OnlinePlayers();

    online.start(plugin);

    verify(TestServer.pluginManager()).registerEvents(online, plugin);
    final Set<UUID> players = online.getPlayers();
    assertEquals(Set.of(alice), players);
    final Player joining = player(bob);
    online.onJoin(new PlayerJoinEvent(joining, (Component) null));
    assertEquals(Set.of(alice, bob), players, "a view that follows the joins");
    online.onQuit(new PlayerQuitEvent(player(alice), (Component) null, PlayerQuitEvent.QuitReason.DISCONNECTED));
    assertEquals(Set.of(bob), players);
    assertThrows(UnsupportedOperationException.class, () -> players.add(alice));
    try (final MockedStatic<HandlerList> lists = Mockito.mockStatic(HandlerList.class)) {
      online.stop();
      lists.verify(() -> HandlerList.unregisterAll(online));
    }
    assertTrue(players.isEmpty());
  }
}
