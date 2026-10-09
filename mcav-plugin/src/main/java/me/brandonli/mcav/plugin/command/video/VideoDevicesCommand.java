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
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import me.brandonli.mcav.plugin.MCAVSandbox;
import me.brandonli.mcav.plugin.command.AnnotationCommandFeature;
import me.brandonli.mcav.plugin.locale.Message;
import me.brandonli.mcav.plugin.utils.CaptureDevices;
import me.brandonli.mcav.plugin.utils.TaskUtils;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.CommandDescription;
import org.incendo.cloud.annotations.Permission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code /mcav video devices}: lists the cameras and capture cards of the server, the only devices the DEVICE player
 * then plays, by the number listed.
 */
public final class VideoDevicesCommand implements AnnotationCommandFeature {

  private static final Logger LOGGER = LoggerFactory.getLogger(VideoDevicesCommand.class);

  private static final String LIST_ERROR = "Failed to list the capture devices";

  private final MCAVSandbox plugin;

  private final VideoPlayerManager manager;

  /**
   * Constructs the command.
   *
   * @param plugin the plugin, whose video player manager must be available
   */
  public VideoDevicesCommand(final MCAVSandbox plugin) {
    Preconditions.checkNotNull(plugin, "Plugin must not be null");
    this.plugin = plugin;
    this.manager = plugin.getVideoPlayerManager();
  }

  /**
   * Handles {@code /mcav video devices}: lists the capture devices on the video worker thread, remembers them for the
   * DEVICE player and tells the sender their numbers and names.
   *
   * <p>Requires the permission {@code mcav.command.video.device}, which playing a device requires too.
   *
   * @param sender who ran the command
   */
  @Command("mcav video devices")
  @Permission(CaptureDevices.PERMISSION)
  @CommandDescription("mcav.command.video.devices.info")
  public void listDevices(final CommandSender sender) {
    Preconditions.checkNotNull(sender, "Sender must not be null");
    final CompletableFuture<List<CaptureDevices.Device>> listing = CompletableFuture.supplyAsync(
      CaptureDevices::list,
      this.manager.getService()
    );
    TaskUtils.whenComplete(listing, (devices, error) -> this.report(sender, devices, error));
  }

  private void report(final CommandSender sender, final @Nullable List<CaptureDevices.Device> devices, final @Nullable Throwable error) {
    if (error != null) {
      LOGGER.error(LIST_ERROR, error);
    }
    final List<CaptureDevices.Device> listed = devices == null ? List.of() : devices;
    this.manager.setDevices(listed);
    final String names = listed
      .stream()
      .map(device -> device.index() + ": " + device.name())
      .collect(Collectors.joining(", "));
    final Component message = listed.isEmpty() ? Message.DEVICE_NONE.build() : Message.DEVICE_LIST.build(names);
    TaskUtils.runOnMainThread(this.plugin, () -> sender.sendMessage(message));
  }
}
