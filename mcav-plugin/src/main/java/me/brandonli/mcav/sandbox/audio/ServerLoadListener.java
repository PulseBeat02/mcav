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

import com.google.common.base.Preconditions;
import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import me.brandonli.mcav.sandbox.MCAVSandbox;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.plugin.ServicesManager;

/**
 * Registers the plugin with Simple Voice Chat once the server has loaded, because Simple Voice Chat offers its
 * service only then. The listener unregisters itself afterwards.
 */
public final class ServerLoadListener implements Listener {

  private final MCAVSandbox sandbox;

  /**
   * Constructs the listener.
   *
   * @param sandbox the plugin
   * @throws NullPointerException if the plugin is {@code null}
   */
  public ServerLoadListener(final MCAVSandbox sandbox) {
    Preconditions.checkNotNull(sandbox, "Plugin must not be null");
    this.sandbox = sandbox;
  }

  /**
   * Registers the plugin with Simple Voice Chat.
   *
   * @param event the event, fired once the server has loaded its plugins and worlds
   * @throws NullPointerException  if the event is {@code null}
   * @throws IllegalStateException if Simple Voice Chat offers no service, for example because it failed to start
   */
  @EventHandler(priority = EventPriority.HIGHEST)
  public void onServerLoad(final ServerLoadEvent event) {
    Preconditions.checkNotNull(event, "Event must not be null");
    final Server server = Bukkit.getServer();
    final ServicesManager manager = server.getServicesManager();
    final BukkitVoicechatService voiceChatService = manager.load(BukkitVoicechatService.class);
    if (voiceChatService == null) {
      throw new IllegalStateException(
        "Simple Voice Chat audio is enabled, but Simple Voice Chat offers no voice chat service, so voice chat audio is not available"
      );
    }

    final MCAVVoiceChatPlugin plugin = new MCAVVoiceChatPlugin(this.sandbox);
    voiceChatService.registerPlugin(plugin);

    final HandlerList handlers = ServerLoadEvent.getHandlerList();
    handlers.unregister(this);
  }
}
