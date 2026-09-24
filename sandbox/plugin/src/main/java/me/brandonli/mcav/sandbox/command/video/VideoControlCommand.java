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

import com.google.common.base.Preconditions;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import me.brandonli.mcav.media.player.multimedia.VideoPlayerMultiplexer;
import me.brandonli.mcav.sandbox.MCAVSandbox;
import me.brandonli.mcav.sandbox.command.AnnotationCommandFeature;
import me.brandonli.mcav.sandbox.locale.Message;
import me.brandonli.mcav.sandbox.utils.TaskUtils;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.CommandDescription;
import org.incendo.cloud.annotations.Permission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code /mcav video pause|resume|release} and the hologram that shows the title of the video.
 */
public final class VideoControlCommand implements AnnotationCommandFeature {

  private static final Logger LOGGER = LoggerFactory.getLogger(VideoControlCommand.class);

  private final MCAVSandbox plugin;
  private final VideoPlayerManager manager;

  /**
   * Constructs the command.
   *
   * @param plugin the plugin, whose video player manager must be available
   */
  public VideoControlCommand(final MCAVSandbox plugin) {
    Preconditions.checkNotNull(plugin, "Plugin must not be null");
    this.plugin = plugin;
    this.manager = plugin.getVideoPlayerManager();
  }

  /**
   * Handles {@code /mcav video resume}: continues the paused video where it stopped, on every display and audio
   * output it uses.
   *
   * <p>Requires the permission {@code mcav.command.video.resume}; players and the console can run it. The sender is
   * told that the video resumed, also when no video is loaded, in which case nothing happens. A video whose
   * playback has already ended cannot be resumed; the sender is then told so, and a new video command plays it
   * again.
   *
   * @param sender who ran the command
   */
  @Command("mcav video resume")
  @Permission("mcav.command.video.resume")
  @CommandDescription("mcav.command.video.resume.info")
  public void resumeVideo(final CommandSender sender) {
    Preconditions.checkNotNull(sender, "Sender must not be null");
    final VideoPlayerMultiplexer player = this.manager.getPlayer();
    final boolean resumed = player == null || player.resume();
    final Component message = resumed ? Message.RESUME_PLAYER.build() : Message.RESUME_PLAYER_FAILED.build();
    sender.sendMessage(message);
  }

  /**
   * Handles {@code /mcav video pause}: freezes the running video on its last frame and stops its sound until
   * {@code /mcav video resume}.
   *
   * <p>Requires the permission {@code mcav.command.video.pause}; players and the console can run it. The sender is
   * told that the video paused, also when no video is loaded, in which case nothing happens.
   *
   * @param sender who ran the command
   */
  @Command("mcav video pause")
  @Permission("mcav.command.video.pause")
  @CommandDescription("mcav.command.video.pause.info")
  public void pauseVideo(final CommandSender sender) {
    Preconditions.checkNotNull(sender, "Sender must not be null");
    final VideoPlayerMultiplexer player = this.manager.getPlayer();
    if (player != null) {
      player.pause();
    }
    final Component message = Message.PAUSE_PLAYER.build();
    sender.sendMessage(message);
  }

  /**
   * Handles {@code /mcav video release}: stops the video for good, removes it from its display, and disconnects its
   * audio output, such as the Discord bot leaving the voice channel.
   *
   * <p>Requires the permission {@code mcav.command.video.release}; players and the console can run it. The sender
   * is told "Releasing the video player..." at once and "Video player released!" once the video is stopped in the
   * background. When no video is loaded, both messages are still sent.
   *
   * @param sender who ran the command
   */
  @Command("mcav video release")
  @Permission("mcav.command.video.release")
  @CommandDescription("mcav.command.video.release.info")
  public void releaseVideo(final CommandSender sender) {
    Preconditions.checkNotNull(sender, "Sender must not be null");
    this.manager.cancelStart();
    final Component starting = Message.RELEASE_PLAYER_START.build();
    sender.sendMessage(starting);

    final ExecutorService service = this.manager.getService();
    final Component released = Message.RELEASE_PLAYER.build();
    final Runnable done = TaskUtils.handleAsyncTask(this.plugin, () -> sender.sendMessage(released));
    final CompletableFuture<Void> release = CompletableFuture.runAsync(this.manager::clearCurrentVideo, service);
    TaskUtils.whenComplete(release, (_, error) -> reportRelease(done, error));
  }

  /**
   * Tells the sender that the video player is released once it is. A release that failed is logged, and the sender
   * is not told that the video player was released.
   */
  private static void reportRelease(final Runnable done, final @Nullable Throwable error) {
    if (error != null) {
      LOGGER.error("Failed to release the video player", error);
      return;
    }
    done.run();
  }

  /**
   * Handles {@code /mcav video hologram set <location>}: makes every video started from now on show a floating
   * hologram at the location with its title, uploader, and a progress bar.
   *
   * <p>The hologram appears with the next video; a video that is already playing keeps its current hologram, or
   * none. Requires the permission {@code mcav.command.hologram.set}; players and the console can run it. The sender
   * is told that the location was set.
   *
   * @param sender   who ran the command
   * @param location where the hologram floats, such as {@code ~ ~2 ~}
   */
  @Command("mcav video hologram set <location>")
  @Permission("mcav.command.hologram.set")
  @CommandDescription("mcav.command.hologram.set.info")
  public void setHologramLocation(final CommandSender sender, final Location location) {
    Preconditions.checkNotNull(sender, "Sender must not be null");
    Preconditions.checkNotNull(location, "Location must not be null");
    this.manager.setHologramLocation(location);
    final Component message = Message.HOLOGRAM_LOCATION_SET.build();
    sender.sendMessage(message);
  }

  /**
   * Handles {@code /mcav video hologram disable}: videos started from now on show no hologram. A hologram that is
   * already shown is not removed by this command; it goes away when the next video starts.
   *
   * <p>Requires the permission {@code mcav.command.hologram.disable}; players and the console can run it. The sender
   * is told that the hologram is disabled.
   *
   * @param sender who ran the command
   */
  @Command("mcav video hologram disable")
  @Permission("mcav.command.hologram.disable")
  @CommandDescription("mcav.command.hologram.disable.info")
  public void disableHologram(final CommandSender sender) {
    Preconditions.checkNotNull(sender, "Sender must not be null");
    this.manager.setHologramLocation(null);
    final Component message = Message.HOLOGRAM_DISABLED.build();
    sender.sendMessage(message);
  }
}
