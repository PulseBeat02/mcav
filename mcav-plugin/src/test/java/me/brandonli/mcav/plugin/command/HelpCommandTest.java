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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import me.brandonli.mcav.plugin.locale.LocaleTools;
import me.brandonli.mcav.plugin.locale.TranslationManager;
import me.brandonli.mcav.plugin.testing.Components;
import me.brandonli.mcav.plugin.testing.TestCommandManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.command.CommandSender;
import org.incendo.cloud.Command;
import org.incendo.cloud.annotations.AnnotationParser;
import org.incendo.cloud.parser.standard.StringParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class HelpCommandTest {

  private static final String DUMP_COMMAND = "/mcav dump";
  private static final String HELP_COMMAND = "/mcav help";
  private static final String DUMP_QUERY = "dump";
  private static final String VIDEO_QUERY = "video";
  private static final String VIDEO_BLOCK_COMMAND = "/mcav video block";

  private static final String DUMP_DESCRIPTION = "Creates a dump of the current server information";
  private static final String HELP_DESCRIPTION = "Shows the commands of the plugin and how to use them";

  private static final String MESSAGE_KEY_PREFIX = "mcav.command.";

  private static final String HELP_PERMISSION = "mcav.command.help";
  private static final String VIDEO_BLOCK_PERMISSION = "mcav.command.video.block";

  private TestCommandManager manager;
  private HelpCommand help;
  private CommandSender sender;

  @BeforeEach
  void registerCommands() {
    this.manager = new TestCommandManager();
    final AnnotationParser<CommandSender> parser = new AnnotationParser<>(this.manager, CommandSender.class);
    final AnnotationParserHandler handler = mock(AnnotationParserHandler.class, CALLS_REAL_METHODS);
    parser.descriptionMapper(handler::describe);
    this.help = new HelpCommand(this.manager);
    parser.parse(this.help);
    final DumpCommand dump = new DumpCommand(() -> "unused");
    parser.parse(dump);
    this.sender = mock(CommandSender.class);
    when(this.sender.hasPermission(anyString())).thenReturn(true);
    doCallRealMethod().when(this.sender).sendMessage(any(ComponentLike.class));
  }

  @Test
  void shouldListTheCommandsWithTheirTranslatedDescriptions() {
    this.help.commandHelp(this.sender, null);
    final String text = this.helpText();
    final boolean title = text.contains("Available Commands");
    final boolean listsDump = text.contains(DUMP_COMMAND);
    final boolean listsHelp = text.contains(HELP_COMMAND);
    final boolean describesDump = text.contains(DUMP_DESCRIPTION);
    final boolean describesHelp = text.contains(HELP_DESCRIPTION);
    final boolean untranslated = text.contains(MESSAGE_KEY_PREFIX);
    assertTrue(title, text);
    assertTrue(listsDump, text);
    assertTrue(listsHelp, text);
    assertTrue(describesDump, text);
    assertTrue(describesHelp, text);
    assertFalse(untranslated, text);
  }

  private String helpText() {
    final List<Component> messages = Components.received(this.sender);
    final StringBuilder text = new StringBuilder();
    for (final Component message : messages) {
      this.appendText(message, text);
    }
    return text.toString();
  }

  private void appendText(final Component component, final StringBuilder text) {
    final String plain = Components.plain(component);
    text.append(plain);
    text.append('\n');
    final HoverEvent<?> hover = component.hoverEvent();
    if (hover != null) {
      final Object value = hover.value();
      if (value instanceof final Component hoverText) {
        this.appendText(hoverText, text);
      }
    }
    final List<Component> children = component.children();
    for (final Component child : children) {
      this.appendText(child, text);
    }
  }

  @Test
  void shouldShowOnlyTheCommandsMatchingAQuery() {
    this.help.commandHelp(this.sender, DUMP_QUERY);
    final String text = this.helpText();
    final boolean dumpListed = text.contains(DUMP_COMMAND);
    final boolean describesDump = text.contains(DUMP_DESCRIPTION);
    final boolean helpListed = text.contains(HELP_COMMAND);
    final boolean describesHelp = text.contains(HELP_DESCRIPTION);
    final boolean untranslated = text.contains(MESSAGE_KEY_PREFIX);
    assertTrue(dumpListed, text);
    assertTrue(describesDump, text);
    assertFalse(helpListed, text);
    assertFalse(describesHelp, text);
    assertFalse(untranslated, text);
  }

  @Test
  void shouldHideTheSubcommandsOfABranchThatTheSenderMayNotRun() {
    this.registerVideoCommands();
    when(this.sender.hasPermission(anyString())).thenReturn(false);
    when(this.sender.hasPermission(HELP_PERMISSION)).thenReturn(true);
    this.help.commandHelp(this.sender, VIDEO_QUERY);
    final String text = this.helpText();
    final boolean blockListed = text.contains(VIDEO_BLOCK_COMMAND);
    final boolean noResults = text.contains("No results for query");
    assertFalse(blockListed, text);
    assertTrue(noResults, text);
  }

  private void registerVideoCommands() {
    final Command.Builder<CommandSender> root = this.manager.commandBuilder("mcav");
    final Command.Builder<CommandSender> video = root.literal(VIDEO_QUERY);
    final Command.Builder<CommandSender> block = video.literal("block");
    final Command.Builder<CommandSender> blockWithMrl = block.required("mrl", StringParser.stringParser());
    final Command.Builder<CommandSender> permittedBlock = blockWithMrl.permission(VIDEO_BLOCK_PERMISSION);
    this.manager.command(permittedBlock);

    final Command.Builder<CommandSender> pause = video.literal("pause");
    final Command.Builder<CommandSender> permittedPause = pause.permission("mcav.command.video.pause");
    this.manager.command(permittedPause);
  }

  @Test
  void shouldListOnlyTheSubcommandsOfABranchThatTheSenderMayRun() {
    this.registerVideoCommands();
    when(this.sender.hasPermission(anyString())).thenReturn(false);
    when(this.sender.hasPermission(HELP_PERMISSION)).thenReturn(true);
    when(this.sender.hasPermission(VIDEO_BLOCK_PERMISSION)).thenReturn(true);
    this.help.commandHelp(this.sender, VIDEO_QUERY);
    final String text = this.helpText();
    final boolean blockListed = text.contains(VIDEO_BLOCK_COMMAND);
    final boolean pauseListed = text.contains("/mcav video pause");
    assertTrue(blockListed, text);
    assertFalse(pauseListed, text);
  }

  @Test
  void shouldTranslateEveryTextOfTheHelp() {
    final Map<String, String> messages = this.help.createMessages();
    final Map<String, String> expected = new HashMap<>();
    this.putListingMessages(expected);
    this.putNavigationMessages(expected);
    assertEquals(expected, messages, "every text the help knows is filled in from the locale file");

    final Collection<String> texts = messages.values();
    for (final String text : texts) {
      final boolean untranslated = text.startsWith("mcav.");
      assertFalse(untranslated, text);
    }
  }

  private void putListingMessages(final Map<String, String> expected) {
    final String title = this.translation("mcav.command.help.command");
    final String description = this.translation("mcav.command.help.description");
    final String arguments = this.translation("mcav.command.help.arguments");
    final String optional = this.translation("mcav.command.help.optional");
    final String available = this.translation("mcav.command.help.available_commands");
    final String showHelp = this.translation("mcav.command.help.show_help");
    expected.put(MESSAGE_HELP_TITLE, title);
    expected.put(MESSAGE_DESCRIPTION, description);
    expected.put(MESSAGE_ARGUMENTS, arguments);
    expected.put(MESSAGE_OPTIONAL, optional);
    expected.put(MESSAGE_AVAILABLE_COMMANDS, available);
    expected.put(MESSAGE_CLICK_TO_SHOW_HELP, showHelp);
  }

  private String translation(final String key) {
    final TranslationManager translations = LocaleTools.MANAGER;
    return translations.getProperty(key);
  }

  private void putNavigationMessages(final Map<String, String> expected) {
    final String query = this.translation("mcav.command.help.search_query");
    final String noResults = this.translation("mcav.command.help.none_query");
    final String invalidPage = this.translation("mcav.command.help.page_invalid");
    final String nextPage = this.translation("mcav.command.help.next_page");
    final String previousPage = this.translation("mcav.command.help.previous_page");
    expected.put(MESSAGE_SHOWING_RESULTS_FOR_QUERY, query);
    expected.put(MESSAGE_NO_RESULTS_FOR_QUERY, noResults);
    expected.put(MESSAGE_PAGE_OUT_OF_RANGE, invalidPage);
    expected.put(MESSAGE_CLICK_FOR_NEXT_PAGE, nextPage);
    expected.put(MESSAGE_CLICK_FOR_PREVIOUS_PAGE, previousPage);
  }

  @Test
  void shouldRefuseNullArguments() {
    final NullPointerException nullManager = assertThrows(NullPointerException.class, () -> new HelpCommand(null));
    final String message = nullManager.getMessage();
    assertEquals("Command manager must not be null!", message);

    assertThrows(NullPointerException.class, () -> this.help.commandHelp(null, DUMP_QUERY));
  }
}
