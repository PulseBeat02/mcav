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
package me.brandonli.mcav.sandbox.listener;

import com.google.common.base.Preconditions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import me.brandonli.mcav.sandbox.MCAVSandbox;
import me.brandonli.mcav.sandbox.utils.DiskImages;
import me.brandonli.mcav.utils.IOUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
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
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Boots a virtual machine when a player puts a renamed music disc into a jukebox: the disc's name selects an ISO
 * image from the plugin's {@code iso} folder, and the machine is shown to the player on map 0.
 */
public final class JukeBoxListener implements Listener {

  private static final PlainTextComponentSerializer PLAIN_TEXT = PlainTextComponentSerializer.plainText();
  private static final Pattern BRACKETS = Pattern.compile("[\\[\\]]");
  private static final Pattern ILLEGAL_FILE_NAME_CHARACTERS = Pattern.compile("[\\\\/:*?\"<>|]");
  private static final String MUSIC_DISC_PREFIX = "MUSIC_DISC_";

  private final MCAVSandbox sandbox;
  private final Path isoFolder;

  /**
   * Constructs the listener and creates the {@code iso} folder.
   *
   * @param sandbox the plugin
   */
  public JukeBoxListener(final MCAVSandbox sandbox) {
    Preconditions.checkNotNull(sandbox, "Sandbox must not be null");
    this.sandbox = sandbox;
    final Path dataFolder = sandbox.getDataPath();
    this.isoFolder = DiskImages.folderOf(dataFolder);
    IOUtils.createDirectoryIfNotExists(this.isoFolder);
  }

  /**
   * Registers the listener.
   */
  public void start() {
    final Server server = Bukkit.getServer();
    final PluginManager pluginManager = server.getPluginManager();
    pluginManager.registerEvents(this, this.sandbox);
  }

  /**
   * Unregisters the listener.
   */
  public void shutdown() {
    HandlerList.unregisterAll(this);
  }

  /**
   * Boots the virtual machine of the disc a player puts into a jukebox, if the ISO folder has an image named like
   * the disc. The disc is kept.
   *
   * @param event the interaction
   */
  @EventHandler
  public void onJukeboxInteract(final PlayerInteractEvent event) {
    Preconditions.checkNotNull(event, "Event must not be null");
    final ItemStack item = event.getItem();
    if (item == null || !isDiscOnJukebox(event, item)) {
      return;
    }
    final Path image = this.findDiskImage(item);
    if (image == null) {
      return;
    }
    event.setCancelled(true);
    final Player player = event.getPlayer();
    final String playerName = player.getName();
    final Path absolute = image.toAbsolutePath();
    final String command = "mcav vm create %s 640x640 30 5x5 0 FILTER_LITE X86_64 -cdrom \"%s\" -m 2048M".formatted(playerName, absolute);
    player.performCommand(command);
  }

  private static boolean isDiscOnJukebox(final PlayerInteractEvent event, final ItemStack item) {
    final Action action = event.getAction();
    final Block block = event.getClickedBlock();
    if (action != Action.RIGHT_CLICK_BLOCK || block == null) {
      return false;
    }
    final Material blockType = block.getType();
    final Material itemType = item.getType();
    final String itemName = itemType.name();
    return blockType == Material.JUKEBOX && itemName.startsWith(MUSIC_DISC_PREFIX);
  }

  /**
   * Finds the disk image in the ISO folder that is named like the disc, such as {@code alpine.iso} for a disc
   * renamed to "alpine.iso" in an anvil.
   *
   * @return the disk image, or {@code null} if there is none or the name would leave the ISO folder
   */
  private @Nullable Path findDiskImage(final ItemStack item) {
    final Component name = item.displayName();
    final String displayName = PLAIN_TEXT.serialize(name);
    final Matcher bracketMatcher = BRACKETS.matcher(displayName);
    final String withoutBrackets = bracketMatcher.replaceAll("");
    final Matcher illegalMatcher = ILLEGAL_FILE_NAME_CHARACTERS.matcher(withoutBrackets);
    final String fileName = illegalMatcher.replaceAll("_");
    final Path image = this.isoFolder.resolve(fileName);
    final Path normalized = image.normalize();
    final boolean inside = normalized.startsWith(this.isoFolder);
    final boolean exists = Files.isRegularFile(normalized);
    if (!inside || !exists) {
      return null;
    }
    return normalized;
  }
}
