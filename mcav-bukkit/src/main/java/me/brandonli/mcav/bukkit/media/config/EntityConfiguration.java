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
package me.brandonli.mcav.bukkit.media.config;

import com.google.common.base.Preconditions;
import java.util.Collection;
import java.util.UUID;
import org.bukkit.Location;

/**
 * Represents a configuration for an entity related prototypes.
 */
public class EntityConfiguration {

  private final Collection<UUID> viewers;
  private final String character;
  private final int entityWidth;
  private final int entityHeight;
  private final Location position;

  private EntityConfiguration(final Builder<?> builder) {
    this.viewers = builder.viewers;
    this.character = builder.character;
    this.entityWidth = builder.entityWidth;
    this.entityHeight = builder.entityHeight;
    this.position = builder.position;
  }

  /**
   * Gets the viewers of this entity configuration.
   *
   * @return the viewers
   */
  public Collection<UUID> getViewers() {
    return this.viewers;
  }

  /**
   * Gets the character string associated with this entity configuration.
   *
   * @return the character string, which may represent a specific
   */
  public String getCharacter() {
    return this.character;
  }

  /**
   * Gets the width of the entity
   *
   * @return the width of the entity
   */
  public int getEntityWidth() {
    return this.entityWidth;
  }

  /**
   * Retrieves the height of the entity.
   *
   * @return the height of the entity
   */
  public int getEntityHeight() {
    return this.entityHeight;
  }

  /**
   * Retrieves the current position of the entity.
   *
   * @return the current position
   */
  public Location getPosition() {
    return this.position;
  }

  /**
   * Entity configuration builder abstraction.
   */
  public static final class EntityResultBuilder extends Builder<EntityResultBuilder> {

    EntityResultBuilder() {
      // no-op
    }

    @Override
    protected EntityResultBuilder self() {
      return this;
    }
  }

  /**
   * Creates a new entity configuration builder.
   *
   * @return a new entity configuration builder
   */
  public static Builder<?> builder() {
    return new EntityResultBuilder();
  }

  /**
   * Abstract builder for entity configurations.
   *
   * @param <T> the type of the builder
   */
  public abstract static class Builder<T extends Builder<T>> {

    private Collection<UUID> viewers;
    private String character;
    private int entityWidth;
    private int entityHeight;
    private Location position;

    Builder() {
      // no-op
    }

    abstract T self();

    /**
     * Sets the viewers of this entity configuration.
     *
     * @param viewers the viewers to set
     * @return the builder instance for chaining
     */
    public T viewers(final Collection<UUID> viewers) {
      this.viewers = viewers;
      return this.self();
    }

    /**
     * Sets the character string of this entity configuration.
     *
     * @param character the character value to be set
     * @return the instance of the builder for method chaining
     */
    public T character(final String character) {
      this.character = character;
      return this.self();
    }

    /**
     * Sets the width of the entity.
     *
     * @param entityWidth the width of the entity to be set
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
     * Sets the position of the entity.
     *
     * @param position the location to set as the entity's position
     * @return the builder instance for method chaining
     */
    public T position(final Location position) {
      this.position = position;
      return this.self();
    }

    /**
     * Builds the entity configuration.
     *
     * @return a new instance of EntityConfiguration
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
