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
package me.brandonli.mcav.media.result;

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import me.brandonli.mcav.MCAVBukkit;
import me.brandonli.mcav.media.config.EntityConfiguration;
import me.brandonli.mcav.media.image.StaticImage;
import me.brandonli.mcav.media.player.metadata.VideoMetadata;
import me.brandonli.mcav.utils.ChatUtils;
import me.brandonli.mcav.utils.PacketUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Represents the result of an entity-based operation that applies a functional video filter.
 * The filter dynamically creates and manages virtual entities, modifies their metadata, and
 * applies visual effects based on the video data provided.
 * <p>
 * This class is built using a builder pattern to allow flexible initialization of its
 * properties, such as entity dimensions, position, and viewers. It ensures that all resources
 * associated with the entities are appropriately created and released during the lifecycle
 * of the filter.
 * <p>
 * Implements the {@link FunctionalVideoFilter} interface, providing additional control over
 * the initialization (`start`), application (`applyFilter`), and cleanup (`release`) phases
 * of the filter's lifecycle.
 */
public class EntityResult implements FunctionalVideoFilter {

  private final EntityConfiguration entityConfiguration;
  private final Entity[] entities;

  /**
   * Constructs an instance of {@code EntityResult} with the specified configuration.
   *
   * @param configuration the {@code EntityConfiguration} object containing the configuration
   *                      details for the entity, including its dimensions, position, associated viewers,
   *                      and character. Must not be null.
   */
  public EntityResult(final EntityConfiguration configuration) {
    this.entityConfiguration = configuration;
    this.entities = new Entity[configuration.getEntityHeight()];
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void applyFilter(final StaticImage data, final VideoMetadata metadata) {
    final String character = this.entityConfiguration.getCharacter();
    final int entityWidth = this.entityConfiguration.getEntityWidth();
    final int entityHeight = this.entityConfiguration.getEntityHeight();
    final Collection<UUID> viewers = this.entityConfiguration.getViewers();
    data.resize(entityWidth, entityHeight);
    final int[] resizedData = data.getAllPixels();
    for (int i = 0; i < entityHeight; i++) {
      final Entity entity = this.entities[i];
      final CraftEntity glow = (CraftEntity) entity;
      final net.minecraft.world.entity.Entity nmsEntity = glow.getHandle();
      final SynchedEntityData entityData = nmsEntity.getEntityData();

      // send via raw components because serializing components to legacy for Bukkit support is slow
      List<SynchedEntityData.DataValue<?>> packed = entityData.getNonDefaultValues();
      if (packed == null) {
        packed = new ArrayList<>();
      } else {
        packed.removeIf(dataValue -> dataValue.id() == 5);
      }

      final Component prefix = ChatUtils.createLine(resizedData, character, entityWidth, i);
      final SynchedEntityData.DataValue<?> value = new SynchedEntityData.DataValue<>(5, EntityDataSerializers.COMPONENT, prefix);
      packed.add(value);

      final int id = entity.getEntityId();
      final ClientboundSetEntityDataPacket packet = new ClientboundSetEntityDataPacket(id, packed);
      PacketUtils.sendPackets(viewers, packet);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void start() {
    final int entityHeight = this.entityConfiguration.getEntityHeight();
    final Location pos = this.entityConfiguration.getPosition();
    final Location clone = pos.add(0, 0, 0);
    final Collection<UUID> viewers = this.entityConfiguration.getViewers();
    final World world = requireNonNull(clone.getWorld());
    final Plugin plugin = MCAVBukkit.getPlugin();
    for (int i = 0; i < entityHeight; i++) {
      final Location position = clone.add(0, 0.1, 0);
      final ArmorStand entity = world.spawn(position, ArmorStand.class, stand -> {
        stand.setInvisible(true);
        stand.setInvulnerable(true);
        stand.setCustomNameVisible(true);
        stand.setVisibleByDefault(false);
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
