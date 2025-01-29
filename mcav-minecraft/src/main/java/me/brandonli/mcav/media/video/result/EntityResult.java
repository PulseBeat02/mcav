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
package me.brandonli.mcav.media.video.result;

import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.world.Location;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.google.common.base.Preconditions;
import java.util.*;
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

  private final Collection<UUID> viewers;
  private final String character;
  private final int entityWidth;
  private final int entityHeight;
  private final Vector3d position;
  private final List<Integer> entityIds;

  private EntityResult(final Builder<?> builder) {
    this.viewers = builder.viewers;
    this.character = builder.character;
    this.entityWidth = builder.entityWidth;
    this.entityHeight = builder.entityHeight;
    this.position = builder.position;
    this.entityIds = new ArrayList<>();
  }

  /**
   * Creates a new instance of {@link EntityResult} using the provided builder.
   */
  public static final class EntityResultBuilder extends Builder<EntityResultBuilder> {

    @Override
    protected EntityResultBuilder self() {
      return this;
    }
  }

  /**
   * Creates and returns a new instance of {@link Builder} for constructing {@link EntityResult} objects.
   *
   * @return a new instance of {@link Builder} for configuring and building {@link EntityResult}
   */
  public static Builder<?> builder() {
    return new EntityResultBuilder();
  }

  public abstract static class Builder<T extends Builder<T>> {

    private Collection<UUID> viewers;
    private String character;
    private int entityWidth;
    private int entityHeight;
    private Vector3d position;

    protected abstract T self();

    /**
     * Sets the collection of viewers represented by their UUIDs.
     *
     * @param viewers a collection of UUIDs representing the viewers
     * @return the builder instance with the updated viewers
     */
    public T viewers(final Collection<UUID> viewers) {
      this.viewers = viewers;
      return this.self();
    }

    /**
     * Sets the character associated with the entity and returns the builder instance.
     *
     * @param character the character string to be associated with the entity
     * @return the builder instance for chaining further configurations
     */
    public T character(final String character) {
      this.character = character;
      return this.self();
    }

    /**
     * Sets the width of the entity.
     *
     * @param entityWidth the width of the entity in units; must be a positive integer
     * @return the builder instance for method chaining
     */
    public T entityWidth(final int entityWidth) {
      this.entityWidth = entityWidth;
      return this.self();
    }

    /**
     * Sets the height of the entity.
     *
     * @param entityHeight the height of the entity to be set
     * @return the builder instance for method chaining
     */
    public T entityHeight(final int entityHeight) {
      this.entityHeight = entityHeight;
      return this.self();
    }

    /**
     * Sets the position in 3D space using the specified x, y, and z coordinates.
     *
     * @param x the x-coordinate of the position
     * @param y the y-coordinate of the position
     * @param z the z-coordinate of the position
     * @return the builder instance for method chaining
     */
    public T position(final double x, final double y, final double z) {
      this.position = new Vector3d(x, y, z);
      return this.self();
    }

    /**
     * Builds and returns a new instance of {@link FunctionalVideoFilter} based on the properties
     * configured in this builder. This method validates that all required fields are properly set
     * and ensures logical constraints, such as positive dimensions for the entity, are met.
     *
     * @return a new instance of {@link FunctionalVideoFilter} with the configured properties
     * @throws NullPointerException     if any required field (viewers, character, or position) is null
     * @throws IllegalArgumentException if entity dimensions (width or height) are not positive
     */
    public FunctionalVideoFilter build() {
      Preconditions.checkNotNull(this.viewers);
      Preconditions.checkNotNull(this.character);
      Preconditions.checkNotNull(this.position);
      Preconditions.checkArgument(this.entityWidth > 0, "Entity width must be positive");
      Preconditions.checkArgument(this.entityHeight > 0, "Entity height must be positive");
      return new EntityResult(this);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void applyFilter(final StaticImage data, final VideoMetadata metadata) {
    data.resize(this.entityWidth, this.entityHeight);
    final int[] resizedData = data.getAllPixels();
    for (int i = 0; i < this.entityHeight; i++) {
      final Integer id = this.entityIds.get(i);
      final Component prefix = ChatUtils.createLine(resizedData, this.character, this.entityWidth, i);
      final EntityData invisible = new EntityData(0, EntityDataTypes.BYTE, (byte) 0x20);
      final EntityData name = new EntityData(5, EntityDataTypes.ADV_COMPONENT, prefix);
      final EntityData show = new EntityData(6, EntityDataTypes.BOOLEAN, true);
      final List<EntityData> dataList = List.of(invisible, name, show);
      final WrapperPlayServerEntityMetadata update = new WrapperPlayServerEntityMetadata(id, dataList);
      PacketUtils.sendPackets(this.viewers, update);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void start() {
    final int random = SPLITTABLE_RANDOM.nextInt();
    final Vector3d clone = this.position.add(0, 0, 0);
    for (int i = 0; i < this.entityHeight; i++) {
      final int id = random + i;
      final UUID randomUUID = UUID.randomUUID();
      final EntityType type = EntityTypes.ARMOR_STAND;
      final Location position = new Location(clone.add(0, 0.1, 0), 0, 0);
      final WrapperPlayServerSpawnEntity spawnEntity = new WrapperPlayServerSpawnEntity(id, randomUUID, type, position, 0f, 0, null);
      PacketUtils.sendPackets(this.viewers, spawnEntity);
      this.entityIds.add(id);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void release() {
    final int[] kill = this.entityIds.stream().mapToInt(Integer::intValue).toArray();
    final WrapperPlayServerDestroyEntities destroyEntities = new WrapperPlayServerDestroyEntities(kill);
    PacketUtils.sendPackets(this.viewers, destroyEntities);
  }
}
