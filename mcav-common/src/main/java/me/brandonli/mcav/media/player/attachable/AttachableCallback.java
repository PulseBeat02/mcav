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
package me.brandonli.mcav.media.player.attachable;

/**
 * A slot on a player that holds one value, such as a pipeline or a target size, which can be attached, replaced,
 * and detached at any time, including while the player is playing. A detached slot holds a harmless default so
 * players never have to check for null.
 *
 * @param <T> the type of value the slot holds
 */
public interface AttachableCallback<T> {
  /**
   * Puts a value into the slot, replacing the previous one.
   *
   * @param value the non-null value, retained by reference; replacing a value does not release it
   * @throws NullPointerException if {@code value} is null
   */
  void attach(final T value);

  /**
   * Empties the slot, restoring its default.
   */
  void detach();

  /**
   * Checks whether a value was explicitly attached, even if it is the fallback value.
   *
   * @return a snapshot of the attached state, which can change immediately after this call
   */
  boolean isAttached();

  /**
   * Gets the attached value, or the default when nothing is attached.
   *
   * @return the value
   */
  T retrieve();
}
