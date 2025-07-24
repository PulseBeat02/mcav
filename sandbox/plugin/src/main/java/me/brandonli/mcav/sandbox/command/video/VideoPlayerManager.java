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
package me.brandonli.mcav.sandbox.command.video;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import me.brandonli.mcav.MCAVApi;
import me.brandonli.mcav.bukkit.hologram.Hologram;
import me.brandonli.mcav.capability.Capability;
import me.brandonli.mcav.media.player.multimedia.VideoPlayerMultiplexer;
import me.brandonli.mcav.media.player.pipeline.filter.video.FunctionalVideoFilter;
import me.brandonli.mcav.sandbox.MCAVSandbox;
import me.brandonli.mcav.sandbox.audio.AudioProvider;
import me.brandonli.mcav.utils.ExecutorUtils;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.scheduler.BukkitScheduler;
import org.checkerframework.checker.nullness.qual.Nullable;

public final class VideoPlayerManager {

  private @Nullable VideoPlayerMultiplexer player;
  private @Nullable FunctionalVideoFilter filter;
  private @Nullable Hologram hologram;
  private @Nullable Location hologramLocation;

  private final MCAVSandbox plugin;
  private final MCAVApi api;
  private final AtomicBoolean status;
  private final ExecutorService service;
  private final AudioProvider provider;

  public VideoPlayerManager(final MCAVSandbox plugin) {
    this.status = new AtomicBoolean(false);
    this.service = Executors.newSingleThreadExecutor();
    this.provider = plugin.getAudioProvider();
    this.api = plugin.getMCAV();
    this.plugin = plugin;
  }

  public void setHologram(final @Nullable Hologram hologram) {
    this.hologram = hologram;
  }

  public @Nullable Hologram getHologram() {
    return this.hologram;
  }

  public void setHologramLocation(final @Nullable Location location) {
    this.hologramLocation = location;
  }

  public @Nullable Location getHologramLocation() {
    return this.hologramLocation;
  }

  public void shutdown() {
    this.releaseVideoPlayer(true);
    ExecutorUtils.shutdownExecutorGracefully(this.service);
  }

  public boolean isVLCSupported() {
    return this.api.hasCapability(Capability.VLC);
  }

  public void releaseVideoPlayer(final boolean disable) {
    final Runnable task = () -> {
      if (this.player != null) {
        try {
          this.player.release();
          this.provider.releaseAudioFilter();
          if (this.filter != null) {
            this.filter.release();
            this.filter = null;
          }
        } catch (final Exception e) {
          throw new AssertionError(e);
        }
        this.player = null;
      }
      if (this.hologram != null) {
        this.hologram.kill();
        this.hologram = null;
      }
    };
    if (disable) {
      task.run();
    } else {
      final BukkitScheduler scheduler = Bukkit.getScheduler();
      scheduler.runTask(this.plugin, task);
    }
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
