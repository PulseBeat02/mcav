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
package me.brandonli.mcav.sandbox.command.interaction;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.*;
import me.brandonli.mcav.sandbox.MCAVSandbox;
import me.brandonli.mcav.sandbox.command.AnnotationCommandFeature;
import me.brandonli.mcav.sandbox.utils.InteractUtils;
import me.brandonli.mcav.sandbox.utils.Keys;
import me.brandonli.mcav.utils.interaction.KeyUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.PluginManager;
import org.bukkit.util.RayTraceResult;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.incendo.cloud.annotations.AnnotationParser;

public abstract class AbstractInteractiveCommand<T> implements AnnotationCommandFeature, Listener {

  protected static final PlainTextComponentSerializer PLAIN_TEXT_SERIALIZER = PlainTextComponentSerializer.plainText();

  protected @Nullable T player;
  protected Set<Player> activePlayers;

  @Override
  public void registerFeature(final MCAVSandbox plugin, final AnnotationParser<CommandSender> parser) {
    this.activePlayers = Collections.newSetFromMap(new WeakHashMap<>());
    final Server server = plugin.getServer();
    final PluginManager pluginManager = server.getPluginManager();
    pluginManager.registerEvents(this, plugin);
  }

  @Override
  public void shutdown() {
    if (this.player != null) {
      this.releasePlayer();
      this.player = null;
    }
    HandlerList.unregisterAll(this);
  }

  @EventHandler
  public void onBlockBreak(final BlockBreakEvent event) {
    final T player = this.player;
    if (player == null) {
      return;
    }

    final Player mcPlayer = event.getPlayer();
    final RayTraceResult entityRay = mcPlayer.rayTraceBlocks(100, FluidCollisionMode.NEVER);
    if (entityRay == null) {
      return;
    }

    final Block block = entityRay.getHitBlock();
    if (block == null) {
      return;
    }

    final Location blockLocation = block.getLocation();
    final World world = blockLocation.getWorld();
    final Collection<Entity> entities = world.getNearbyEntities(blockLocation, 0.5, 0.5, 0.5);
    final Optional<ItemFrame> entity = entities
      .stream()
      .filter(e -> e instanceof ItemFrame)
      .map(e -> (ItemFrame) e)
      .min(Comparator.comparingDouble(frame -> frame.getLocation().distanceSquared(blockLocation)));
    if (entity.isEmpty()) {
      return;
    }

    final ItemFrame frame = entity.get();
    final PersistentDataContainer data = frame.getPersistentDataContainer();
    if (!data.has(Keys.MAP_KEY, PersistentDataType.BOOLEAN)) {
      return;
    }

    final int[] coordinates = InteractUtils.getBoardCoordinates(mcPlayer, frame);
    if (coordinates == null) {
      return;
    }
    event.setCancelled(true);

    final int x = coordinates[0];
    final int y = coordinates[1];
    this.handleLeftClick(player, x, y);
  }

  @EventHandler
  public void onEntityDamageByEntity(final EntityDamageByEntityEvent event) {
    final T player = this.player;
    if (player == null) {
      return;
    }

    final Entity damager = event.getDamager();
    if (!(damager instanceof final Player mcPlayer)) {
      return;
    }

    final Entity entity = event.getEntity();
    if (!(entity instanceof final ItemFrame frame)) {
      return;
    }

    final PersistentDataContainer data = frame.getPersistentDataContainer();
    if (!data.has(Keys.MAP_KEY, PersistentDataType.BOOLEAN)) {
      return;
    }

    final int[] coordinates = InteractUtils.getBoardCoordinates(mcPlayer);
    if (coordinates == null) {
      return;
    }
    event.setCancelled(true);

    final int x = coordinates[0];
    final int y = coordinates[1];
    this.handleLeftClick(player, x, y);
  }

  @EventHandler
  public void onPlayerInteractEntityEvent(final PlayerInteractEntityEvent event) {
    final T player = this.player;
    if (player == null) {
      return;
    }

    final Entity entity = event.getRightClicked();
    if (!(entity instanceof final ItemFrame frame)) {
      return;
    }

    final PersistentDataContainer data = frame.getPersistentDataContainer();
    if (!data.has(Keys.MAP_KEY, PersistentDataType.BOOLEAN)) {
      return;
    }

    final Player mcPlayer = event.getPlayer();
    final int[] coordinates = InteractUtils.getBoardCoordinates(mcPlayer);
    if (coordinates == null) {
      return;
    }
    event.setCancelled(true);

    final int x = coordinates[0];
    final int y = coordinates[1];
    this.handleRightClick(player, x, y);
  }

  @EventHandler
  public void onChatMessage(final AsyncChatEvent event) {
    final Player mcPlayer = event.getPlayer();
    if (!this.activePlayers.contains(mcPlayer)) {
      return;
    }

    final T player = this.player;
    if (player == null) {
      return;
    }
    event.setCancelled(true);

    final Component message = event.message();
    final String raw = PLAIN_TEXT_SERIALIZER.serialize(message);
    final String converted = KeyUtils.replaceKeysWithKeyCodes(raw);
    this.handleTextInput(player, converted);
  }

  protected void activateInteraction(final Player player, final Component enableMessage, final Component disableMessage) {
    if (this.activePlayers.contains(player)) {
      this.activePlayers.remove(player);
      player.sendMessage(disableMessage);
    } else {
      this.activePlayers.add(player);
      player.sendMessage(enableMessage);
    }
  }

  protected void releaseResource(final CommandSender sender, final Component message) {
    if (this.player != null) {
      this.releasePlayer();
      this.player = null;
    }
    sender.sendMessage(message);
  }

  protected abstract void handleLeftClick(T player, int x, int y);

  protected abstract void handleRightClick(T player, int x, int y);

  protected abstract void handleTextInput(T player, String text);

  protected abstract void releasePlayer();
}
