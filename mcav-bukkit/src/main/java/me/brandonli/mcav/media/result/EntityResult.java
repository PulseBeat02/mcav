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

import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.world.Location;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import java.util.*;
import me.brandonli.mcav.media.config.EntityConfiguration;
import me.brandonli.mcav.media.image.StaticImage;
import me.brandonli.mcav.media.player.metadata.VideoMetadata;
import me.brandonli.mcav.utils.ChatUtils;
import me.brandonli.mcav.utils.PacketUtils;
import net.kyori.adventure.text.Component;

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

  private static final SplittableRandom SPLITTABLE_RANDOM = new SplittableRandom();

  private final EntityConfiguration entityConfiguration;
  private final List<Integer> entityIds;

  /**
   * Constructs an instance of {@code EntityResult} with the specified configuration.
   *
   * @param configuration the {@code EntityConfiguration} object containing the configuration
   *                      details for the entity, including its dimensions, position, associated viewers,
   *                      and character. Must not be null.
   */
  public EntityResult(final EntityConfiguration configuration) {
    this.entityConfiguration = configuration;
    this.entityIds = new ArrayList<>();
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
      final Integer id = this.entityIds.get(i);
      final Component prefix = ChatUtils.createLine(resizedData, character, entityWidth, i);
      final EntityData invisible = new EntityData(0, EntityDataTypes.BYTE, (byte) 0x20);
      final EntityData name = new EntityData(5, EntityDataTypes.ADV_COMPONENT, prefix);
      final EntityData show = new EntityData(6, EntityDataTypes.BOOLEAN, true);
      final List<EntityData> dataList = List.of(invisible, name, show);
      final WrapperPlayServerEntityMetadata update = new WrapperPlayServerEntityMetadata(id, dataList);
      PacketUtils.sendPackets(viewers, update);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void start() {
    final int random = SPLITTABLE_RANDOM.nextInt();
    final int entityHeight = this.entityConfiguration.getEntityHeight();
    final Vector3d pos = this.entityConfiguration.getPosition();
    final Vector3d clone = pos.add(0, 0, 0);
    final Collection<UUID> viewers = this.entityConfiguration.getViewers();
    for (int i = 0; i < entityHeight; i++) {
      final int id = random + i;
      final UUID randomUUID = UUID.randomUUID();
      final EntityType type = EntityTypes.ARMOR_STAND;
      final Location position = new Location(clone.add(0, 0.1, 0), 0, 0);
      final WrapperPlayServerSpawnEntity spawnEntity = new WrapperPlayServerSpawnEntity(id, randomUUID, type, position, 0f, 0, null);
      PacketUtils.sendPackets(viewers, spawnEntity);
      this.entityIds.add(id);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void release() {
    final int[] kill = this.entityIds.stream().mapToInt(Integer::intValue).toArray();
    final Collection<UUID> viewers = this.entityConfiguration.getViewers();
    final WrapperPlayServerDestroyEntities destroyEntities = new WrapperPlayServerDestroyEntities(kill);
    PacketUtils.sendPackets(viewers, destroyEntities);
  }
}
