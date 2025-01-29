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
package me.brandonli.mcav.sandbox.command.video;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import me.brandonli.mcav.media.player.combined.VideoPlayerMultiplexer;
import me.brandonli.mcav.media.result.FunctionalVideoFilter;
import me.brandonli.mcav.sandbox.MCAVSandbox;
import me.brandonli.mcav.sandbox.locale.AudienceProvider;
import me.brandonli.mcav.utils.ExecutorUtils;
import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import org.checkerframework.checker.nullness.qual.Nullable;

public final class VideoPlayerManager {

  private @Nullable VideoPlayerMultiplexer player;
  private @Nullable FunctionalVideoFilter filter;

  private final AtomicBoolean status;
  private final ExecutorService service;
  private final BukkitAudiences audiences;

  public VideoPlayerManager(final MCAVSandbox plugin) {
    final AudienceProvider provider = plugin.getAudience();
    this.status = new AtomicBoolean(false);
    this.service = Executors.newSingleThreadExecutor();
    this.audiences = provider.retrieve();
  }

  public void shutdown() {
    ExecutorUtils.shutdownExecutorGracefully(this.service);
  }

  public void releaseVideoPlayer() {
    if (this.player != null) {
      try {
        this.player.release();
        if (this.filter != null) {
          this.filter.release();
          this.filter = null;
        }
      } catch (final Exception e) {
        throw new AssertionError(e);
      }
      this.player = null;
    }
  }

  public BukkitAudiences getAudiences() {
    return this.audiences;
  }

  public ExecutorService getService() {
    return this.service;
  }

  public AtomicBoolean getStatus() {
    return this.status;
  }

  public void setPlayer(final @Nullable VideoPlayerMultiplexer player) {
    this.player = player;
  }

  public @Nullable VideoPlayerMultiplexer getPlayer() {
    return this.player;
  }

  public void setFilter(final @Nullable FunctionalVideoFilter filter) {
    this.filter = filter;
  }

  public @Nullable FunctionalVideoFilter getFilter() {
    return this.filter;
  }
}
