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
package me.brandonli.mcav.media.image;

import static java.util.Objects.requireNonNull;

import java.util.Collection;
import java.util.SplittableRandom;
import java.util.UUID;
import me.brandonli.mcav.MCAVBukkit;
import me.brandonli.mcav.media.config.EntityConfiguration;
import me.brandonli.mcav.utils.ChatUtils;
import net.minecraft.network.chat.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.Plugin;

/**
 * The EntityImage class represents an implementation of the DisplayableImage interface that
 * is responsible for rendering and managing images as collections of virtual entities in a 3D space.
 * The images are displayed using in-game entities such as Armor Stands.
 * <p>
 * This class uses an instance of {@link EntityConfiguration} to define the display parameters,
 * such as the position, dimensions, and character representation of the entities. It supports
 * resizing of the input image and ensures proper cleanup of resources when the entities are no longer needed.
 * <p>
 * Instances of EntityImage are immutable with respect to their configuration but are stateful
 * in managing the entities used for rendering the image.
 */
public class EntityImage implements DisplayableImage {

  private static final SplittableRandom SPLITTABLE_RANDOM = new SplittableRandom();

  private final EntityConfiguration entityConfiguration;
  private final Entity[] entities;

  EntityImage(final EntityConfiguration configuration) {
    this.entityConfiguration = configuration;
    this.entities = new Entity[configuration.getEntityHeight()];
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void displayImage(final StaticImage data) {
    this.release();
    final int entityHeight = this.entityConfiguration.getEntityHeight();
    final Location pos = this.entityConfiguration.getPosition();
    final Location clone = pos.clone();
    final Collection<UUID> viewers = this.entityConfiguration.getViewers();
    final World world = requireNonNull(clone.getWorld());
    final Plugin plugin = MCAVBukkit.getPlugin();
    for (int i = 0; i < entityHeight; i++) {
      final Location position = clone.add(0, 0.2, 0);
      @SuppressWarnings("deprecation")
      final TextDisplay entity = world.spawn(position, TextDisplay.class, display -> {
        display.setInvulnerable(true);
        display.setCustomNameVisible(true);
        display.setSeeThrough(false);
        display.setAlignment(TextDisplay.TextAlignment.CENTER);
        display.setBillboard(Display.Billboard.CENTER);
        display.setVisibleByDefault(false);
        display.setBackgroundColor(Color.BLACK);
        display.setDefaultBackground(true);
        display.setShadowed(false);
        display.setText("");
      });
      for (final UUID viewer : viewers) {
        final Player player = Bukkit.getPlayer(viewer);
        if (player == null) {
          continue;
        }
        player.showEntity(plugin, entity);
      }
      this.entities[i] = entity;
    }
    final String character = this.entityConfiguration.getCharacter();
    final int entityWidth = this.entityConfiguration.getEntityWidth();
    data.resize(entityWidth, entityHeight);
    final int[] resizedData = data.getAllPixels();
    for (int i = 0; i < entityHeight; i++) {
      final Entity entity = this.entities[i];
      final CraftEntity glow = (CraftEntity) entity;
      final net.minecraft.world.entity.Entity nmsEntity = glow.getHandle();
      final Component prefix = ChatUtils.createLine(resizedData, character, entityWidth, entityHeight - i - 1);
      nmsEntity.setCustomName(prefix);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void release() {
    for (final Entity entity : this.entities) {
      entity.remove();
    }
  }
}
