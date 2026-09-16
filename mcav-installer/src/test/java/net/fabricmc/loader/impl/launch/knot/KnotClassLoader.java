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
package net.fabricmc.loader.impl.launch.knot;

/**
 * Stands in for the class loader of Fabric in tests. Like the real one, it is no {@link java.net.URLClassLoader}
 * but keeps the class path in a private {@code urlLoader} field.
 */
public class KnotClassLoader extends ClassLoader {

  private final Object urlLoader;

  /**
   * Creates the class loader.
   *
   * @param urlLoader the value of the {@code urlLoader} field, normally a {@link java.net.URLClassLoader}
   */
  public KnotClassLoader(final Object urlLoader) {
    super(null);
    this.urlLoader = urlLoader;
  }

  @Override
  public String toString() {
    return "KnotClassLoader[" + this.urlLoader + "]";
  }
}
