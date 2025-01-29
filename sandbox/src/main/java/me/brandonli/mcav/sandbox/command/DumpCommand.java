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
package me.brandonli.mcav.sandbox.command;

import java.util.concurrent.CompletableFuture;
import me.brandonli.mcav.sandbox.MCAVSandbox;
import me.brandonli.mcav.sandbox.locale.Message;
import me.brandonli.mcav.sandbox.utils.DumpUtils;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.incendo.cloud.annotations.AnnotationParser;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.CommandDescription;
import org.incendo.cloud.annotations.Permission;

public final class DumpCommand implements AnnotationCommandFeature {

  @Override
  public void registerFeature(final MCAVSandbox plugin, final AnnotationParser<CommandSender> parser) {}

  @Permission("mcav.command.dump")
  @Command(value = "mcav dump", requiredSender = CommandSender.class)
  @CommandDescription("mcav.command.dump.info")
  public void startDebugGame(final CommandSender sender) {
    sender.sendMessage(Message.CREATE_DUMP.build());
    CompletableFuture.supplyAsync(DumpUtils::createAndUploadDump).thenAccept(url -> {
      final Component component = Message.SEND_DUMP.build(url);
      sender.sendMessage(component);
    });
  }
}
