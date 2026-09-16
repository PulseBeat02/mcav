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

import static org.incendo.cloud.minecraft.extras.MinecraftHelp.MESSAGE_ARGUMENTS;
import static org.incendo.cloud.minecraft.extras.MinecraftHelp.MESSAGE_AVAILABLE_COMMANDS;
import static org.incendo.cloud.minecraft.extras.MinecraftHelp.MESSAGE_CLICK_FOR_NEXT_PAGE;
import static org.incendo.cloud.minecraft.extras.MinecraftHelp.MESSAGE_CLICK_FOR_PREVIOUS_PAGE;
import static org.incendo.cloud.minecraft.extras.MinecraftHelp.MESSAGE_CLICK_TO_SHOW_HELP;
import static org.incendo.cloud.minecraft.extras.MinecraftHelp.MESSAGE_DESCRIPTION;
import static org.incendo.cloud.minecraft.extras.MinecraftHelp.MESSAGE_HELP_TITLE;
import static org.incendo.cloud.minecraft.extras.MinecraftHelp.MESSAGE_NO_RESULTS_FOR_QUERY;
import static org.incendo.cloud.minecraft.extras.MinecraftHelp.MESSAGE_OPTIONAL;
import static org.incendo.cloud.minecraft.extras.MinecraftHelp.MESSAGE_PAGE_OUT_OF_RANGE;
import static org.incendo.cloud.minecraft.extras.MinecraftHelp.MESSAGE_SHOWING_RESULTS_FOR_QUERY;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import java.util.HashMap;
import java.util.Map;
import me.brandonli.mcav.sandbox.locale.LocaleTools;
import me.brandonli.mcav.sandbox.locale.TranslationManager;
import org.bukkit.command.CommandSender;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.annotation.specifier.Greedy;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.CommandDescription;
import org.incendo.cloud.annotations.Permission;
import org.incendo.cloud.minecraft.extras.AudienceProvider;
import org.incendo.cloud.minecraft.extras.ImmutableMinecraftHelp;
import org.incendo.cloud.minecraft.extras.MinecraftHelp;

/**
 * {@code /mcav help [query]}: lists the commands, with texts from the plugin's locale file.
 */
public final class HelpCommand implements AnnotationCommandFeature {

  private static final String ROOT_COMMAND = "mcav";

  private final MinecraftHelp<CommandSender> minecraftHelp;

  /**
   * Constructs the command.
   *
   * @param manager the command manager whose commands are listed
   */
  public HelpCommand(final CommandManager<CommandSender> manager) {
    Preconditions.checkNotNull(manager, "Command manager must not be null");
    final Map<String, String> messages = createMessages();
    final AudienceProvider<CommandSender> audiences = AudienceProvider.nativeAudience();
    final ImmutableMinecraftHelp.CommandManagerBuildStage<CommandSender> managerStage = MinecraftHelp.builder();
    final ImmutableMinecraftHelp.AudienceProviderBuildStage<CommandSender> audienceStage = managerStage.commandManager(manager);
    final ImmutableMinecraftHelp.CommandPrefixBuildStage<CommandSender> prefixStage = audienceStage.audienceProvider(audiences);
    final ImmutableMinecraftHelp.BuildFinal<CommandSender> finalStage = prefixStage.commandPrefix("/mcav help");
    finalStage.messages(messages);
    this.minecraftHelp = finalStage.build();
  }

  /**
   * Creates the texts the help shows, each read from the locale file of the plugin. A key that is missing here makes
   * the help fall back to its own English text, so every key it knows is filled in. Visible for testing.
   *
   * @return the help messages by the key of the help
   */
  @VisibleForTesting
  static Map<String, String> createMessages() {
    final Map<String, String> messages = new HashMap<>();
    putTranslation(messages, MESSAGE_HELP_TITLE, "mcav.command.help.command");
    putTranslation(messages, MESSAGE_DESCRIPTION, "mcav.command.help.description");
    putTranslation(messages, MESSAGE_ARGUMENTS, "mcav.command.help.arguments");
    putTranslation(messages, MESSAGE_OPTIONAL, "mcav.command.help.optional");
    putTranslation(messages, MESSAGE_SHOWING_RESULTS_FOR_QUERY, "mcav.command.help.search_query");
    putTranslation(messages, MESSAGE_NO_RESULTS_FOR_QUERY, "mcav.command.help.none_query");
    putTranslation(messages, MESSAGE_AVAILABLE_COMMANDS, "mcav.command.help.available_commands");
    putTranslation(messages, MESSAGE_CLICK_TO_SHOW_HELP, "mcav.command.help.show_help");
    putTranslation(messages, MESSAGE_PAGE_OUT_OF_RANGE, "mcav.command.help.page_invalid");
    putTranslation(messages, MESSAGE_CLICK_FOR_NEXT_PAGE, "mcav.command.help.next_page");
    putTranslation(messages, MESSAGE_CLICK_FOR_PREVIOUS_PAGE, "mcav.command.help.previous_page");
    return messages;
  }

  private static void putTranslation(final Map<String, String> messages, final String helpKey, final String translationKey) {
    final TranslationManager translations = LocaleTools.MANAGER;
    final String text = translations.getProperty(translationKey);
    messages.put(helpKey, text);
  }

  /**
   * Shows the help. The query is a command without the {@code /mcav} in front, such as {@code dump}, because the
   * help matches queries against whole commands such as {@code mcav dump}.
   *
   * @param sender who ran the command
   * @param query  the command to show, or {@code null} to list every command
   */
  @Permission("mcav.command.help")
  @CommandDescription("mcav.command.help.info")
  @Command("mcav help [query]")
  public void commandHelp(final CommandSender sender, @Greedy final @Nullable String query) {
    Preconditions.checkNotNull(sender, "Sender must not be null");
    final String search = query == null ? "" : ROOT_COMMAND + " " + query;
    this.minecraftHelp.queryCommands(search, sender);
  }
}
