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

final class OnlinePlayersTest {

  private static final String EVENT_NULL_MESSAGE = "Event must not be null!";

  @Test
  void shouldFollowThePlayersFromThoseOnlineAtTheStart() {
    final Server server = TestServer.reset();
    final UUID alice = UUID.randomUUID();
    final UUID bob = UUID.randomUUID();
    doReturn(List.of(this.player(alice)))
      .when(server)
      .getOnlinePlayers();
    final Plugin plugin = mock(Plugin.class);
    final OnlinePlayers online = new OnlinePlayers();

    online.start(plugin);

    verify(TestServer.getPluginManager()).registerEvents(online, plugin);

    final Set<UUID> players = online.getPlayers();
    final Set<UUID> expectedPlayers = Set.of(alice);
    assertEquals(expectedPlayers, players);

    final Player joining = this.player(bob);
    online.onPlayerJoin(new PlayerJoinEvent(joining, (Component) null));
    final Set<UUID> expectedJoinedPlayers = Set.of(alice, bob);
    assertEquals(expectedJoinedPlayers, players, "a view that follows the joins");

    online.onPlayerQuit(new PlayerQuitEvent(this.player(alice), (Component) null, PlayerQuitEvent.QuitReason.DISCONNECTED));
    final Set<UUID> expectedRemainingPlayers = Set.of(bob);
    assertEquals(expectedRemainingPlayers, players);
    assertThrows(UnsupportedOperationException.class, () -> players.add(alice));

    try (final MockedStatic<HandlerList> lists = Mockito.mockStatic(HandlerList.class)) {
      online.shutdown();
      lists.verify(() -> HandlerList.unregisterAll(online));
    }

    final boolean empty = players.isEmpty();
    assertTrue(empty);
  }

  @Test
  void shouldRefuseNullArguments() {
    // with a test server, only the check can refuse a null plugin
    TestServer.reset();
    final OnlinePlayers online = new OnlinePlayers();
    assertThrows(NullPointerException.class, () -> online.start(null));
    assertThrows(NullPointerException.class, () -> online.onPlayerJoin(null));
    assertThrows(NullPointerException.class, () -> online.onPlayerQuit(null));
  }

  @Test
  void shouldRefuseANullJoinEventWithItsOwnMessage() {
    final OnlinePlayers online = new OnlinePlayers();
    final NullPointerException exception = assertThrows(NullPointerException.class, () -> online.onPlayerJoin(null));

    final String message = exception.getMessage();
    assertEquals(EVENT_NULL_MESSAGE, message);
  }

  @Test
  void shouldRefuseANullQuitEventWithItsOwnMessage() {
    final OnlinePlayers online = new OnlinePlayers();
    final NullPointerException exception = assertThrows(NullPointerException.class, () -> online.onPlayerQuit(null));

    final String message = exception.getMessage();
    assertEquals(EVENT_NULL_MESSAGE, message);
  }

  private Player player(final UUID uuid) {
    final Player player = mock(Player.class);
    when(player.getUniqueId()).thenReturn(uuid);

    return player;
  }
}
