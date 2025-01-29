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
import me.brandonli.mcav.utils.ChatUtils;
import me.brandonli.mcav.utils.PacketUtils;
import net.kyori.adventure.text.Component;

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
  private final List<Integer> entityIds;

  EntityImage(final EntityConfiguration configuration) {
    this.entityConfiguration = configuration;
    this.entityIds = new ArrayList<>();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void displayImage(final StaticImage data) {
    this.release();
    final int random = SPLITTABLE_RANDOM.nextInt();
    final int entityHeight = this.entityConfiguration.getEntityHeight();
    final Vector3d pos = this.entityConfiguration.getPosition();
    final Vector3d clone = pos.add(0, 0, 0);
    final String character = this.entityConfiguration.getCharacter();
    final int entityWidth = this.entityConfiguration.getEntityWidth();
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
  public void release() {
    final int[] kill = this.entityIds.stream().mapToInt(Integer::intValue).toArray();
    final Collection<UUID> viewers = this.entityConfiguration.getViewers();
    final WrapperPlayServerDestroyEntities destroyEntities = new WrapperPlayServerDestroyEntities(kill);
    PacketUtils.sendPackets(viewers, destroyEntities);
  }
}
