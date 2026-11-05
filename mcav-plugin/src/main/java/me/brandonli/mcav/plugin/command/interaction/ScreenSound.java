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
package me.brandonli.mcav.plugin.command.interaction;

import com.google.common.base.MoreObjects;
import java.util.Arrays;
import java.util.Objects;
import me.brandonli.mcav.plugin.utils.AudioArgument;
import org.bukkit.entity.Player;
import org.checkerframework.checker.nullness.qual.Nullable;

final class ScreenSound {

  private final AudioArgument type;
  private final Player[] viewers;

  ScreenSound(final AudioArgument type, final Player[] viewers) {
    this.type = type;
    this.viewers = viewers.clone();
  }

  AudioArgument getType() {
    return this.type;
  }

  Player[] getViewers() {
    return this.viewers.clone();
  }

  @Override
  public boolean equals(final @Nullable Object other) {
    if (this == other) {
      return true;
    }

    if (!(other instanceof final ScreenSound sound)) {
      return false;
    }

    return this.type == sound.type && Arrays.equals(this.viewers, sound.viewers);
  }

  @Override
  public int hashCode() {
    final int viewersHash = Arrays.hashCode(this.viewers);
    return Objects.hash(this.type, viewersHash);
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this).add("type", this.type).add("viewers", this.viewers).toString();
  }
}
