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
package me.brandonli.mcav.plugin.command.video;

import com.google.common.base.Preconditions;
import java.util.concurrent.CompletableFuture;
import me.brandonli.mcav.media.player.multimedia.VideoPlayerMultiplexer;
import me.brandonli.mcav.media.player.multimedia.cv.AbstractVideoPlayerCV;
import me.brandonli.mcav.media.player.pipeline.filter.audio.VolumeFilter;
import me.brandonli.mcav.plugin.MCAVSandbox;
import me.brandonli.mcav.plugin.command.AnnotationCommandFeature;
import me.brandonli.mcav.plugin.locale.Message;
import me.brandonli.mcav.plugin.utils.SeekPosition;
import me.brandonli.mcav.plugin.utils.TaskUtils;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.incendo.cloud.annotation.specifier.Greedy;
import org.incendo.cloud.annotation.specifier.Range;
import org.incendo.cloud.annotations.Argument;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.CommandDescription;
import org.incendo.cloud.annotations.Permission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code /mcav video seek|volume|speed|loop}: controls the video that plays. The players that decode with FFmpeg or
 * OpenCV jump to any time, change the speed of a file and loop it; the VLC player only jumps to a time from the start.
 * A live stream or a camera plays on at its own pace: it only changes volume.
 */
public final class VideoPlaybackCommand implements AnnotationCommandFeature {

  private static final Logger LOGGER = LoggerFactory.getLogger(VideoPlaybackCommand.class);

  private static final String SEEK_ERROR = "Failed to seek the video";

  private static final double PERCENT = 100.0;

  private final MCAVSandbox plugin;

  private final VideoPlayerManager manager;

  /**
   * Constructs the command.
   *
   * @param plugin the plugin, whose video player manager must be available
   */
  public VideoPlaybackCommand(final MCAVSandbox plugin) {
    Preconditions.checkNotNull(plugin, "Plugin must not be null");
    this.plugin = plugin;
    this.manager = plugin.getVideoPlayerManager();
  }

  /**
   * Handles {@code /mcav video seek <position>}: jumps to a time from the start of the video, such as {@code 1:30}, or
   * from where it plays, such as {@code +10} or {@code -1:00}; a jump before the start goes to the start.
   *
   * <p>The position takes the rest of the command, because Minecraft's command parser does not accept a colon in a
   * single word. Requires the permission {@code mcav.command.video.seek}; players and the console can run it. The media is
   * opened again at the new time on the video worker thread, and the sender is told where the video jumped, or why it
   * could not.
   *
   * @param sender   who ran the command
   * @param position the time
   */
  @Command("mcav video seek <position>")
  @Permission("mcav.command.video.seek")
  @CommandDescription("mcav.command.video.seek.info")
  public void seekVideo(final CommandSender sender, @Argument(suggestions = "positions") @Greedy final String position) {
    Preconditions.checkNotNull(sender, "Sender must not be null");
    Preconditions.checkNotNull(position, "Position must not be null");
    final SeekPosition parsed = SeekPosition.parse(position);
    if (parsed == null) {
      sender.sendMessage(Message.SEEK_INVALID.build(position));
      return;
    }
    final VideoPlayerMultiplexer player = this.manager.getPlayer();
    if (player == null) {
      sender.sendMessage(Message.SEEK_FAILED.build());
      return;
    }
    final boolean decoded = player instanceof AbstractVideoPlayerCV;
    if (parsed.relative() && !decoded) {
      sender.sendMessage(Message.SEEK_RELATIVE_UNSUPPORTED.build());
      return;
    }
    final long current = player instanceof final AbstractVideoPlayerCV cvPlayer ? cvPlayer.getPositionMillis() : 0L;
    final long target = parsed.resolve(current);
    final CompletableFuture<Boolean> seek = CompletableFuture.supplyAsync(() -> player.seek(target), this.manager.getService());
    TaskUtils.whenComplete(seek, (seeked, error) -> this.reportSeek(sender, target, seeked, error));
  }

  private void reportSeek(final CommandSender sender, final long target, final @Nullable Boolean seeked, final @Nullable Throwable error) {
    if (error != null) {
      LOGGER.error(SEEK_ERROR, error);
    }
    final Component message = Boolean.TRUE.equals(seeked)
      ? Message.SEEK_PLAYER.build(SeekPosition.format(target))
      : Message.SEEK_FAILED.build();
    TaskUtils.runOnMainThread(this.plugin, () -> sender.sendMessage(message));
  }

  /**
   * Handles {@code /mcav video volume <percent>}: sets the volume of the video that plays and of the videos started
   * later, in percent of their own loudness; above 100 loud videos clip.
   *
   * <p>Requires the permission {@code mcav.command.video.volume}; players and the console can run it.
   *
   * @param sender  who ran the command
   * @param percent the volume, from 0 to 200
   */
  @Command("mcav video volume <percent>")
  @Permission("mcav.command.video.volume")
  @CommandDescription("mcav.command.video.volume.info")
  public void setVolume(final CommandSender sender, @Argument(suggestions = "volumes") @Range(min = "0", max = "200") final int percent) {
    Preconditions.checkNotNull(sender, "Sender must not be null");
    final VolumeFilter volume = this.manager.getVolume();
    volume.setVolume(percent / PERCENT);
    sender.sendMessage(Message.VOLUME_SET.build(percent));
  }

  /**
   * Handles {@code /mcav video speed <factor>}: plays the video file faster or slower, with its sound higher or lower to
   * match. The speed stays when the video jumps, and a new video starts at normal speed.
   *
   * <p>Requires the permission {@code mcav.command.video.speed}; players and the console can run it.
   *
   * @param sender who ran the command
   * @param factor the factor of the normal speed, from 0.5 to 2
   */
  @Command("mcav video speed <factor>")
  @Permission("mcav.command.video.speed")
  @CommandDescription("mcav.command.video.speed.info")
  public void setSpeed(final CommandSender sender, @Argument(suggestions = "speeds") @Range(min = "0.5", max = "2") final double factor) {
    Preconditions.checkNotNull(sender, "Sender must not be null");
    final VideoPlayerMultiplexer player = this.manager.getPlayer();
    final Component message;
    if (player != null && !(player instanceof AbstractVideoPlayerCV)) {
      message = Message.VLC_UNSUPPORTED.build();
    } else if (player instanceof final AbstractVideoPlayerCV decoded && decoded.setSpeed(factor)) {
      message = Message.SPEED_SET.build(Double.toString(factor));
    } else {
      message = Message.SPEED_FAILED.build();
    }
    sender.sendMessage(message);
  }

  /**
   * Handles {@code /mcav video loop <enabled>}: makes the videos play again from their start whenever they end, or stop
   * at their end, from now on.
   *
   * <p>Requires the permission {@code mcav.command.video.loop}; players and the console can run it. With the VLC player
   * the sender is also told that it does not loop.
   *
   * @param sender who ran the command
   * @param enabled true to loop
   */
  @Command("mcav video loop <enabled>")
  @Permission("mcav.command.video.loop")
  @CommandDescription("mcav.command.video.loop.info")
  public void setLoop(final CommandSender sender, final boolean enabled) {
    Preconditions.checkNotNull(sender, "Sender must not be null");
    this.manager.setLooping(enabled);
    sender.sendMessage(enabled ? Message.LOOP_ON.build() : Message.LOOP_OFF.build());
    final VideoPlayerMultiplexer player = this.manager.getPlayer();
    if (enabled && player != null && !(player instanceof AbstractVideoPlayerCV)) {
      sender.sendMessage(Message.VLC_UNSUPPORTED.build());
    }
  }
}
