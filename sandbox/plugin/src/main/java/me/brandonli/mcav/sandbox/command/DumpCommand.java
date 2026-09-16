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
package me.brandonli.mcav.sandbox.command;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import me.brandonli.mcav.sandbox.locale.Message;
import me.brandonli.mcav.sandbox.utils.DumpUtils;
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
 * {@code /mcav dump}: uploads diagnostic information for bug reports and prints the link.
 */
public final class DumpCommand implements AnnotationCommandFeature {

  private static final Logger LOGGER = LoggerFactory.getLogger(DumpCommand.class);

  private final Supplier<String> uploader;

  /**
   * Constructs the command.
   */
  public DumpCommand() {
    this(DumpUtils::createAndUploadDump);
  }

  /**
   * Constructs a command that uploads dumps with another uploader.
   *
   * @param uploader collects and uploads a dump, returning its URL
   */
  @VisibleForTesting
  DumpCommand(final Supplier<String> uploader) {
    Preconditions.checkNotNull(uploader, "Uploader must not be null");
    this.uploader = uploader;
  }

  /**
   * Uploads a dump in the background and sends its link to the sender. Failures are only logged.
   *
   * @param sender who ran the command
   */
  @Permission("mcav.command.dump")
  @Command(value = "mcav dump", requiredSender = CommandSender.class)
  @CommandDescription("mcav.command.dump.info")
  public void createDump(final CommandSender sender) {
    Preconditions.checkNotNull(sender, "Sender must not be null");
    final Component loading = Message.CREATE_DUMP.build();
    sender.sendMessage(loading);

    final CompletableFuture<String> upload = CompletableFuture.supplyAsync(this.uploader);
    TaskUtils.whenComplete(upload, (url, error) -> reportUpload(sender, url, error));
  }

  private static void reportUpload(final CommandSender sender, final String url, final @Nullable Throwable error) {
    if (error != null) {
      LOGGER.error("Failed to upload the dump", error);
      final Component failure = Message.DUMP_FAILED.build();
      sender.sendMessage(failure);
      return;
    }
    final Component result = Message.SEND_DUMP.build(url);
    sender.sendMessage(result);
  }
}
