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
package me.brandonli.mcav.sandbox.utils;

import me.brandonli.mcav.sandbox.MCAVSandbox;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitScheduler;

public final class TaskUtils {

  private TaskUtils() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  public static Runnable handleAsyncTask(final MCAVSandbox plugin, final Runnable task) {
    final BukkitScheduler scheduler = Bukkit.getScheduler();
    return () -> scheduler.runTask(plugin, task);
  }
}
