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
package me.brandonli.mcav.bukkit.media.config;

import com.google.common.base.Preconditions;
import java.util.Collection;
import java.util.UUID;
import org.bukkit.Location;

public class BlockConfiguration {

  private final Collection<UUID> viewers;
  private final int blockWidth;
  private final int blockHeight;
  private final Location position;

  private BlockConfiguration(final BlockConfiguration.Builder<?> builder) {
    this.viewers = builder.viewers;
    this.blockWidth = builder.blockWidth;
    this.blockHeight = builder.blockHeight;
    this.position = builder.position;
  }

  public Collection<UUID> getViewers() {
    return this.viewers;
  }

  public int getBlockWidth() {
    return this.blockWidth;
  }

  public int getBlockHeight() {
    return this.blockHeight;
  }

  public Location getPosition() {
    return this.position;
  }

  public static final class BlockResultBuilder extends BlockConfiguration.Builder<BlockConfiguration.BlockResultBuilder> {

    @Override
    protected BlockConfiguration.BlockResultBuilder self() {
      return this;
    }
  }

  public static BlockConfiguration.Builder<?> builder() {
    return new BlockConfiguration.BlockResultBuilder();
  }

  public abstract static class Builder<T extends BlockConfiguration.Builder<T>> {

    private Collection<UUID> viewers;
    private int blockWidth;
    private int blockHeight;
    private Location position;

    protected abstract T self();

    public T viewers(final Collection<UUID> viewers) {
      this.viewers = viewers;
      return this.self();
    }

    public T position(final Location position) {
      this.position = position;
      return this.self();
    }

    public T blockWidth(final int blockWidth) {
      this.blockWidth = blockWidth;
      return this.self();
    }

    public T blockHeight(final int blockHeight) {
      this.blockHeight = blockHeight;
      return this.self();
    }

    public BlockConfiguration build() {
      Preconditions.checkArgument(this.blockWidth > 0, "Map block width must be positive");
      Preconditions.checkArgument(this.blockHeight > 0, "Map block height must be positive");
      Preconditions.checkNotNull(this.viewers);
      Preconditions.checkNotNull(this.position);
      return new BlockConfiguration(this);
    }
  }
}
