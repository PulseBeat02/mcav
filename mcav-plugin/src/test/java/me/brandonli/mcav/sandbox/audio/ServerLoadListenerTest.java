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
package me.brandonli.mcav.sandbox.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import java.util.Arrays;
import java.util.List;
import me.brandonli.mcav.sandbox.MCAVSandbox;
import me.brandonli.mcav.sandbox.testing.TestServer;
import org.bukkit.Server;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.plugin.ServicesManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Tests {@link ServerLoadListener}.
 */
final class ServerLoadListenerTest {

  private ServerLoadListener listener;
  private ServicesManager services;
  private RegisteredListener registration;

  @BeforeEach
  void registerListener() {
    final Server server = TestServer.reset();
    this.services = mock(ServicesManager.class);
    when(server.getServicesManager()).thenReturn(this.services);
    final MCAVSandbox sandbox = mock(MCAVSandbox.class);
    this.listener = new ServerLoadListener(sandbox);
    final EventExecutor executor = mock(EventExecutor.class);
    this.registration = new RegisteredListener(this.listener, executor, EventPriority.HIGHEST, sandbox, false);
    final HandlerList handlers = ServerLoadEvent.getHandlerList();
    handlers.register(this.registration);
  }

  @AfterEach
  void unregisterListener() {
    final HandlerList handlers = ServerLoadEvent.getHandlerList();
    handlers.unregister(this.listener);
  }

  private boolean isRegistered() {
    final HandlerList handlers = ServerLoadEvent.getHandlerList();
    final RegisteredListener[] listeners = handlers.getRegisteredListeners();
    final List<RegisteredListener> registered = Arrays.asList(listeners);
    return registered.contains(this.registration);
  }

  @Test
  void registersTheVoiceChatPluginOnceTheServerHasLoaded() {
    final BukkitVoicechatService voiceChat = mock(BukkitVoicechatService.class);
    when(this.services.load(BukkitVoicechatService.class)).thenReturn(voiceChat);
    final ServerLoadEvent event = mock(ServerLoadEvent.class);
    final boolean registeredBefore = this.isRegistered();
    assertTrue(registeredBefore);
    this.listener.onServerLoad(event);
    final ArgumentCaptor<VoicechatPlugin> captor = ArgumentCaptor.forClass(VoicechatPlugin.class);
    verify(voiceChat).registerPlugin(captor.capture());
    final VoicechatPlugin plugin = captor.getValue();
    final String id = plugin.getPluginId();
    assertEquals("mcav", id);
    final boolean registeredAfter = this.isRegistered();
    assertFalse(registeredAfter);
  }

  @Test
  void failsWithAClearMessageWhenSimpleVoiceChatOffersNoService() {
    final ServerLoadEvent event = mock(ServerLoadEvent.class);
    final IllegalStateException exception = assertThrows(IllegalStateException.class, () -> this.listener.onServerLoad(event));
    final String message = exception.getMessage();
    assertEquals(
      "Simple Voice Chat audio is enabled, but Simple Voice Chat offers no voice chat service, so voice chat audio is not available",
      message
    );
  }

  @Test
  void refusesANullPlugin() {
    assertThrows(NullPointerException.class, () -> new ServerLoadListener(null));
  }

  @Test
  void refusesANullEvent() {
    assertThrows(NullPointerException.class, () -> this.listener.onServerLoad(null));
  }
}
