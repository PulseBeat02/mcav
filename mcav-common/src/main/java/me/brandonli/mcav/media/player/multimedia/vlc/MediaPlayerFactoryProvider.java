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
package me.brandonli.mcav.media.player.multimedia.vlc;

import me.brandonli.mcav.capability.installer.vlc.UnsupportedOperatingSystemException;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.co.caprica.vlcj.factory.ApplicationApi;
import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.log.LogLevel;
import uk.co.caprica.vlcj.log.NativeLog;

/**
 * The MediaPlayerFactoryProvider class provides a factory for creating
 * media player instances using the underlying VLC library. It ensures
 * the factory is initialized and handles its lifecycle.
 * <p>
 * This class cannot be instantiated as it is designed to act as a utility
 * class for managing the MediaPlayerFactory instance.
 */
public final class MediaPlayerFactoryProvider {

  private static final Logger INTERNAL_LOGGER = LoggerFactory.getLogger(MediaPlayerFactoryProvider.class);
  private static final @Nullable MediaPlayerFactory FACTORY;

  static {
    MediaPlayerFactory factory;
    try {
      factory = new MediaPlayerFactory("--reset-plugins-cache");
      setLoggerCallbacks(factory);
    } catch (final UnsatisfiedLinkError e) {
      factory = null;
    }
    FACTORY = factory;
  }

  private static void setLoggerCallbacks(final MediaPlayerFactory factory) {
    final ApplicationApi api = factory.application();
    final NativeLog nativeLog = api.newLog();
    nativeLog.setLevel(LogLevel.ERROR);
    nativeLog.addLogListener((level, module, file, line, name, header, id, message) -> {
      switch (level) {
        case ERROR:
          INTERNAL_LOGGER.error("[VLC] {}: {} ({}:{}) - {}", module, message, file, line, name);
          break;
        case WARNING:
          INTERNAL_LOGGER.warn("[VLC] {}: {} ({}:{}) - {}", module, message, file, line, name);
          break;
        case DEBUG:
          INTERNAL_LOGGER.debug("[VLC] {}: {} ({}:{}) - {}", module, message, file, line, name);
          break;
        default:
          INTERNAL_LOGGER.trace("[VLC] {}: {} ({}:{}) - {}", module, message, file, line, name);
          break;
      }
    });
  }

  private MediaPlayerFactoryProvider() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Retrieves the MediaPlayerFactory instance initialized with the underlying VLC library.
   * This method ensures the factory is available for use. If the factory is not supported
   * on the current system, an exception will be thrown.
   *
   * @return the initialized MediaPlayerFactory instance.
   * @throws UnsupportedOperationException if VLC is not supported on the current system.
   */
  public static MediaPlayerFactory getPlayerFactory() {
    if (FACTORY == null) {
      throw new UnsupportedOperatingSystemException("VLC is not supported on your system!");
    }
    return FACTORY;
  }

  /**
   * Releases resources held by the MediaPlayerFactory instance, if it has been initialized.
   * <p>
   * This method ensures that any resources associated with the MediaPlayerFactory
   * are properly released to avoid potential memory leaks or other issues. If the
   * factory has not been initialized, the method performs no operation.
   * <p>
   * It is important to call this method when the application no longer needs
   * the media player functionality to ensure resources are cleaned up appropriately.
   */
  public static void shutdown() {
    if (FACTORY == null) {
      return;
    }
    FACTORY.release();
  }
}
