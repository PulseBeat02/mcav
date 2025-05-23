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

import java.lang.ref.Cleaner;
import me.brandonli.mcav.capability.installer.vlc.UnsupportedOperatingSystemException;
import org.checkerframework.checker.nullness.qual.Nullable;
import uk.co.caprica.vlcj.factory.MediaPlayerFactory;

/**
 * The MediaPlayerFactoryProvider class provides a factory for creating
 * media player instances using the underlying VLC library. It ensures
 * the factory is initialized and handles its lifecycle.
 * <p>
 * This class cannot be instantiated as it is designed to act as a utility
 * class for managing the MediaPlayerFactory instance.
 */
public final class MediaPlayerFactoryProvider {

  private static final Cleaner CLEANER = Cleaner.create();
  private static final FactoryHolder HOLDER = new FactoryHolder();

  private static class FactoryHolder implements AutoCloseable {

    private @Nullable MediaPlayerFactory factory;
    private Cleaner.@Nullable Cleanable cleanable;

    @SuppressWarnings("all")
    private final Object cleanerKey = new Object(); // checker

    FactoryHolder() {
      try {
        this.factory = new MediaPlayerFactory();
        this.cleanable = CLEANER.register(this.cleanerKey, new FactoryResources(this.factory));
      } catch (final Throwable e) {
        this.factory = null;
        this.cleanable = null;
      }
    }

    @Nullable
    MediaPlayerFactory getFactory() {
      return this.factory;
    }

    @Override
    public void close() {
      if (this.cleanable != null) {
        this.cleanable.clean();
        this.factory = null;
      }
    }
  }

  private static class FactoryResources implements Runnable {

    private final MediaPlayerFactory factory;

    FactoryResources(final MediaPlayerFactory factory) {
      this.factory = factory;
    }

    @Override
    public void run() {
      if (this.factory != null) {
        try {
          this.factory.release();
        } catch (final Exception ignored) {}
      }
    }
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
    final MediaPlayerFactory factory = HOLDER.getFactory();
    if (factory == null) {
      throw new UnsupportedOperatingSystemException("VLC is not supported on your system!");
    }
    return factory;
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
    HOLDER.close();
  }
}
