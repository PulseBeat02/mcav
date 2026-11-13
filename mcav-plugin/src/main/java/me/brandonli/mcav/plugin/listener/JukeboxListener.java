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
package me.brandonli.mcav.plugin.listener;

import com.google.common.base.Preconditions;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import me.brandonli.mcav.plugin.MCAVSandbox;
import me.brandonli.mcav.plugin.utils.DiskImages;
import me.brandonli.mcav.utils.IoUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
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
 *
 * <p>This class is not thread-safe: use it on the server main thread.
 *
 * @since 1.0.0
 */
public final class JukeboxListener implements Listener {

  private static final PlainTextComponentSerializer PLAIN_TEXT = PlainTextComponentSerializer.plainText();

  private static final String MUSIC_DISC_PREFIX = "MUSIC_DISC_";

  private static final Pattern BRACKETS = Pattern.compile("[\\[\\]]");
  private static final Pattern ILLEGAL_FILE_NAME_CHARACTERS = Pattern.compile("[\\\\/:*?\"<>|]");
  private static final String NAME_REPLACEMENT = "_";

  private static final String VM_RESOLUTION = "640x640";

  private static final int TARGET_FRAMES_PER_SECOND = 30;

  private static final String BLOCK_DIMENSIONS = "5x5";

  private static final int MAP_ID = 0;

  private static final String DITHERING_ALGORITHM = "FILTER_LITE";

  private static final String ARCHITECTURE = "X86_64";

  private static final String AUDIO_TYPE = "NONE";

  private static final String MEMORY = "2048M";

  private final MCAVSandbox plugin;
  private final Path isoFolder;

  /**
   * Creates the listener and the {@code iso} folder.
   *
   * @param plugin the plugin
   * @throws NullPointerException                           if {@code plugin} is null
   * @throws me.brandonli.mcav.utils.FileOperationException if the {@code iso} folder cannot be created
   * @since 1.0.0
   */
  public JukeboxListener(final MCAVSandbox plugin) {
    Preconditions.checkNotNull(plugin, "Plugin must not be null!");

    this.plugin = plugin;
    final Path dataFolder = plugin.getDataPath();
    this.isoFolder = DiskImages.folderOf(dataFolder);
    IoUtils.createDirectoryIfNotExists(this.isoFolder);
  }

  /**
   * Registers the listener.
   *
   * @since 1.0.0
   */
  public void start() {
    final Server server = Bukkit.getServer();
    final PluginManager pluginManager = server.getPluginManager();
    pluginManager.registerEvents(this, this.plugin);
  }

  /**
   * Unregisters the listener.
   *
   * @since 1.0.0
   */
  public void shutdown() {
    HandlerList.unregisterAll(this);
  }

  /**
   * Boots the virtual machine of the disc a player puts into a jukebox, if the ISO folder has an image named like
   * the disc. The disc is kept. It runs at the highest priority, once the plugins that protect regions, which decide at
   * lower priorities, may have denied the player the jukebox; a denied use boots nothing.
   *
   * @param event the interaction
   * @throws NullPointerException if {@code event} is null
   * @since 1.0.0
   */
  @EventHandler(priority = EventPriority.HIGHEST)
  public void onJukeboxInteract(final PlayerInteractEvent event) {
    Preconditions.checkNotNull(event, "Event must not be null!");

    if (event.useInteractedBlock() == Event.Result.DENY) {
      return;
    }

    final ItemStack item = event.getItem();
    if (item == null || !this.isDiscOnJukebox(event, item)) {
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
    final String command = "mcav vm create %s %s %s %s %s %s %s %s -cdrom \"%s\" -m %s".formatted(
      playerName,
      VM_RESOLUTION,
      TARGET_FRAMES_PER_SECOND,
      BLOCK_DIMENSIONS,
      MAP_ID,
      DITHERING_ALGORITHM,
      ARCHITECTURE,
      AUDIO_TYPE,
      absolute,
      MEMORY
    );
    player.performCommand(command);
  }

  private boolean isDiscOnJukebox(final PlayerInteractEvent event, final ItemStack item) {
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

  // a disc renamed in an anvil, such as "alpine.iso", names its disk image
  private @Nullable Path findDiskImage(final ItemStack item) {
    final Component name = item.displayName();
    final String displayName = PLAIN_TEXT.serialize(name);
    final Matcher bracketMatcher = BRACKETS.matcher(displayName);
    final String withoutBrackets = bracketMatcher.replaceAll("");
    final Matcher illegalMatcher = ILLEGAL_FILE_NAME_CHARACTERS.matcher(withoutBrackets);
    final String fileName = illegalMatcher.replaceAll(NAME_REPLACEMENT);
    final @Nullable Path image = this.resolveImage(fileName);
    if (image == null) {
      return null;
    }

    final Path normalized = image.normalize();
    final boolean inside = normalized.startsWith(this.isoFolder);
    final boolean exists = Files.isRegularFile(normalized);
    if (!inside || !exists) {
      return null;
    }

    return normalized;
  }

  // a name the file system refuses, such as one with a NUL character, has no image
  private @Nullable Path resolveImage(final String fileName) {
    try {
      return this.isoFolder.resolve(fileName);
    } catch (final InvalidPathException exception) {
      return null;
    }
  }
}
