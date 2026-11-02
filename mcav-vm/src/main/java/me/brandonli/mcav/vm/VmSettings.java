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

import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import java.util.Objects;
import me.brandonli.mcav.MCAVException;
import me.brandonli.mcav.utils.IoUtils;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * How a virtual machine is streamed: the local VNC port QEMU listens on, the size frames are scaled to, and
 * the requested VNC update rate. This does not change the guest display rate. Creating settings does not reserve a
 * port or start a machine.
 *
 * <p>This class is immutable and thread-safe.
 *
 * @since 1.0.0
 */
public final class VmSettings {

  private static final int MAXIMUM_PORT = 65535;

  private final int port;
  private final int width;
  private final int height;
  private final int targetFrameRate;

  VmSettings(final int port, final int width, final int height, final int targetFrameRate) {
    this.port = port;
    this.width = width;
    this.height = height;
    this.targetFrameRate = targetFrameRate;
  }

  /**
   * Creates settings using the first port from 5900 through 65535 that can be bound during a temporary probe.
   * The probe socket is closed before returning, so another process may claim the port before QEMU starts.
   *
   * @param width           the strictly positive output width in pixels
   * @param height          the strictly positive output height in pixels
   * @param targetFrameRate the strictly positive requested frame rate in frames per second
   * @return the settings
   * @throws IllegalArgumentException if a dimension or frame rate is not positive
   * @throws MCAVException            if no port in the VNC range can be bound by the probe
   * @since 1.0.0
   */
  public static VmSettings of(final int width, final int height, final int targetFrameRate) {
    final int port = IoUtils.getNextFreeVncPort();
    return of(port, width, height, targetFrameRate);
  }

  /**
   * Creates settings on a specific VNC port.
   *
   * @param port            the local TCP port QEMU listens on, from 5900 through 65535
   * @param width           the strictly positive output width in pixels
   * @param height          the strictly positive output height in pixels
   * @param targetFrameRate the strictly positive requested frame rate in frames per second
   * @return the settings
   * @throws IllegalArgumentException if the port is outside 5900 through 65535, or a dimension or frame rate is not positive
   * @since 1.0.0
   */
  public static VmSettings of(final int port, final int width, final int height, final int targetFrameRate) {
    Preconditions.checkArgument(
      port >= VmProcess.FIRST_VNC_PORT && port <= MAXIMUM_PORT,
      "VNC port %s must be between 5900 and 65535 inclusive!",
      port
    );
    Preconditions.checkArgument(width > 0 && height > 0, "Frame size %sx%s must be positive!", width, height);
    Preconditions.checkArgument(targetFrameRate > 0, "Frame rate %s must be positive!", targetFrameRate);

    return new VmSettings(port, width, height, targetFrameRate);
  }

  /**
   * Returns the local VNC port.
   *
   * @return the configured TCP port, from 5900 through 65535; it is not reserved by these settings
   * @since 1.0.0
   */
  public int getPort() {
    return this.port;
  }

  /**
   * Returns the width the frames are scaled to.
   *
   * @return the width in pixels
   * @since 1.0.0
   */
  public int getWidth() {
    return this.width;
  }

  /**
   * Returns the height the frames are scaled to.
   *
   * @return the height in pixels
   * @since 1.0.0
   */
  public int getHeight() {
    return this.height;
  }

  /**
   * Returns the requested VNC update rate. This setting does not change the guest display rate.
   *
   * @return the strictly positive requested frames per second; actual updates depend on the remote display
   * @since 1.0.0
   */
  public int getTargetFrameRate() {
    return this.targetFrameRate;
  }

  /**
   * Returns whether {@code other} is settings with the same port, size and frame rate.
   *
   * @param other the object to compare with
   * @return whether both settings match
   * @since 1.0.0
   */
  @Override
  public boolean equals(final @Nullable Object other) {
    if (this == other) {
      return true;
    }

    if (!(other instanceof final VmSettings settings)) {
      return false;
    }

    return (
      this.port == settings.port &&
      this.width == settings.width &&
      this.height == settings.height &&
      this.targetFrameRate == settings.targetFrameRate
    );
  }

  /**
   * Returns a hash code built from the port, the size and the frame rate.
   *
   * @return the hash code
   * @since 1.0.0
   */
  @Override
  public int hashCode() {
    return Objects.hash(this.port, this.width, this.height, this.targetFrameRate);
  }

  /**
   * Returns a debugging representation with the port, the size and the frame rate.
   *
   * @return the representation
   * @since 1.0.0
   */
  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
      .add("port", this.port)
      .add("width", this.width)
      .add("height", this.height)
      .add("targetFrameRate", this.targetFrameRate)
      .toString();
  }
}
