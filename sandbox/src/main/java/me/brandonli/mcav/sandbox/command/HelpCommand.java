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

import static org.incendo.cloud.minecraft.extras.MinecraftHelp.*;

import java.util.HashMap;
import java.util.Map;
import me.brandonli.mcav.sandbox.MCAVSandbox;
import me.brandonli.mcav.sandbox.locale.LocaleTools;
import me.brandonli.mcav.sandbox.locale.TranslationManager;
import org.bukkit.command.CommandSender;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.annotation.specifier.Greedy;
import org.incendo.cloud.annotations.AnnotationParser;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.CommandDescription;
import org.incendo.cloud.annotations.Permission;
import org.incendo.cloud.minecraft.extras.AudienceProvider;
import org.incendo.cloud.minecraft.extras.MinecraftHelp;

public final class HelpCommand implements AnnotationCommandFeature {

  private CommandManager<CommandSender> manager;
  private MinecraftHelp<CommandSender> minecraftHelp;

  @Override
  public void registerFeature(final MCAVSandbox plugin, final AnnotationParser<CommandSender> parser) {
    this.manager = parser.manager();
    this.setupHelp();
  }

  private Map<String, String> constructHelpMap() {
    final TranslationManager manager = LocaleTools.MANAGER;
    final Map<String, String> bundle = new HashMap<>();
    bundle.put(MESSAGE_HELP_TITLE, manager.getProperty("mcav.command.help.command"));
    bundle.put(MESSAGE_DESCRIPTION, manager.getProperty("mcav.command.help.description"));
    bundle.put(MESSAGE_ARGUMENTS, manager.getProperty("mcav.command.help.arguments"));
    bundle.put(MESSAGE_OPTIONAL, manager.getProperty("mcav.command.help.optional"));
    bundle.put(MESSAGE_SHOWING_RESULTS_FOR_QUERY, manager.getProperty("mcav.command.help.search_query"));
    bundle.put(MESSAGE_NO_RESULTS_FOR_QUERY, manager.getProperty("mcav.command.help.none_query"));
    bundle.put(MESSAGE_AVAILABLE_COMMANDS, manager.getProperty("mcav.command.help.available_commands"));
    bundle.put(MESSAGE_CLICK_TO_SHOW_HELP, manager.getProperty("mcav.command.help.show_help"));
    bundle.put(MESSAGE_PAGE_OUT_OF_RANGE, manager.getProperty("mcav.command.help.page_invalid"));
    bundle.put(MESSAGE_CLICK_FOR_NEXT_PAGE, manager.getProperty("mcav.command.help.next_page"));
    bundle.put(MESSAGE_CLICK_FOR_PREVIOUS_PAGE, manager.getProperty("mcav.command.help.previous_page"));
    return bundle;
  }

  private void setupHelp() {
    this.minecraftHelp = MinecraftHelp.<CommandSender>builder()
      .commandManager(this.manager)
      .audienceProvider(AudienceProvider.nativeAudience())
      .commandPrefix("/mcav help")
      .messages(this.constructHelpMap())
      .build();
  }

  public CommandManager<CommandSender> getManager() {
    return this.manager;
  }

  public MinecraftHelp<CommandSender> getMinecraftHelp() {
    return this.minecraftHelp;
  }

  @Permission("mcav.command.help")
  @CommandDescription("mcav.command.help.info")
  @Command("mcav help [query]")
  public void commandHelp(final CommandSender sender, @Greedy final String query) {
    this.minecraftHelp.queryCommands(query == null ? "" : query, sender);
  }
}
