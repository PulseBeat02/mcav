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
package me.brandonli.mcav.bukkit.media.image;

import static java.util.Objects.requireNonNull;

import java.util.Collection;
import java.util.UUID;
import me.brandonli.mcav.bukkit.BukkitModule;
import me.brandonli.mcav.bukkit.media.config.EntityConfiguration;
import me.brandonli.mcav.bukkit.utils.ChatUtils;
import me.brandonli.mcav.media.image.StaticImage;
import net.minecraft.network.chat.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.craftbukkit.entity.CraftTextDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;

/**
 * Represents an entity-based image display implementation. Uses a {@link TextDisplay} entity to display images in the world.
 */
public class EntityImage implements DisplayableImage {

  private final EntityConfiguration entityConfiguration;

  private TextDisplay entity;

  EntityImage(final EntityConfiguration configuration) {
    this.entityConfiguration = configuration;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  @SuppressWarnings("deprecation")
  public void displayImage(final StaticImage data) {
    this.release();

    final Location pos = this.entityConfiguration.getPosition();
    final Location clone = pos.clone();
    final Collection<UUID> viewers = this.entityConfiguration.getViewers();
    final World world = requireNonNull(clone.getWorld());
    final Plugin plugin = BukkitModule.getPlugin();
    this.entity = world.spawn(clone, TextDisplay.class, display -> {
      display.setInvulnerable(true);
      display.setCustomNameVisible(false);
      display.setSeeThrough(false);
      display.setAlignment(TextDisplay.TextAlignment.CENTER);
      display.setBillboard(Display.Billboard.CENTER);
      display.setVisibleByDefault(false);
      display.setBackgroundColor(Color.BLACK);
      display.setDefaultBackground(true);
      display.setShadowed(false);
      display.setText("");
      display.setCustomName("");
      display.setLineWidth(Integer.MAX_VALUE);
    });
    for (final UUID viewer : viewers) {
      final Player player = Bukkit.getPlayer(viewer);
      if (player == null) {
        continue;
      }
      player.showEntity(plugin, this.entity);
    }

    final String character = this.entityConfiguration.getCharacter();
    final int entityWidth = this.entityConfiguration.getEntityWidth();
    final int entityHeight = this.entityConfiguration.getEntityHeight();
    data.resize(entityWidth, entityHeight);

    final int[] resizedData = data.getAllPixels();
    final Component prefix = ChatUtils.createChatComponent(resizedData, character, entityWidth, entityHeight);
    final CraftTextDisplay craftEntity = (CraftTextDisplay) this.entity;
    final net.minecraft.world.entity.Display.TextDisplay frame = craftEntity.getHandle();
    frame.setText(prefix);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void release() {
    if (this.entity == null) {
      return;
    }
    final BukkitScheduler scheduler = Bukkit.getScheduler();
    final Plugin plugin = BukkitModule.getPlugin();
    scheduler.runTask(plugin, this::release0);
  }

  private void release0() {
    if (this.entity == null) {
      return;
    }
    this.entity.remove();
  }
}
