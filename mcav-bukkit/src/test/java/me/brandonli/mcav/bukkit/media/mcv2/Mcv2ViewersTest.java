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
import java.util.Optional;
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

  private static final String REFUSED_ONCE_MESSAGE = "the refusal is reported once";

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
  void shouldFollowWhatTheClientReports() {
    final Mcv2Viewers viewers = this.viewers();
    final UUID uuid = PLAYER;
    final Optional<Mcv2Viewers.PackState> stateBeforeRequest = viewers.getState(uuid);
    final boolean stateAbsentBeforeRequest = stateBeforeRequest.isEmpty();
    assertTrue(stateAbsentBeforeRequest);

    viewers.recordRequest(uuid);
    final Mcv2Viewers.PackState requestedState = viewers.getState(uuid).orElseThrow();
    assertEquals(Mcv2Viewers.PackState.REQUESTED, requestedState);

    viewers.onResourcePackStatus(this.status(PACK, Status.ACCEPTED));
    viewers.onResourcePackStatus(this.status(PACK, Status.DOWNLOADED));
    final boolean loadedAfterDownload = viewers.isLoaded(uuid);
    assertFalse(loadedAfterDownload);

    viewers.onResourcePackStatus(this.status(PACK, Status.SUCCESSFULLY_LOADED));
    final boolean loaded = viewers.isLoaded(uuid);
    assertTrue(loaded);

    // another pack's answers are not this pack's
    viewers.onResourcePackStatus(this.status(OTHER, Status.DECLINED));
    final boolean loadedAfterOtherPack = viewers.isLoaded(uuid);
    assertTrue(loadedAfterOtherPack);

    viewers.onResourcePackStatus(this.status(PACK, Status.FAILED_RELOAD));
    final List<Player> expectedRefused = List.of(this.player);
    assertEquals(expectedRefused, this.refused, "the first refusal is reported at once");

    viewers.onResourcePackStatus(this.status(PACK, Status.DISCARDED));
    final Mcv2Viewers.PackState refusedState = viewers.getState(uuid).orElseThrow();
    assertEquals(Mcv2Viewers.PackState.REFUSED, refusedState);

    final List<Player> expectedRefusedOnce = List.of(this.player);
    assertEquals(expectedRefusedOnce, this.refused, REFUSED_ONCE_MESSAGE);

    viewers.onPlayerQuit(new PlayerQuitEvent(this.player, (Component) null, PlayerQuitEvent.QuitReason.DISCONNECTED));
    final Optional<Mcv2Viewers.PackState> stateAfterQuit = viewers.getState(uuid);
    final boolean stateAbsentAfterQuit = stateAfterQuit.isEmpty();
    assertTrue(stateAbsentAfterQuit);
  }

  @Test
  void shouldRefuseANullPlayer() {
    final Mcv2Viewers viewers = this.viewers();
    final NullPointerException missingPlayer = assertThrows(NullPointerException.class, () -> viewers.recordRequest(null));
    final String missingPlayerMessage = missingPlayer.getMessage();
    assertEquals("Player must not be null!", missingPlayerMessage);

    assertThrows(NullPointerException.class, () -> viewers.getState(null));
  }

  @Test
  void shouldRefuseANullPackIdOrRefusedCallback() {
    assertThrows(NullPointerException.class, () -> new Mcv2Viewers(null, this.refused::add));
    assertThrows(NullPointerException.class, () -> new Mcv2Viewers(PACK, null));
  }

  @Test
  void shouldStartASessionWhenAClientLoadsThePackAnew() {
    final Mcv2Viewers viewers = this.viewers();
    final long sessionBeforeLoad = viewers.getSession(PLAYER);
    assertEquals(0L, sessionBeforeLoad, "no session before the pack loaded");

    viewers.onResourcePackStatus(this.status(PACK, Status.SUCCESSFULLY_LOADED));
    final long first = viewers.getSession(PLAYER);
    final boolean firstSessionStarted = first > 0;
    assertTrue(firstSessionStarted);

    viewers.onResourcePackStatus(this.status(PACK, Status.SUCCESSFULLY_LOADED));
    final long sessionAfterSecondReport = viewers.getSession(PLAYER);
    assertEquals(first, sessionAfterSecondReport, "the same load reported twice is one session");

    // the player leaves and joins again: their new client loads the pack anew
    viewers.onPlayerQuit(new PlayerQuitEvent(this.player, (Component) null, PlayerQuitEvent.QuitReason.DISCONNECTED));
    final long sessionAfterQuit = viewers.getSession(PLAYER);
    assertEquals(0L, sessionAfterQuit);

    viewers.onResourcePackStatus(this.status(PACK, Status.SUCCESSFULLY_LOADED));
    final long rejoined = viewers.getSession(PLAYER);
    final boolean newSessionAfterRejoin = rejoined > first;
    assertTrue(newSessionAfterRejoin, "a new session");

    // downloading the pack again before loading it is a new session too
    viewers.onResourcePackStatus(this.status(PACK, Status.DOWNLOADED));
    viewers.onResourcePackStatus(this.status(PACK, Status.SUCCESSFULLY_LOADED));
    final long reloaded = viewers.getSession(PLAYER);
    final boolean newSessionAfterReload = reloaded > rejoined;
    assertTrue(newSessionAfterReload);

    viewers.onResourcePackStatus(this.status(PACK, Status.FAILED_RELOAD));
    final long sessionAfterFailure = viewers.getSession(PLAYER);
    assertEquals(0L, sessionAfterFailure, "a client that failed to load the pack has no session");

    viewers.onResourcePackStatus(this.status(PACK, Status.SUCCESSFULLY_LOADED));
    final boolean newSessionAfterFailure = viewers.getSession(PLAYER) > reloaded;
    assertTrue(newSessionAfterFailure);

    viewers.retarget(OTHER);
    final long sessionAfterRetarget = viewers.getSession(PLAYER);
    assertEquals(0L, sessionAfterRetarget, "another pack ends every session");
  }

  @Test
  void shouldUnregisterTheListenerItReplacesOrStops() {
    try (final MockedStatic<HandlerList> lists = Mockito.mockStatic(HandlerList.class)) {
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
  void shouldListenUntilUnregistered() throws EventException {
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
    final boolean loaded = viewers.isLoaded(PLAYER);
    assertTrue(loaded);

    final Event left = new PlayerQuitEvent(this.player, (Component) null, PlayerQuitEvent.QuitReason.DISCONNECTED);
    quit.execute(listener, left);
    final Optional<Mcv2Viewers.PackState> stateAfterQuit = viewers.getState(PLAYER);
    final boolean stateAbsentAfterQuit = stateAfterQuit.isEmpty();
    assertTrue(stateAbsentAfterQuit);

    viewers.unregister();
  }

  @Test
  void shouldNotAskAPlayerWhoDeclinedAgainWhenThePackChanges() {
    final Mcv2Viewers viewers = this.viewers();
    final Player other = mock(Player.class);
    final UUID otherId = UUID.fromString("00000000-0000-0000-0000-000000000022");
    when(other.getUniqueId()).thenReturn(otherId);
    viewers.onResourcePackStatus(this.status(PACK, Status.DECLINED));
    viewers.onResourcePackStatus(this.status(PACK, Status.DECLINED));
    final Mcv2Viewers.PackState declinedState = viewers.getState(PLAYER).orElseThrow();
    assertEquals(Mcv2Viewers.PackState.DECLINED, declinedState);

    final List<Player> expectedRefused = List.of(this.player);
    assertEquals(expectedRefused, this.refused, REFUSED_ONCE_MESSAGE);

    viewers.onResourcePackStatus(new PlayerResourcePackStatusEvent(other, PACK, Status.SUCCESSFULLY_LOADED));
    final boolean otherLoaded = viewers.isLoaded(otherId);
    assertTrue(otherLoaded);

    viewers.retarget(OTHER);

    final UUID packId = viewers.getPackId();
    assertEquals(OTHER, packId);

    final Mcv2Viewers.PackState stateAfterRetarget = viewers.getState(PLAYER).orElseThrow();
    assertEquals(Mcv2Viewers.PackState.DECLINED, stateAfterRetarget);

    final Optional<Mcv2Viewers.PackState> otherState = viewers.getState(otherId);
    final boolean otherStateAbsent = otherState.isEmpty();
    assertTrue(otherStateAbsent, "nobody has the new pack until their client says so");

    // the old pack's answers no longer count
    viewers.onResourcePackStatus(new PlayerResourcePackStatusEvent(other, PACK, Status.SUCCESSFULLY_LOADED));
    final boolean otherLoadedAfterRetarget = viewers.isLoaded(otherId);
    assertFalse(otherLoadedAfterRetarget);

    viewers.onResourcePackStatus(this.status(OTHER, Status.FAILED_DOWNLOAD));
    final Mcv2Viewers.PackState refusedState = viewers.getState(PLAYER).orElseThrow();
    assertEquals(Mcv2Viewers.PackState.REFUSED, refusedState);

    final List<Player> expectedRefusedOnce = List.of(this.player);
    assertEquals(expectedRefusedOnce, this.refused, "a player who declined is not told again");
    assertThrows(NullPointerException.class, () -> viewers.retarget(null));
  }
}
