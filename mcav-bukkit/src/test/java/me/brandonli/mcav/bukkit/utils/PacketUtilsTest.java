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

import static java.util.Objects.requireNonNull;
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

import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelFuture;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.epoll.EpollChannelOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import me.brandonli.mcav.bukkit.media.map.MapPacketFactory;
import me.brandonli.mcav.bukkit.media.map.MapTilePatch;
import me.brandonli.mcav.bukkit.testing.FakeServer;
import me.brandonli.mcav.bukkit.testing.UtilityClassAssertions;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import org.bukkit.Bukkit;
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
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

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
  void shouldDistinguishReconnectsWhileKeepingAnUnchangedConnectionsIdentity() throws ReflectiveOperationException, EventException {
    final CraftPlayer first = this.server.addPlayer(FIRST);
    this.server.addPlayer(SECOND);
    this.server.injectModule();
    final Object original = PacketUtils.findConnectionIdentity(FIRST).orElseThrow();
    final Object second = PacketUtils.findConnectionIdentity(SECOND).orElseThrow();
    assertNotSame(original, second);

    final Optional<Object> late = PacketUtils.findConnectionIdentity(LATE);
    final boolean lateConnectionAbsent = late.isEmpty();
    assertTrue(lateConnectionAbsent);

    final Listener listener = this.captureListener(1);
    final EventExecutor join = this.captureExecutor(PlayerJoinEvent.class, EventPriority.LOWEST);
    final PlayerJoinEvent joined = mock(PlayerJoinEvent.class);
    when(joined.getPlayer()).thenReturn(first);
    join.execute(listener, joined);
    final Object rejoined = PacketUtils.findConnectionIdentity(FIRST).orElseThrow();
    assertSame(original, rejoined, "a repeated event cannot invalidate a live baseline");

    PacketUtils.initialize();
    final Object refreshed = PacketUtils.findConnectionIdentity(FIRST).orElseThrow();
    assertSame(original, refreshed, "refreshing the cache keeps a live connection's identity");

    this.server.removePlayer(FIRST);
    final CraftPlayer replacement = this.server.addPlayer(FIRST);
    when(joined.getPlayer()).thenReturn(replacement);
    join.execute(listener, joined);
    final Object reconnected = PacketUtils.findConnectionIdentity(FIRST).orElseThrow();
    assertNotSame(original, reconnected, "a new connection cannot inherit the previous client's map state");

    PacketUtils.shutdown();
    final Optional<Object> afterShutdown = PacketUtils.findConnectionIdentity(FIRST);
    final boolean connectionAbsentAfterShutdown = afterShutdown.isEmpty();
    assertTrue(connectionAbsentAfterShutdown);

    final NullPointerException exception = assertThrows(NullPointerException.class, () -> PacketUtils.findConnectionIdentity(null));
    final @Nullable String message = exception.getMessage();
    assertEquals("Player must not be null!", message);
  }

  @Test
  void shouldCacheTheConnectionsOfPlayersThatAreAlreadyOnline() throws ReflectiveOperationException {
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
  void shouldKeepTheCacheInSyncWithJoinsAndQuits() throws ReflectiveOperationException, EventException {
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
    final boolean connectedAfterJoin = PacketUtils.isConnected(LATE);
    quitExecutor.execute(listener, quitEvent);
    final boolean connectedAfterQuit = PacketUtils.isConnected(LATE);

    assertFalse(beforeJoin);
    assertTrue(connectedAfterJoin);
    assertFalse(connectedAfterQuit);
  }

  @Test
  void shouldIgnoreEventsOfOtherTypes() throws ReflectiveOperationException, EventException {
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
  void shouldHandleJoinsFirstAndQuitsLastWithOneListener() throws ReflectiveOperationException {
    this.server.injectModule();
    final Listener joinListener = this.captureListener(PlayerJoinEvent.class, EventPriority.LOWEST, 1);
    final Listener quitListener = this.captureListener(PlayerQuitEvent.class, EventPriority.MONITOR, 1);
    assertSame(joinListener, quitListener, "one listener owns both handlers, so both are unregistered together");
  }

  @Test
  void shouldSendEveryPacketInOrderToConnectedViewersOnly() throws ReflectiveOperationException {
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

  private Connection useConnection() {
    final Connection connection = FakeServer.mockConnection();
    final CraftPlayer player = (CraftPlayer) requireNonNull(Bukkit.getPlayer(FIRST));
    FakeServer.connect(player.getHandle().connection, connection);
    return connection;
  }

  @Test
  void shouldCapTheUnsentBytesOfAConnectionThatTakesTheOption() throws ReflectiveOperationException {
    this.server.addPlayer(FIRST);
    this.server.injectModule();
    final boolean offlineLimited = PacketUtils.limitUnsent(SECOND, 1024);
    assertFalse(offlineLimited);

    final boolean unconnectedLimited = PacketUtils.limitUnsent(FIRST, 1024);
    assertFalse(unconnectedLimited);

    final Connection connection = this.useConnection();
    final boolean channellessLimited = PacketUtils.limitUnsent(FIRST, 1024);
    assertFalse(channellessLimited);

    connection.channel = new EmbeddedChannel();
    final boolean embeddedLimited = PacketUtils.limitUnsent(FIRST, 1024);
    assertFalse(embeddedLimited);

    final Channel epoll = mock(Channel.class);
    final ChannelConfig channelConfiguration = mock(ChannelConfig.class);
    when(epoll.config()).thenReturn(channelConfiguration);
    when(channelConfiguration.setOption(EpollChannelOption.TCP_NOTSENT_LOWAT, 1024L)).thenReturn(true);
    connection.channel = epoll;
    final boolean epollLimited = PacketUtils.limitUnsent(FIRST, 1024);
    assertTrue(epollLimited);
    verify(channelConfiguration).setOption(EpollChannelOption.TCP_NOTSENT_LOWAT, 1024L);
    assertThrows(NullPointerException.class, () -> PacketUtils.limitUnsent(null, 1));
    assertThrows(IllegalArgumentException.class, () -> PacketUtils.limitUnsent(FIRST, 0));
  }

  @Test
  void shouldWriteAPacketWithAListenerToTheConnectionAtOnce() throws ReflectiveOperationException {
    this.server.addPlayer(FIRST);
    this.server.injectModule();
    final Connection connection = this.useConnection();
    final EmbeddedChannel channel = new EmbeddedChannel();
    connection.channel = channel;
    final MapTilePatch page = MapTilePatch.of(100, 0, 0, 128, 1, new byte[128]);
    final ClientboundBundlePacket bundle = MapPacketFactory.createBundles(List.of(page)).getFirst().getPacket();
    final List<ChannelFuture> writes = new ArrayList<>();
    final boolean sent = PacketUtils.sendPacket(FIRST, bundle, writes::add);
    assertTrue(sent);
    // Paper holds a map packet that a plugin sends from another thread until the server's next tick
    final @Nullable Object written = channel.readOutbound();
    assertSame(bundle, written, "the packet is written to the connection and flushed at once");

    final int writeCount = writes.size();
    assertEquals(1, writeCount);

    final ChannelFuture firstWrite = writes.getFirst();
    final boolean firstWriteSucceeded = firstWrite.isSuccess();
    assertTrue(firstWriteSucceeded, "the listener runs once the write is done");

    final ChannelFuture closing = channel.close();
    final boolean closed = closing.isSuccess();
    assertTrue(closed);

    final boolean sentToClosed = PacketUtils.sendPacket(FIRST, bundle, writes::add);
    assertTrue(sentToClosed);

    final int writeCountAfterClose = writes.size();
    assertEquals(2, writeCountAfterClose);

    final ChannelFuture lastWrite = writes.getLast();
    final boolean lastWriteSucceeded = lastWrite.isSuccess();
    assertFalse(lastWriteSucceeded, "a write that fails runs the listener too");

    connection.channel = null;
    final boolean sentWithoutChannel = PacketUtils.sendPacket(FIRST, bundle, writes::add);
    assertFalse(sentWithoutChannel, "a connection that is not open yet takes nothing");

    final boolean sentOffline = PacketUtils.sendPacket(SECOND, bundle, writes::add);
    assertFalse(sentOffline, "a player who is not online takes nothing");

    final int writeCountAfterUnsent = writes.size();
    assertEquals(2, writeCountAfterUnsent, "the listener of a packet that was not sent never runs");
  }

  @Test
  void shouldReplaceThePreviousListenerWhenInitializingAgain() throws ReflectiveOperationException {
    this.server.injectModule();
    final Listener firstListener = this.captureListener(1);
    try (final MockedStatic<HandlerList> handlerLists = Mockito.mockStatic(HandlerList.class)) {
      PacketUtils.initialize();
      handlerLists.verify(() -> HandlerList.unregisterAll(firstListener));
    }

    final Listener secondListener = this.captureListener(2);
    assertNotSame(firstListener, secondListener);
  }

  @Test
  void shouldUnregisterTheListenerAndForgetEveryConnectionWhenShuttingDown() throws ReflectiveOperationException {
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
  void shouldNeedAnInjectedPlugin() {
    assertThrows(IllegalStateException.class, PacketUtils::initialize);
  }

  @Test
  void shouldRejectMissingArguments() {
    final List<UUID> viewers = List.of(FIRST);
    final Component text = Component.literal("text");
    final ClientboundSystemChatPacket packet = new ClientboundSystemChatPacket(text, false);
    final List<ChannelFuture> writes = new ArrayList<>();
    assertThrows(NullPointerException.class, () -> PacketUtils.isConnected(null));
    assertThrows(NullPointerException.class, () -> PacketUtils.sendPackets(null));
    assertThrows(NullPointerException.class, () -> PacketUtils.sendPackets(viewers, (Packet<?>[]) null));
    assertThrows(NullPointerException.class, () -> PacketUtils.sendPacket(null, packet, writes::add));
    assertThrows(NullPointerException.class, () -> PacketUtils.sendPacket(FIRST, null, writes::add));
    assertThrows(NullPointerException.class, () -> PacketUtils.sendPacket(FIRST, packet, null));
  }

  @Test
  void shouldNotBeInstantiable() {
    UtilityClassAssertions.assertNotInstantiable(PacketUtils.class);
  }
}
