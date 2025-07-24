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

import static java.util.Objects.requireNonNull;

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

public final class ServerLoadListener implements Listener {

  private final MCAVSandbox mcav;

  public ServerLoadListener(final MCAVSandbox sandbox) {
    this.mcav = sandbox;
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void onServerLoad(final ServerLoadEvent event) {
    final Server server = Bukkit.getServer();
    final ServicesManager manager = server.getServicesManager();
    final BukkitVoicechatService svcService = requireNonNull(manager.load(BukkitVoicechatService.class));
    final MCAVVoiceChatPlugin plugin = new MCAVVoiceChatPlugin(this.mcav);
    svcService.registerPlugin(plugin);

    final HandlerList handlers = ServerLoadEvent.getHandlerList();
    handlers.unregister(this);
  }
}
