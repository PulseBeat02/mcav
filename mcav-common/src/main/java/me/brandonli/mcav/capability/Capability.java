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
package me.brandonli.mcav.capability;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import me.brandonli.mcav.capability.installer.qemu.QemuInstaller;
import me.brandonli.mcav.capability.installer.vlc.VLCInstaller;
import me.brandonli.mcav.capability.installer.ytdlp.YTDLPInstaller;
import me.brandonli.mcav.utils.ffmpeg.FFmpegExecutableProvider;

/**
 * Represents a capability of the library.
 */
public interface Capability {
  /**
   * Represents the VLC capability.
   */
  Capability VLC = createCapabilityImpl(() -> {
    try {
      final VLCInstaller installer = VLCInstaller.create();
      return installer.isSupported();
    } catch (final Throwable e) {
      return false;
    }
  });

  /**
   * Represents the FFmpeg capability.
   */
  Capability FFMPEG = createCapabilityImpl(() -> {
    try {
      final Path ffmpegPath = FFmpegExecutableProvider.getFFmpegPath();
      return Files.exists(ffmpegPath);
    } catch (final Throwable e) {
      return false;
    }
  });

  /**
   * Represents the YTDLP capability.
   */
  Capability YTDLP = createCapabilityImpl(() -> {
    try {
      final YTDLPInstaller installer = YTDLPInstaller.create();
      return installer.isSupported();
    } catch (final Throwable e) {
      return false;
    }
  });

  Capability QEMU = createCapabilityImpl(() -> {
    try {
      final QemuInstaller installer = QemuInstaller.create();
      return installer.isSupported();
    } catch (final Throwable e) {
      return false;
    }
  });

  /**
   * Returns all capabilities.
   *
   * @return an array of all capabilities
   */
  static Capability[] values() {
    return new Capability[] { VLC, FFMPEG, YTDLP, QEMU };
  }

  private static CapabilityImpl createCapabilityImpl(final BooleanSupplier supplier) {
    return new CapabilityImpl(supplier);
  }

  /**
   * Returns whether the capability is enabled or not.
   *
   * @return true if the capability is enabled, false otherwise
   */
  boolean isEnabled();

  /**
   * Returns whether the capability is disabled or not.
   *
   * @return true if the capability is disabled, false otherwise
   */
  default boolean isDisabled() {
    return !this.isEnabled();
  }
}
