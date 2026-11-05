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
package me.brandonli.mcav.plugin.utils;

import org.bukkit.NamespacedKey;

/**
 * The keys of the persistent data the plugin stores on the item frames of map screens.
 *
 * <p>This class is stateless and thread-safe.
 *
 * @since 1.0.0
 */
public final class Keys {

  /**
   * The namespace of every key of the plugin.
   *
   * @since 1.0.0
   */
  public static final String NAMESPACE = "mcav";

  /**
   * The key that marks every item frame of a map screen.
   *
   * @since 1.0.0
   */
  public static final NamespacedKey MAP_KEY = new NamespacedKey(NAMESPACE, "map");

  /**
   * The key that marks the top left item frame of a map screen.
   *
   * @since 1.0.0
   */
  public static final NamespacedKey FIRST_MAP_KEY = new NamespacedKey(NAMESPACE, "first_map");

  /**
   * The key that marks the bottom right item frame of a map screen.
   *
   * @since 1.0.0
   */
  public static final NamespacedKey LAST_MAP_KEY = new NamespacedKey(NAMESPACE, "second_map");

  /**
   * The key that identifies the screen a map frame belongs to, so neighboring screens remain independent.
   *
   * @since 1.0.0
   */
  public static final NamespacedKey SCREEN_KEY = new NamespacedKey(NAMESPACE, "screen");

  private Keys() {
    final String message = "Utility class cannot be instantiated!";
    throw new UnsupportedOperationException(message);
  }
}
