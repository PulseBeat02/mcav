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
package me.brandonli.mcav.bukkit.resourcepack.provider.netty;

import java.util.Collection;
import java.util.HashSet;

final class InjectorHandler {

  private final Collection<Injector> injectors;

  InjectorHandler() {
    this.injectors = new HashSet<>();
  }

  void addInjector(final Injector injector) {
    this.injectors.add(injector);
  }

  void removeInjector(final Injector injector) {
    this.injectors.remove(injector);
  }

  void clearInjectors() {
    this.injectors.clear();
  }

  Collection<Injector> getInjectors() {
    return this.injectors;
  }
}
