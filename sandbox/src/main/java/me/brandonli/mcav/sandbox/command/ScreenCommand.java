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

import me.brandonli.mcav.sandbox.MCAV;
import me.brandonli.mcav.sandbox.gui.ScreenBuilderGui;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.incendo.cloud.annotations.AnnotationParser;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.CommandDescription;
import org.incendo.cloud.annotations.Permission;

public final class ScreenCommand implements AnnotationCommandFeature {

  private MCAV plugin;

  @Override
  public void registerFeature(final MCAV plugin, final AnnotationParser<CommandSender> parser) {
    this.plugin = plugin;
  }

  @Command("mcav screen")
  @Permission("mcav.command.screen")
  @CommandDescription("mcav.command.screen.info")
  public void showScreenBuilderGui(final Player player) {
    final ScreenBuilderGui gui = new ScreenBuilderGui(player);
    gui.open(player);
  }
}
