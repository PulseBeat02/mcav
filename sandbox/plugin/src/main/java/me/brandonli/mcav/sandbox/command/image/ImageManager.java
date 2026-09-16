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
package me.brandonli.mcav.sandbox.command.image;

import com.google.common.base.Preconditions;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import me.brandonli.mcav.bukkit.media.image.DisplayableImage;
import me.brandonli.mcav.media.image.Image;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.image.ImagePlayer;
import me.brandonli.mcav.sandbox.MCAVSandbox;
import me.brandonli.mcav.utils.ExecutorUtils;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitScheduler;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Holds the image that is shown, together with the thread that loads images.
 */
public final class ImageManager {

  private @Nullable ImagePlayer player;
  private @Nullable DisplayableImage image;
  private @Nullable Image currentImage;

  private final MCAVSandbox plugin;
  private final ExecutorService service;

  /**
   * Constructs the manager.
   *
   * @param plugin the plugin
   */
  public ImageManager(final MCAVSandbox plugin) {
    Preconditions.checkNotNull(plugin, "Plugin must not be null");
    this.plugin = plugin;
    this.service = Executors.newSingleThreadExecutor();
  }

  /**
   * Removes the image and stops the loading thread.
   */
  public void shutdown() {
    this.releaseImage(true);
    ExecutorUtils.shutdownExecutorGracefully(this.service);
  }

  /**
   * Removes the image that is shown and frees its memory.
   *
   * @param force true to release at once on the calling thread, false to release on the main thread
   */
  public void releaseImage(final boolean force) {
    if (force) {
      this.releaseNow();
    } else {
      final BukkitScheduler scheduler = Bukkit.getScheduler();
      scheduler.runTask(this.plugin, this::releaseNow);
    }
  }

  // every part is forgotten before it is released, so a failure never releases it twice
  private void releaseNow() {
    final DisplayableImage display = this.image;
    this.image = null;
    if (display != null) {
      display.release();
    }
    final Image shown = this.currentImage;
    this.currentImage = null;
    if (shown != null) {
      shown.close();
    }
    final ImagePlayer imagePlayer = this.player;
    this.player = null;
    if (imagePlayer != null) {
      imagePlayer.release();
    }
  }

  /**
   * Sets the player of an animated image.
   *
   * @param player the player, or {@code null} for none
   */
  public void setPlayer(final @Nullable ImagePlayer player) {
    this.player = player;
  }

  /**
   * Sets the display that shows the image.
   *
   * @param image the display, or {@code null} for none
   */
  public void setImage(final @Nullable DisplayableImage image) {
    this.image = image;
  }

  /**
   * Gets the thread that loads images.
   *
   * @return the executor
   */
  public ExecutorService getService() {
    return this.service;
  }

  /**
   * Sets the image that is shown, which is closed when it is released.
   *
   * @param currentImage the image, or {@code null} for none
   */
  public void setCurrentImage(final @Nullable ImageBuffer currentImage) {
    this.currentImage = currentImage;
  }
}
