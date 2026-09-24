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
package me.brandonli.mcav.vm;

import com.google.common.base.Preconditions;
import me.brandonli.mcav.utils.IOUtils;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * How a virtual machine is streamed: the local VNC port QEMU listens on, the size frames are scaled to, and
 * the target frame rate of the VNC render loop. This setting does not change the guest display rate.
 */
public final class VMSettings {

  private final int port;
  private final int width;
  private final int height;
  private final int targetFrameRate;

  VMSettings(final int port, final int width, final int height, final int targetFrameRate) {
    this.port = port;
    this.width = width;
    this.height = height;
    this.targetFrameRate = targetFrameRate;
  }

  /**
   * Creates settings on the next free VNC port.
   *
   * @param width           the width the frames are scaled to, in pixels
   * @param height          the height the frames are scaled to, in pixels
   * @param targetFrameRate the frame rate in frames per second
   * @return the settings
   */
  public static VMSettings of(final int width, final int height, final int targetFrameRate) {
    final int port = IOUtils.getNextFreeVNCPort();
    return of(port, width, height, targetFrameRate);
  }

  /**
   * Creates settings on a specific VNC port.
   *
   * @param port            the local port QEMU listens on, at least 5900
   * @param width           the width the frames are scaled to, in pixels
   * @param height          the height the frames are scaled to, in pixels
   * @param targetFrameRate the frame rate in frames per second
   * @return the settings
   */
  public static VMSettings of(final int port, final int width, final int height, final int targetFrameRate) {
    Preconditions.checkArgument(
      port >= VMProcess.FIRST_VNC_PORT && port <= 65535,
      "VNC port must be between 5900 and 65535 but was %s",
      port
    );
    Preconditions.checkArgument(width > 0 && height > 0, "Frame size must be positive but was %sx%s", width, height);
    Preconditions.checkArgument(targetFrameRate > 0, "Frame rate must be positive but was %s", targetFrameRate);
    return new VMSettings(port, width, height, targetFrameRate);
  }

  /**
   * Gets the local VNC port.
   *
   * @return the port
   */
  public int getPort() {
    return this.port;
  }

  /**
   * Gets the width the frames are scaled to.
   *
   * @return the width in pixels
   */
  public int getWidth() {
    return this.width;
  }

  /**
   * Gets the height the frames are scaled to.
   *
   * @return the height in pixels
   */
  public int getHeight() {
    return this.height;
  }

  /**
   * Gets the target frame rate of the VNC render loop. This setting does not change the guest display rate.
   *
   * @return the frame rate in frames per second
   */
  public int getTargetFps() {
    return this.targetFrameRate;
  }

  @Override
  public boolean equals(final @Nullable Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof final VMSettings settings)) {
      return false;
    }
    return (
      this.port == settings.port &&
      this.width == settings.width &&
      this.height == settings.height &&
      this.targetFrameRate == settings.targetFrameRate
    );
  }

  @Override
  public int hashCode() {
    int result = this.port;
    result = result * 31 + this.width;
    result = result * 31 + this.height;
    result = result * 31 + this.targetFrameRate;
    return result;
  }

  @Override
  public String toString() {
    return "VMSettings[port=" + this.port + ", " + this.width + "x" + this.height + "@" + this.targetFrameRate + "]";
  }
}
