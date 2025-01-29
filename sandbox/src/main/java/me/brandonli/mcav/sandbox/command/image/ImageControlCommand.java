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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import me.brandonli.mcav.sandbox.MCAVSandbox;
import me.brandonli.mcav.sandbox.command.AnnotationCommandFeature;
import me.brandonli.mcav.sandbox.locale.Message;
import me.brandonli.mcav.sandbox.utils.TaskUtils;
import org.bukkit.command.CommandSender;
import org.incendo.cloud.annotations.AnnotationParser;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.CommandDescription;
import org.incendo.cloud.annotations.Permission;

public final class ImageControlCommand implements AnnotationCommandFeature {

  private MCAVSandbox sandbox;
  private ImageManager manager;

  @Override
  public void registerFeature(final MCAVSandbox plugin, final AnnotationParser<CommandSender> parser) {
    this.sandbox = plugin;
    this.manager = plugin.getImageManager();
  }

  @Command("mcav image release")
  @Permission("mcav.command.image.release")
  @CommandDescription("mcav.command.image.release.info")
  public void releaseVideo(final CommandSender player) {
    final ExecutorService service = this.manager.getService();
    player.sendMessage(Message.RELEASE_IMAGE_START.build());
    CompletableFuture.runAsync(() -> this.manager.releaseImage(false), service).thenRun(
      TaskUtils.handleAsyncTask(this.sandbox, () -> player.sendMessage(Message.RELEASE_IMAGE.build()))
    );
  }
}
