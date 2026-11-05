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
package me.brandonli.mcav.plugin.command;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import java.util.HashMap;
import java.util.Map;
import me.brandonli.mcav.plugin.locale.LocaleTools;
import me.brandonli.mcav.plugin.locale.TranslationManager;
import org.bukkit.command.CommandSender;
import org.checkerframework.checker.initialization.qual.UnderInitialization;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.annotation.specifier.Greedy;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.CommandDescription;
import org.incendo.cloud.annotations.Permission;
import org.incendo.cloud.minecraft.extras.AudienceProvider;
import org.incendo.cloud.minecraft.extras.ImmutableMinecraftHelp;
import org.incendo.cloud.minecraft.extras.MinecraftHelp;
import org.incendo.cloud.permission.PermissionResult;

/**
 * {@code /mcav help [query]}: lists the commands the sender may run, with texts from the plugin's locale file.
 *
 * <p>This class is not thread-safe: use it on the server main thread, where Cloud runs its commands.
 *
 * @since 1.0.0
 */
public final class HelpCommand implements AnnotationCommandFeature {

  private static final String ROOT_COMMAND = "mcav";

  private static final String HELP_COMMAND = "/mcav help";

  private static final String WORD_SEPARATOR = " ";

  private final CommandManager<CommandSender> manager;
  private final ImmutableMinecraftHelp<CommandSender> minecraftHelp;

  /**
   * Creates the command.
   *
   * @param manager the command manager whose commands are listed
   * @throws NullPointerException if {@code manager} is null
   * @since 1.0.0
   */
  public HelpCommand(final CommandManager<CommandSender> manager) {
    Preconditions.checkNotNull(manager, "Command manager must not be null!");

    final Map<String, String> messages = this.createMessages();
    final AudienceProvider<CommandSender> audiences = AudienceProvider.nativeAudience();
    final ImmutableMinecraftHelp.CommandManagerBuildStage<CommandSender> managerStage = MinecraftHelp.builder();
    final ImmutableMinecraftHelp.AudienceProviderBuildStage<CommandSender> audienceStage = managerStage.commandManager(manager);
    final ImmutableMinecraftHelp.CommandPrefixBuildStage<CommandSender> prefixStage = audienceStage.audienceProvider(audiences);
    final ImmutableMinecraftHelp.BuildFinal<CommandSender> finalStage = prefixStage.commandPrefix(HELP_COMMAND);
    finalStage.messages(messages);
    this.manager = manager;
    this.minecraftHelp = finalStage.build();
  }

  // every key is filled in: a missing one falls back to the help's English text
  @VisibleForTesting
  Map<String, String> createMessages(@UnderInitialization HelpCommand this) {
    final Map<String, String> messages = new HashMap<>();
    this.putTranslation(messages, MinecraftHelp.MESSAGE_HELP_TITLE, "mcav.command.help.command");
    this.putTranslation(messages, MinecraftHelp.MESSAGE_DESCRIPTION, "mcav.command.help.description");
    this.putTranslation(messages, MinecraftHelp.MESSAGE_ARGUMENTS, "mcav.command.help.arguments");
    this.putTranslation(messages, MinecraftHelp.MESSAGE_OPTIONAL, "mcav.command.help.optional");
    this.putTranslation(messages, MinecraftHelp.MESSAGE_SHOWING_RESULTS_FOR_QUERY, "mcav.command.help.search_query");
    this.putTranslation(messages, MinecraftHelp.MESSAGE_NO_RESULTS_FOR_QUERY, "mcav.command.help.none_query");
    this.putTranslation(messages, MinecraftHelp.MESSAGE_AVAILABLE_COMMANDS, "mcav.command.help.available_commands");
    this.putTranslation(messages, MinecraftHelp.MESSAGE_CLICK_TO_SHOW_HELP, "mcav.command.help.show_help");
    this.putTranslation(messages, MinecraftHelp.MESSAGE_PAGE_OUT_OF_RANGE, "mcav.command.help.page_invalid");
    this.putTranslation(messages, MinecraftHelp.MESSAGE_CLICK_FOR_NEXT_PAGE, "mcav.command.help.next_page");
    this.putTranslation(messages, MinecraftHelp.MESSAGE_CLICK_FOR_PREVIOUS_PAGE, "mcav.command.help.previous_page");
    return messages;
  }

  private void putTranslation(
    @UnderInitialization HelpCommand this,
    final Map<String, String> messages,
    final String helpKey,
    final String translationKey
  ) {
    final TranslationManager translations = LocaleTools.MANAGER;
    final String text = translations.getProperty(translationKey);
    messages.put(helpKey, text);
  }

  /**
   * Shows the help for a shorthand query such as {@code dump}, a full command query such as {@code mcav dump},
   * or a page number. Full queries and page numbers are used by the help's generated navigation links. A trailing
   * page number selects a page of a filtered query, such as {@code video 2}. Lists only the commands the sender may
   * run, also under a branch such as {@code video}.
   *
   * @param sender who ran the command
   * @param query  the command to show, or {@code null} to list every command
   * @throws NullPointerException if {@code sender} is null
   * @since 1.0.0
   */
  @Permission("mcav.command.help")
  @CommandDescription("mcav.command.help.info")
  @Command("mcav help [query]")
  public void commandHelp(final CommandSender sender, @Greedy final @Nullable String query) {
    Preconditions.checkNotNull(sender, "Sender must not be null!");

    final String search = this.toHelpQuery(query);
    final MinecraftHelp<CommandSender> senderHelp = this.minecraftHelp.withCommandFilter(command -> this.isRunnableBy(sender, command));
    senderHelp.queryCommands(search, sender);
  }

  private String toHelpQuery(final @Nullable String query) {
    if (query == null || query.isBlank()) {
      return "";
    }

    final String trimmed = query.strip();
    final int separator = trimmed.indexOf(WORD_SEPARATOR);
    final String first = separator < 0 ? trimmed : trimmed.substring(0, separator);
    if (ROOT_COMMAND.equalsIgnoreCase(first)) {
      return trimmed;
    }

    try {
      // Cloud reserves a trailing integer for pagination, including a page with no command query.
      Integer.parseInt(trimmed);
      return trimmed;
    } catch (final NumberFormatException exception) {
      return ROOT_COMMAND + WORD_SEPARATOR + trimmed;
    }
  }

  private boolean isRunnableBy(
    final CommandSender sender,
    final org.incendo.cloud.Command<CommandSender> command // fqn: clashes with the Command annotation
  ) {
    final org.incendo.cloud.permission.Permission permission = command.commandPermission(); // fqn: clashes with the Permission annotation
    final PermissionResult result = this.manager.testPermission(sender, permission);
    return result.allowed();
  }
}
