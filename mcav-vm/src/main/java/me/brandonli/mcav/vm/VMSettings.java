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
package me.brandonli.mcav.vm;

import me.brandonli.mcav.utils.IOUtils;

public final class VMSettings {

  private final int port;
  private final int width;
  private final int height;
  private final int targetFps;

  VMSettings(final int port, final int width, final int height, final int targetFps) {
    this.port = port;
    this.width = width;
    this.height = height;
    this.targetFps = targetFps;
  }

  public int getPort() {
    return this.port;
  }

  public int getWidth() {
    return this.width;
  }

  public int getHeight() {
    return this.height;
  }

  public int getTargetFps() {
    return this.targetFps;
  }

  public static VMSettings of(final int width, final int height, final int targetFps) {
    final int free = IOUtils.getNextFreeVNCPort();
    return of(free, width, height, targetFps);
  }

  public static VMSettings of(final int port, final int width, final int height, final int targetFps) {
    return new VMSettings(port, width, height, targetFps);
  }
}
