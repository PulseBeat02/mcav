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
package me.brandonli.mcav.bukkit.media.mcv2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import me.brandonli.mcav.bukkit.testing.FakeServer;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventException;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent.Status;
import org.bukkit.plugin.EventExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

final class Mcv2ViewersTest {

  private static final UUID PACK = UUID.fromString("00000000-0000-0000-0000-00000000aaaa");

  private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-00000000bbbb");

  private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000021");

  private FakeServer server;

  private Player player;

  private final List<Player> refused = new ArrayList<>();

  @BeforeEach
  void startServer() throws ReflectiveOperationException {
    this.server = FakeServer.start();
    this.server.injectModule();
    this.player = mock(Player.class);
    when(this.player.getUniqueId()).thenReturn(PLAYER);
  }

  @AfterEach
  void stopServer() {
    this.server.close();
  }

  private Mcv2Viewers viewers() {
    return new Mcv2Viewers(PACK, this.refused::add);
  }

  private PlayerResourcePackStatusEvent status(final UUID pack, final Status status) {
    return new PlayerResourcePackStatusEvent(this.player, pack, status);
  }

  @Test
  void followsWhatTheClientReports() {
    final Mcv2Viewers viewers = this.viewers();
    final UUID uuid = PLAYER;
    assertNull(viewers.getState(uuid));
    viewers.requested(uuid);
    assertEquals(Mcv2Viewers.PackState.REQUESTED, viewers.getState(uuid));
    viewers.handleStatus(this.status(PACK, Status.ACCEPTED));
    viewers.handleStatus(this.status(PACK, Status.DOWNLOADED));
    assertFalse(viewers.isLoaded(uuid));
    viewers.handleStatus(this.status(PACK, Status.SUCCESSFULLY_LOADED));
    assertTrue(viewers.isLoaded(uuid));
    // another pack's answers are not this pack's
    viewers.handleStatus(this.status(OTHER, Status.DECLINED));
    assertTrue(viewers.isLoaded(uuid));
    viewers.handleStatus(this.status(PACK, Status.FAILED_RELOAD));
    assertEquals(List.of(this.player), this.refused, "the first refusal is reported at once");
    viewers.handleStatus(this.status(PACK, Status.DISCARDED));
    assertEquals(Mcv2Viewers.PackState.REFUSED, viewers.getState(uuid));
    assertEquals(List.of(this.player), this.refused, "the refusal is reported once");
    viewers.handleQuit(new PlayerQuitEvent(this.player, (Component) null, PlayerQuitEvent.QuitReason.DISCONNECTED));
    assertNull(viewers.getState(uuid));
    assertThrows(NullPointerException.class, () -> viewers.requested(null));
    assertThrows(NullPointerException.class, () -> viewers.getState(null));
    assertThrows(NullPointerException.class, () -> new Mcv2Viewers(null, this.refused::add));
    assertThrows(NullPointerException.class, () -> new Mcv2Viewers(PACK, null));
  }

  @Test
  void unregistersTheListenerItReplacesOrStops() {
    try (MockedStatic<HandlerList> lists = Mockito.mockStatic(HandlerList.class)) {
      final Mcv2Viewers viewers = this.viewers();
      // nothing registered: nothing to unregister
      viewers.unregister();
      lists.verifyNoInteractions();
      viewers.register();
      viewers.register();
      // the second registration replaced the first listener
      lists.verify(() -> HandlerList.unregisterAll(any(Listener.class)), times(1));
      viewers.unregister();
      lists.verify(() -> HandlerList.unregisterAll(any(Listener.class)), times(2));
      viewers.unregister();
      lists.verify(() -> HandlerList.unregisterAll(any(Listener.class)), times(2));
    }
  }

  @Test
  void listensUntilUnregistered() throws EventException {
    final Mcv2Viewers viewers = this.viewers();
    viewers.unregister();
    viewers.register();
    viewers.register();
    final ArgumentCaptor<EventExecutor> executors = ArgumentCaptor.forClass(EventExecutor.class);
    final ArgumentCaptor<Listener> listeners = ArgumentCaptor.forClass(Listener.class);
    verify(this.server.getPluginManager(), times(2)).registerEvent(
      eq(PlayerResourcePackStatusEvent.class),
      listeners.capture(),
      eq(EventPriority.MONITOR),
      executors.capture(),
      eq(this.server.getPlugin())
    );
    // the connection cache of the module listens to players leaving too, so this tracker's listener is picked out
    final ArgumentCaptor<Listener> quitListeners = ArgumentCaptor.forClass(Listener.class);
    final ArgumentCaptor<EventExecutor> quits = ArgumentCaptor.forClass(EventExecutor.class);
    verify(this.server.getPluginManager(), atLeast(2)).registerEvent(
      eq(PlayerQuitEvent.class),
      quitListeners.capture(),
      eq(EventPriority.MONITOR),
      quits.capture(),
      eq(this.server.getPlugin())
    );
    final Listener listener = listeners.getValue();
    final EventExecutor quit = quits.getAllValues().get(quitListeners.getAllValues().lastIndexOf(listener));
    executors.getValue().execute(listener, this.status(PACK, Status.SUCCESSFULLY_LOADED));
    assertTrue(viewers.isLoaded(PLAYER));
    final Event left = new PlayerQuitEvent(this.player, (Component) null, PlayerQuitEvent.QuitReason.DISCONNECTED);
    quit.execute(listener, left);
    assertNull(viewers.getState(PLAYER));
    viewers.unregister();
  }
}
