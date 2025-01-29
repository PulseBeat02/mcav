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
package me.brandonli.mcav.module;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.HashMap;
import java.util.Map;

public class ModuleLoader {

  private final Map<Class<?>, MCAVModule> modules;

  ModuleLoader() {
    this.modules = new HashMap<>();
  }

  public <T extends MCAVModule> T getModule(final Class<T> moduleClass) {
    final MCAVModule module = this.modules.get(moduleClass);
    if (module == null) {
      final String name = moduleClass.getSimpleName();
      final String msg = "Module %s does not exist or is not loaded!".formatted(name);
      throw new ModuleException(msg);
    }
    return moduleClass.cast(module);
  }

  public void loadPlugins(final Class<?>... plugins) {
    final MethodType type = MethodType.methodType(void.class);
    final MethodHandles.Lookup originalLookup = MethodHandles.lookup();
    for (final Class<?> plugin : plugins) {
      if (!MCAVModule.class.isAssignableFrom(plugin)) {
        final String name = plugin.getSimpleName();
        final String msg = "Plugin %s is not a valid MCAV plugin!".formatted(name);
        throw new ModuleException(msg);
      }
      final MCAVModule module = this.tryPluginLoad(plugin, originalLookup, type);
      final Class<?> moduleClass = module.getClass();
      this.modules.put(moduleClass, module);
      module.start();
    }
  }

  private MCAVModule tryPluginLoad(final Class<?> plugin, final MethodHandles.Lookup originalLookup, final MethodType type) {
    try {
      final MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(plugin, originalLookup);
      final MethodHandle constructor = lookup.findConstructor(plugin, type);
      return (MCAVModule) constructor.invoke();
    } catch (final Throwable e) {
      throw new ModuleException(e.getMessage(), e);
    }
  }

  public void shutdownPlugins() {
    this.modules.values().forEach(MCAVModule::stop);
  }
}
