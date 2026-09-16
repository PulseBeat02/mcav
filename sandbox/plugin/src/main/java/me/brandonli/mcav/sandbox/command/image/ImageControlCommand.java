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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import me.brandonli.mcav.sandbox.MCAVSandbox;
import me.brandonli.mcav.sandbox.command.AnnotationCommandFeature;
import me.brandonli.mcav.sandbox.locale.Message;
import me.brandonli.mcav.sandbox.utils.TaskUtils;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.CommandDescription;
import org.incendo.cloud.annotations.Permission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code /mcav image release}: removes the image that is shown.
 */
public final class ImageControlCommand implements AnnotationCommandFeature {

  private static final Logger LOGGER = LoggerFactory.getLogger(ImageControlCommand.class);

  private final MCAVSandbox plugin;
  private final ImageManager manager;

  /**
   * Constructs the command.
   *
   * @param plugin the plugin
   */
  public ImageControlCommand(final MCAVSandbox plugin) {
    Preconditions.checkNotNull(plugin, "Plugin must not be null");
    this.plugin = plugin;
    this.manager = plugin.getImageManager();
  }

  /**
   * Handles {@code /mcav image release}: removes the image shown by any of the {@code /mcav image} commands and
   * frees its memory. Blocks, maps, chat lines, text display entities, or scoreboards that showed it disappear for
   * every viewer.
   *
   * <p>Requires the permission {@code mcav.command.image.release}; players and the console can run it. The sender
   * is told "Releasing image..." at once and "Image released!" when it is done. When no image is shown, the
   * command does nothing but still sends both messages.
   *
   * @param sender who ran the command
   */
  @Command("mcav image release")
  @Permission("mcav.command.image.release")
  @CommandDescription("mcav.command.image.release.info")
  public void releaseImage(final CommandSender sender) {
    Preconditions.checkNotNull(sender, "Sender must not be null");
    final Component starting = Message.RELEASE_IMAGE_START.build();
    sender.sendMessage(starting);

    final ExecutorService service = this.manager.getService();
    final Component released = Message.RELEASE_IMAGE.build();
    final Runnable done = TaskUtils.handleAsyncTask(this.plugin, () -> sender.sendMessage(released));
    final CompletableFuture<Void> release = CompletableFuture.runAsync(() -> this.manager.releaseImage(false), service);
    TaskUtils.whenComplete(release, (_, error) -> reportRelease(done, error));
  }

  /**
   * Tells the sender that the image is released once it is. A release that failed is logged, and the sender is not
   * told that the image was released.
   */
  private static void reportRelease(final Runnable done, final @Nullable Throwable error) {
    if (error != null) {
      LOGGER.error("Failed to release the image", error);
      return;
    }
    done.run();
  }
}
