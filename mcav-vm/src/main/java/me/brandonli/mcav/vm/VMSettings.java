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

/**
 * Stores the VNC settings for a virtual machine (VM) instance.
 */
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

  /**
   * Gets the port number for the VNC server.
   *
   * @return the port number
   */
  public int getPort() {
    return this.port;
  }

  /**
   * Gets the width of the VM display.
   *
   * @return the width in pixels
   */
  public int getWidth() {
    return this.width;
  }

  /**
   * Gets the height of the VM display.
   *
   * @return the height in pixels
   */
  public int getHeight() {
    return this.height;
  }

  /**
   * Gets the target frames per second (FPS) for the VM. Won't be guaranteed, but will be attempted to be achieved.
   *
   * @return the target FPS
   */
  public int getTargetFps() {
    return this.targetFps;
  }

  /**
   * Creates a new VMSettings instance with a free port and specified dimensions and target FPS.
   *
   * @param width      the width of the VM display
   * @param height     the height of the VM display
   * @param targetFps  the target frames per second for the VM
   * @return a new VMSettings instance
   */
  public static VMSettings of(final int width, final int height, final int targetFps) {
    final int free = IOUtils.getNextFreeVNCPort();
    return of(free, width, height, targetFps);
  }

  /**
   * Creates a new VMSettings instance with the specified port, dimensions, and target FPS.
   *
   * @param port       the port number for the VNC server
   * @param width      the width of the VM display
   * @param height     the height of the VM display
   * @param targetFps  the target frames per second for the VM
   * @return a new VMSettings instance
   */
  public static VMSettings of(final int port, final int width, final int height, final int targetFps) {
    return new VMSettings(port, width, height, targetFps);
  }
}
