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
package me.brandonli.mcav.bukkit.resourcepack.provider.netty.injector;

import java.util.Collection;
import java.util.HashSet;

public final class InjectorHandler {

  private final Collection<Injector> injectors;

  public InjectorHandler() {
    this.injectors = new HashSet<>();
  }

  public void addInjector(final Injector injector) {
    this.injectors.add(injector);
  }

  public void removeInjector(final Injector injector) {
    this.injectors.remove(injector);
  }

  public void clearInjectors() {
    this.injectors.clear();
  }

  public Collection<Injector> getInjectors() {
    return this.injectors;
  }
}
