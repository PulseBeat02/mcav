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
package me.brandonli.mcav.bukkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.UUID;
import me.brandonli.mcav.bukkit.testing.FakeServer;
import me.brandonli.mcav.bukkit.utils.PacketUtils;
import me.brandonli.mcav.bukkit.utils.versioning.UnsupportedServerVersionException;
import org.bukkit.Bukkit;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/**
 * Tests {@link BukkitModule}.
 */
final class BukkitModuleTest {

  private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");

  private FakeServer server;

  @BeforeEach
  void startServer() {
    this.server = FakeServer.start();
    this.server.addPlayer(PLAYER);
  }

  @AfterEach
  void stopServer() {
    this.server.close();
  }

  private void verifyConnectionTrackingRegistered(final int registrations) {
    final PluginManager pluginManager = this.server.getPluginManager();
    final Plugin plugin = this.server.getPlugin();
    verify(pluginManager, times(registrations)).registerEvent(
      eq(PlayerJoinEvent.class),
      any(Listener.class),
      eq(EventPriority.LOWEST),
      any(EventExecutor.class),
      eq(plugin)
    );
  }

  @Test
  void injectingThePluginRegistersListenersAndCachesOnlinePlayers() {
    final BukkitModule module = new BukkitModule();
    final Plugin plugin = this.server.getPlugin();
    module.inject(plugin);
    final Plugin injected = BukkitModule.getPlugin();
    final boolean connected = PacketUtils.isConnected(PLAYER);
    assertSame(plugin, injected);
    assertTrue(connected);
    this.verifyConnectionTrackingRegistered(1);
  }

  @Test
  void rejectsMissingPlugins() {
    final BukkitModule module = new BukkitModule();
    assertThrows(NullPointerException.class, () -> module.inject(null));
  }

  @Test
  void refusesUnsupportedServersBeforeInjectingAnything() {
    final MockedStatic<Bukkit> bukkit = this.server.getBukkit();
    bukkit.when(Bukkit::getMinecraftVersion).thenReturn("1.20.1");
    final BukkitModule module = new BukkitModule();
    final Plugin plugin = this.server.getPlugin();
    final PluginManager pluginManager = this.server.getPluginManager();
    assertThrows(UnsupportedServerVersionException.class, () -> module.inject(plugin));
    assertThrows(IllegalStateException.class, BukkitModule::getPlugin);
    verifyNoInteractions(pluginManager);
  }

  @Test
  void failsToProvideThePluginBeforeItWasInjected() {
    final IllegalStateException exception = assertThrows(IllegalStateException.class, BukkitModule::getPlugin);
    final String message = exception.getMessage();
    assertEquals("No plugin has been injected, call BukkitModule#inject(Plugin) first", message);
  }

  @Test
  void startingAgainAfterStoppingRegistersTheListenersAgain() {
    final BukkitModule module = new BukkitModule();
    final Plugin plugin = this.server.getPlugin();
    module.inject(plugin);
    module.stop();
    final boolean connectedWhileStopped = PacketUtils.isConnected(PLAYER);
    module.start();
    final boolean connectedAfterRestart = PacketUtils.isConnected(PLAYER);
    assertFalse(connectedWhileStopped);
    assertTrue(connectedAfterRestart, "a restarted module can send packets again");
    this.verifyConnectionTrackingRegistered(2);
  }

  @Test
  void startingWhileThePluginIsDisabledDoesNothing() {
    final BukkitModule module = new BukkitModule();
    final Plugin plugin = this.server.getPlugin();
    module.inject(plugin);
    module.stop();
    when(plugin.isEnabled()).thenReturn(false);
    module.start();
    final boolean connected = PacketUtils.isConnected(PLAYER);
    assertFalse(connected);
    this.verifyConnectionTrackingRegistered(1);
  }

  @Test
  void startingBeforeThePluginIsInjectedDoesNothing() {
    final BukkitModule module = new BukkitModule();
    final PluginManager pluginManager = this.server.getPluginManager();
    module.start();
    final String name = module.getModuleName();
    assertEquals("bukkit", name);
    verifyNoInteractions(pluginManager);
    assertThrows(IllegalStateException.class, BukkitModule::getPlugin);
  }

  @Test
  void stoppingClearsTheConnectionsButKeepsThePlugin() {
    final BukkitModule module = new BukkitModule();
    final Plugin plugin = this.server.getPlugin();
    module.inject(plugin);
    module.stop();
    final boolean connected = PacketUtils.isConnected(PLAYER);
    final Plugin injected = BukkitModule.getPlugin();
    assertFalse(connected);
    assertSame(plugin, injected);
  }

  @Test
  void stoppingWithoutAPluginDoesNothing() {
    final BukkitModule module = new BukkitModule();
    final Plugin plugin = this.server.getPlugin();
    module.inject(plugin);
    FakeServer.clearInjectedPlugin();
    module.stop();
    final boolean connected = PacketUtils.isConnected(PLAYER);
    assertTrue(connected, "nothing is shut down while no plugin is injected");
  }
}
