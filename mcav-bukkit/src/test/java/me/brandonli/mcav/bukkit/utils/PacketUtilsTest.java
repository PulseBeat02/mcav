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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import me.brandonli.mcav.bukkit.testing.FakeServer;
import me.brandonli.mcav.bukkit.testing.UtilityClassAssertions;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.event.Event;
import org.bukkit.event.EventException;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Tests {@link PacketUtils}.
 */
final class PacketUtilsTest {

  private static final UUID FIRST = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID SECOND = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final UUID LATE = UUID.fromString("00000000-0000-0000-0000-000000000003");

  private FakeServer server;

  @BeforeEach
  void startServer() {
    this.server = FakeServer.start();
  }

  @AfterEach
  void stopServer() {
    this.server.close();
  }

  private Listener captureListener(final Class<? extends Event> eventType, final EventPriority priority, final int registrations) {
    final PluginManager pluginManager = this.server.getPluginManager();
    final Plugin plugin = this.server.getPlugin();
    final ArgumentCaptor<Listener> captor = ArgumentCaptor.forClass(Listener.class);
    verify(pluginManager, times(registrations)).registerEvent(
      eq(eventType),
      captor.capture(),
      eq(priority),
      any(EventExecutor.class),
      eq(plugin)
    );
    return captor.getValue();
  }

  private Listener captureListener(final int registrations) {
    return this.captureListener(PlayerJoinEvent.class, EventPriority.LOWEST, registrations);
  }

  private EventExecutor captureExecutor(final Class<? extends Event> eventType, final EventPriority priority) {
    final PluginManager pluginManager = this.server.getPluginManager();
    final Plugin plugin = this.server.getPlugin();
    final ArgumentCaptor<EventExecutor> captor = ArgumentCaptor.forClass(EventExecutor.class);
    verify(pluginManager).registerEvent(eq(eventType), any(Listener.class), eq(priority), captor.capture(), eq(plugin));
    return captor.getValue();
  }

  @Test
  void cachesTheConnectionsOfPlayersThatAreAlreadyOnline() throws ReflectiveOperationException {
    this.server.addPlayer(FIRST);
    this.server.addPlayer(SECOND);
    this.server.injectModule();
    final boolean firstConnected = PacketUtils.isConnected(FIRST);
    final boolean secondConnected = PacketUtils.isConnected(SECOND);
    final boolean lateConnected = PacketUtils.isConnected(LATE);
    assertTrue(firstConnected);
    assertTrue(secondConnected);
    assertFalse(lateConnected, "players that are not online cannot receive packets");
  }

  @Test
  void keepsTheCacheInSyncWithJoinsAndQuits() throws ReflectiveOperationException, EventException {
    this.server.injectModule();
    final Listener listener = this.captureListener(1);
    final EventExecutor joinExecutor = this.captureExecutor(PlayerJoinEvent.class, EventPriority.LOWEST);
    final EventExecutor quitExecutor = this.captureExecutor(PlayerQuitEvent.class, EventPriority.MONITOR);
    final CraftPlayer late = this.server.addPlayer(LATE);
    final boolean beforeJoin = PacketUtils.isConnected(LATE);
    final PlayerJoinEvent joinEvent = mock(PlayerJoinEvent.class);
    when(joinEvent.getPlayer()).thenReturn(late);
    final PlayerQuitEvent quitEvent = mock(PlayerQuitEvent.class);
    when(quitEvent.getPlayer()).thenReturn(late);

    joinExecutor.execute(listener, joinEvent);
    final boolean afterJoin = PacketUtils.isConnected(LATE);
    quitExecutor.execute(listener, quitEvent);
    final boolean afterQuit = PacketUtils.isConnected(LATE);

    assertFalse(beforeJoin);
    assertTrue(afterJoin);
    assertFalse(afterQuit);
  }

  @Test
  void ignoresEventsOfOtherTypes() throws ReflectiveOperationException, EventException {
    final CraftPlayer first = this.server.addPlayer(FIRST);
    this.server.injectModule();
    final Listener listener = this.captureListener(1);
    final EventExecutor joinExecutor = this.captureExecutor(PlayerJoinEvent.class, EventPriority.LOWEST);
    final EventExecutor quitExecutor = this.captureExecutor(PlayerQuitEvent.class, EventPriority.MONITOR);
    final CraftPlayer late = this.server.addPlayer(LATE);
    final PlayerJoinEvent joinEvent = mock(PlayerJoinEvent.class);
    when(joinEvent.getPlayer()).thenReturn(late);
    final PlayerQuitEvent quitEvent = mock(PlayerQuitEvent.class);
    when(quitEvent.getPlayer()).thenReturn(first);

    joinExecutor.execute(listener, quitEvent);
    quitExecutor.execute(listener, joinEvent);
    final boolean lateConnected = PacketUtils.isConnected(LATE);
    final boolean firstConnected = PacketUtils.isConnected(FIRST);

    assertFalse(lateConnected, "a quit is not handled as a join");
    assertTrue(firstConnected, "a join is not handled as a quit");
  }

  @Test
  void handlesJoinsFirstAndQuitsLastWithOneListener() throws ReflectiveOperationException {
    this.server.injectModule();
    final Listener joinListener = this.captureListener(PlayerJoinEvent.class, EventPriority.LOWEST, 1);
    final Listener quitListener = this.captureListener(PlayerQuitEvent.class, EventPriority.MONITOR, 1);
    assertSame(joinListener, quitListener, "one listener owns both handlers, so both are unregistered together");
  }

  @Test
  void sendsEveryPacketInOrderToConnectedViewersOnly() throws ReflectiveOperationException {
    this.server.addPlayer(FIRST);
    this.server.injectModule();
    final Component firstText = Component.literal("first");
    final Component secondText = Component.literal("second");
    final ClientboundSystemChatPacket first = new ClientboundSystemChatPacket(firstText, false);
    final ClientboundSystemChatPacket second = new ClientboundSystemChatPacket(secondText, true);
    final List<UUID> viewers = List.of(SECOND, FIRST);
    PacketUtils.sendPackets(viewers, first, second);
    final List<Packet<?>> packets = this.server.getSentPackets(FIRST);
    final List<Packet<?>> expected = List.of(first, second);
    assertEquals(expected, packets);
  }

  @Test
  void initializingAgainReplacesThePreviousListener() throws ReflectiveOperationException {
    this.server.injectModule();
    final Listener firstListener = this.captureListener(1);
    try (final MockedStatic<HandlerList> handlerLists = Mockito.mockStatic(HandlerList.class)) {
      PacketUtils.init();
      handlerLists.verify(() -> HandlerList.unregisterAll(firstListener));
    }
    final Listener secondListener = this.captureListener(2);
    assertNotSame(firstListener, secondListener);
  }

  @Test
  void shuttingDownUnregistersTheListenerAndForgetsEveryConnection() throws ReflectiveOperationException {
    this.server.addPlayer(FIRST);
    this.server.injectModule();
    final Listener listener = this.captureListener(1);
    try (final MockedStatic<HandlerList> handlerLists = Mockito.mockStatic(HandlerList.class)) {
      PacketUtils.shutdown();
      PacketUtils.shutdown();
      handlerLists.verify(() -> HandlerList.unregisterAll(listener), times(1));
      handlerLists.verify(() -> HandlerList.unregisterAll(any(Listener.class)), times(1));
    }
    final boolean connected = PacketUtils.isConnected(FIRST);
    assertFalse(connected);
  }

  @Test
  void needsAnInjectedPlugin() {
    assertThrows(IllegalStateException.class, PacketUtils::init);
  }

  @Test
  void rejectsMissingArguments() {
    final List<UUID> viewers = List.of(FIRST);
    assertThrows(NullPointerException.class, () -> PacketUtils.isConnected(null));
    assertThrows(NullPointerException.class, () -> PacketUtils.sendPackets(null));
    assertThrows(NullPointerException.class, () -> PacketUtils.sendPackets(viewers, (Packet<?>[]) null));
  }

  @Test
  void cannotBeInstantiated() {
    UtilityClassAssertions.assertNotInstantiable(PacketUtils.class);
  }
}
