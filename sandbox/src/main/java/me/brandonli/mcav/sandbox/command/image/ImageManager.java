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
package me.brandonli.mcav.sandbox.command.image;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import me.brandonli.mcav.bukkit.media.image.DisplayableImage;
import me.brandonli.mcav.media.image.Image;
import me.brandonli.mcav.media.image.StaticImage;
import me.brandonli.mcav.media.player.image.ImagePlayer;
import me.brandonli.mcav.utils.ExecutorUtils;
import org.checkerframework.checker.nullness.qual.Nullable;

public final class ImageManager {

  private @Nullable ImagePlayer player;
  private @Nullable DisplayableImage image;
  private @Nullable Image currentImage;

  private final ExecutorService service;

  public ImageManager() {
    this.service = Executors.newSingleThreadExecutor();
  }

  public void shutdown() {
    this.releaseImage();
    ExecutorUtils.shutdownExecutorGracefully(this.service);
  }

  public void releaseImage() {
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

  public void setCurrentImage(final @Nullable StaticImage currentImage) {
    this.currentImage = currentImage;
  }
}
