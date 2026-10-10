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
import me.brandonli.mcav.bukkit.media.result.Characters;
import org.bukkit.Location;
import org.bukkit.World;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * Describes an image drawn as colored text inside a text display entity, for use with the entity image and entity
 * result.
 *
 * <p>Every pixel is drawn as the configured character in the color of the pixel, and every row of pixels becomes
 * one line of text. The entity always faces the viewer horizontally. Visibility is checked every server tick,
 * including viewers added after spawning and players who reconnect; removed online viewers are hidden.
 *
 * <p>The viewers collection is not copied, so a concurrent collection can be passed.
 *
 * <p>The position is retained by reference. Finish setting it before creating the display and do not
 * mutate it while rendering. {@link #getPosition()} returns that same mutable location.
 */
public class EntityConfiguration {

  private final Collection<UUID> viewers;
  private final String character;
  private final int entityWidth;
  private final int entityHeight;
  private final Location position;

  private EntityConfiguration(final Builder<?> builder, final Collection<UUID> viewers, final String character, final Location position) {
    this.viewers = viewers;
    this.character = character;
    this.entityWidth = builder.entityWidth;
    this.entityHeight = builder.entityHeight;
    this.position = position;
  }

  /**
   * Gets the players who see the entity.
   *
   * @return the original mutable-or-immutable collection supplied to the builder, without a copy; use a
   *         concurrent collection if another thread changes membership during rendering
   */
  public Collection<UUID> getViewers() {
    return this.viewers;
  }

  /**
   * Gets the text drawn for every pixel, usually a single character such as {@code █}.
   *
   * @return the pixel text
   * @see Characters
   */
  public String getCharacter() {
    return this.character;
  }

  /**
   * Gets the width of the image in characters.
   *
   * @return the width in characters
   */
  public int getEntityWidth() {
    return this.entityWidth;
  }

  /**
   * Gets the height of the image in lines.
   *
   * @return the height in lines
   */
  public int getEntityHeight() {
    return this.entityHeight;
  }

  /**
   * Gets the position the entity is spawned at.
   *
   * @return the original location supplied to the builder, without a defensive copy
   */
  public Location getPosition() {
    return this.position;
  }

  /**
   * The builder returned by {@link #builder()}.
   */
  public static final class EntityResultBuilder extends Builder<EntityResultBuilder> {

    EntityResultBuilder() {}

    /**
     * Returns this builder with its concrete type.
     *
     * @return this builder
     */
    @Override
    protected EntityResultBuilder self() {
      return this;
    }
  }

  /**
   * Creates a new builder for an entity configuration.
   *
   * @return a new builder
   */
  public static Builder<?> builder() {
    return new EntityResultBuilder();
  }

  /**
   * Builds entity configurations. Every value is required.
   *
   * <p>Builders are mutable and not thread-safe. Setters retain reference arguments; numeric ranges and
   * required fields are checked by {@link #build()}, which may be called again to create another configuration.
   *
   * @param <T> the type of the builder
   */
  public abstract static class Builder<T extends Builder<T>> {

    private @MonotonicNonNull Collection<UUID> viewers;
    private @MonotonicNonNull String character;
    private int entityWidth;
    private int entityHeight;
    private @MonotonicNonNull Location position;

    Builder() {}

    /**
     * Returns this builder with its concrete type, so the setters can be chained.
     *
     * @return this builder
     */
    abstract T self();

    /**
     * Sets the players who see the entity. The collection is not copied, see {@link EntityConfiguration}.
     *
     * @param viewers the UUIDs of the viewers
     * @return this builder
     * @throws NullPointerException if the viewers are null
     */
    public T viewers(final Collection<UUID> viewers) {
      Preconditions.checkNotNull(viewers, "Viewers must not be null");
      this.viewers = viewers;
      return this.self();
    }

    /**
     * Sets the text drawn for every pixel.
     *
     * <p>The text must be nonempty when {@link #build()} is called. Multiple characters are allowed, but
     * change the visual width of a pixel; no font or character-width validation is performed.
     *
     * @param character the pixel text, usually a single character such as {@code █}
     * @return this builder
     * @throws NullPointerException if the character is null
     */
    public T character(final String character) {
      Preconditions.checkNotNull(character, "Character must not be null");
      this.character = character;
      return this.self();
    }

    /**
     * Sets the width of the image in characters.
     *
     * @param entityWidth the width in characters, which must be positive
     * @return this builder
     */
    public T entityWidth(final int entityWidth) {
      this.entityWidth = entityWidth;
      return this.self();
    }

    /**
     * Sets the height of the image in lines.
     *
     * @param entityHeight the height in lines, which must be positive
     * @return this builder
     */
    public T entityHeight(final int entityHeight) {
      this.entityHeight = entityHeight;
      return this.self();
    }

    /**
     * Sets the position the entity is spawned at.
     *
     * <p>The location is stored without copying. Its world is checked when {@link #build()} is called.
     *
     * @param position the position of the entity, which must have a world
     * @return this builder
     * @throws NullPointerException if the position is null
     */
    public T position(final Location position) {
      Preconditions.checkNotNull(position, "Position must not be null");
      this.position = position;
      return this.self();
    }

    /**
     * Builds the entity configuration.
     *
     * @return the entity configuration
     * @throws IllegalArgumentException if a size is not positive, the character is empty, or the position has no
     *                                  world
     * @throws NullPointerException     if the viewers, the character, or the position were not set
     */
    public EntityConfiguration build() {
      final Collection<UUID> configuredViewers = Preconditions.checkNotNull(this.viewers, "Viewers must be set");
      final String configuredCharacter = Preconditions.checkNotNull(this.character, "Character must be set");
      final Location configuredPosition = Preconditions.checkNotNull(this.position, "Position must be set");

      final World world = configuredPosition.getWorld();
      Preconditions.checkArgument(world != null, "Position must have a world");
      Preconditions.checkArgument(!configuredCharacter.isEmpty(), "Character must not be empty");
      Preconditions.checkArgument(this.entityWidth > 0, "Entity width must be positive");
      Preconditions.checkArgument(this.entityHeight > 0, "Entity height must be positive");

      return new EntityConfiguration(this, configuredViewers, configuredCharacter, configuredPosition);
    }
  }
}
