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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import me.brandonli.mcav.media.player.multimedia.VideoPlayerMultiplexer;
import me.brandonli.mcav.sandbox.MCAVSandbox;
import me.brandonli.mcav.sandbox.command.AnnotationCommandFeature;
import me.brandonli.mcav.sandbox.locale.Message;
import org.bukkit.command.CommandSender;
import org.incendo.cloud.annotations.AnnotationParser;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.CommandDescription;
import org.incendo.cloud.annotations.Permission;

public final class VideoControlCommand implements AnnotationCommandFeature {

  private VideoPlayerManager manager;

  @Override
  public void registerFeature(final MCAVSandbox plugin, final AnnotationParser<CommandSender> parser) {
    this.manager = plugin.getVideoPlayerManager();
  }

  @Command("mcav video resume")
  @Permission("mcav.command.video.resume")
  @CommandDescription("mcav.command.video.resume.info")
  public void resumeVideo(final CommandSender player) {
    final VideoPlayerMultiplexer videoPlayer = this.manager.getPlayer();
    if (videoPlayer != null) {
      videoPlayer.resume();
    }
    player.sendMessage(Message.RESUME_PLAYER.build());
  }

  @Command("mcav video release")
  @Permission("mcav.command.video.release")
  @CommandDescription("mcav.command.video.release.info")
  public void releaseVideo(final CommandSender player) {
    final ExecutorService service = this.manager.getService();
    player.sendMessage(Message.RELEASE_PLAYER_START.build());
    CompletableFuture.runAsync(this.manager::releaseVideoPlayer, service).thenRun(() -> player.sendMessage(Message.RELEASE_PLAYER.build()));
  }

  @Command("mcav video pause")
  @Permission("mcav.command.video.pause")
  @CommandDescription("mcav.command.video.pause.info")
  public void pauseVideo(final CommandSender player) {
    final VideoPlayerMultiplexer videoPlayer = this.manager.getPlayer();
    if (videoPlayer != null) {
      videoPlayer.pause();
    }
    player.sendMessage(Message.PAUSE_PLAYER.build());
  }
}
