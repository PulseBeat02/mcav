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

public final class ImageManager {

  private @Nullable ImagePlayer player;
  private @Nullable DisplayableImage image;
  private @Nullable Image currentImage;

  private final MCAVSandbox plugin;
  private final ExecutorService service;

  public ImageManager(final MCAVSandbox plugin) {
    this.plugin = plugin;
    this.service = Executors.newSingleThreadExecutor();
  }

  public void shutdown() {
    this.releaseImage(true);
    ExecutorUtils.shutdownExecutorGracefully(this.service);
  }

  public void releaseImage(final boolean force) {
    final Runnable task = () -> {
      if (this.image != null) {
        this.image.release();
        this.image = null;
      }
      if (this.currentImage != null) {
        try {
          this.currentImage.close();
        } catch (final Exception e) {
          throw new AssertionError(e);
        }
        this.currentImage = null;
      }
      if (this.player != null) {
        this.player.release();
        this.player = null;
      }
    };
    if (force) {
      task.run();
    } else {
      final BukkitScheduler scheduler = Bukkit.getScheduler();
      scheduler.runTask(this.plugin, task);
    }
  }

  public void setPlayer(final @Nullable ImagePlayer player) {
    this.player = player;
  }

  public void setImage(final @Nullable DisplayableImage image) {
    this.image = image;
  }

  public ExecutorService getService() {
    return this.service;
  }

  public void setCurrentImage(final @Nullable ImageBuffer currentImage) {
    this.currentImage = currentImage;
  }
}
