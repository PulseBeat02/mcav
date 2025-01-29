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
package me.brandonli.mcav.media.config;

import com.github.retrooper.packetevents.util.Vector3d;
import com.google.common.base.Preconditions;
import me.brandonli.mcav.media.result.EntityResult;
import me.brandonli.mcav.media.result.FunctionalVideoFilter;

import java.util.Collection;
import java.util.UUID;

/**
 * Represents the configuration of an entity, including its dimensions, position, associated
 * viewers, and character definition.
 *
 * <p>This class is immutable and can only be instantiated using the {@link Builder} or its subclasses.
 * It ensures all mandatory attributes are properly set and validated.
 */
public class EntityConfiguration {

  private final Collection<UUID> viewers;
  private final String character;
  private final int entityWidth;
  private final int entityHeight;
  private final Vector3d position;

  private EntityConfiguration(final Builder<?> builder) {
    this.viewers = builder.viewers;
    this.character = builder.character;
    this.entityWidth = builder.entityWidth;
    this.entityHeight = builder.entityHeight;
    this.position = builder.position;
  }

  /**
   * Retrieves the collection of viewers associated with this configuration.
   *
   * @return a collection of UUIDs representing the viewers
   */
  public Collection<UUID> getViewers() {
    return this.viewers;
  }

  /**
   * Retrieves the character associated with this configuration.
   *
   * @return the character as a String
   */
  public String getCharacter() {
    return this.character;
  }

  /**
   * Retrieves the width of the entity.
   *
   * @return an integer representing the width of the entity
   */
  public int getEntityWidth() {
    return this.entityWidth;
  }

  /**
   * Retrieves the height of the entity.
   *
   * @return the
   */
  public int getEntityHeight() {
    return this.entityHeight;
  }

  /**
   * Retrieves the current position of the entity.
   *
   * @return the position of the entity as a {@link Vector3d} object
   */
  public Vector3d getPosition() {
    return this.position;
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
   * Creates and returns a new {@code Builder} instance for constructing {@link EntityConfiguration}.
   *
   * @return a new {@link Builder} instance for setting up and building an {@link EntityConfiguration} object
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
    public EntityConfiguration build() {
      Preconditions.checkNotNull(this.viewers);
      Preconditions.checkNotNull(this.character);
      Preconditions.checkNotNull(this.position);
      Preconditions.checkArgument(this.entityWidth > 0, "Entity width must be positive");
      Preconditions.checkArgument(this.entityHeight > 0, "Entity height must be positive");
      return new EntityConfiguration(this);
    }
  }
}
