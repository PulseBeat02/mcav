/*
 * This file is part of mcav, a media playback library for Minecraft
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
package me.brandonli.mcav.sandbox.listener;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import me.brandonli.mcav.sandbox.MCAVSandbox;
import me.brandonli.mcav.utils.IOUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Registry;
import org.bukkit.Server;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.PluginManager;
import org.checkerframework.checker.initialization.qual.UnderInitialization;

public final class JukeBoxListener implements Listener {

  private static final PlainTextComponentSerializer PLAIN_TEXT_COMPONENT_SERIALIZER = PlainTextComponentSerializer.plainText();

  private static final Set<Material> MUSIC_DISCS = Registry.MATERIAL.stream()
    .filter(material -> material.name().startsWith("MUSIC_DISC_"))
    .collect(Collectors.toSet());

  private final MCAVSandbox sandbox;
  private final Path isoPath;

  public JukeBoxListener(final MCAVSandbox sandbox) {
    this.isoPath = this.createFolder(sandbox);
    this.sandbox = sandbox;
  }

  public void shutdown() {
    HandlerList.unregisterAll(this);
  }

  public void start() {
    final Server server = Bukkit.getServer();
    final PluginManager pluginManager = server.getPluginManager();
    pluginManager.registerEvents(this, this.sandbox);
  }

  private Path createFolder(@UnderInitialization JukeBoxListener this, final MCAVSandbox sandbox) {
    final Path path = sandbox.getDataPath();
    final Path iso = path.resolve("iso");
    IOUtils.createDirectoryIfNotExists(iso);
    return iso;
  }

  @EventHandler
  public void onJukeboxInteract(final PlayerInteractEvent event) {
    final Action action = event.getAction();
    if (action != Action.RIGHT_CLICK_BLOCK) {
      return;
    }

    final Block clickedBlock = event.getClickedBlock();
    if (clickedBlock == null) {
      return;
    }

    final Material blockType = clickedBlock.getType();
    if (blockType != Material.JUKEBOX) {
      return;
    }

    final ItemStack itemInHand = event.getItem();
    if (itemInHand == null) {
      return;
    }

    final Material itemType = itemInHand.getType();
    if (!MUSIC_DISCS.contains(itemType)) {
      return;
    }

    final Component name = itemInHand.displayName();
    final String displayName = PLAIN_TEXT_COMPONENT_SERIALIZER.serialize(name);
    final String sanitizedName = displayName.replaceAll("[\\\\/:*?\"<>|]", "_");
    final String noBrackets = sanitizedName.replaceAll("[\\[\\]]", "");
    final Path path = this.isoPath.resolve(noBrackets);
    if (Files.notExists(path)) {
      return;
    }
    event.setCancelled(true);

    final Path absolute = path.toAbsolutePath();
    final String raw = absolute.toString();
    final String cmd = "mcav vm create @a 640x640 5x5 0 filter_lite x86_64 -cdrom %s -m 2048M".formatted(raw);
    final Player player = event.getPlayer();
    player.performCommand(cmd);
  }
}
