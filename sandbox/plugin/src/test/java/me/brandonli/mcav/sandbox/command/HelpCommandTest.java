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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import me.brandonli.mcav.sandbox.locale.LocaleTools;
import me.brandonli.mcav.sandbox.locale.TranslationManager;
import me.brandonli.mcav.sandbox.testing.Components;
import me.brandonli.mcav.sandbox.testing.TestCommandManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.command.CommandSender;
import org.incendo.cloud.annotations.AnnotationParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link HelpCommand} with the help and dump commands registered like the plugin registers them.
 */
final class HelpCommandTest {

  private HelpCommand help;
  private CommandSender sender;

  @BeforeEach
  void registerCommands() {
    final TestCommandManager manager = new TestCommandManager();
    final AnnotationParser<CommandSender> parser = new AnnotationParser<>(manager, CommandSender.class);
    parser.descriptionMapper(AnnotationParserHandler::describe);
    this.help = new HelpCommand(manager);
    parser.parse(this.help);
    final DumpCommand dump = new DumpCommand(() -> "unused");
    parser.parse(dump);
    this.sender = mock(CommandSender.class);
    when(this.sender.hasPermission(anyString())).thenReturn(true);
    // the help sends some lines as builders; the real default method turns them into components
    doCallRealMethod().when(this.sender).sendMessage(any(ComponentLike.class));
  }

  // the help shows descriptions when the mouse hovers over a command, so the hover texts are included
  private static void appendText(final Component component, final StringBuilder text) {
    final String plain = Components.plain(component);
    text.append(plain);
    text.append('\n');
    final HoverEvent<?> hover = component.hoverEvent();
    if (hover != null) {
      final Object value = hover.value();
      if (value instanceof final Component hoverText) {
        appendText(hoverText, text);
      }
    }
    final List<Component> children = component.children();
    for (final Component child : children) {
      appendText(child, text);
    }
  }

  private String helpText() {
    final List<Component> messages = Components.received(this.sender);
    final StringBuilder text = new StringBuilder();
    for (final Component message : messages) {
      appendText(message, text);
    }
    return text.toString();
  }

  @Test
  void listsTheCommandsWithTheirTranslatedDescriptions() {
    this.help.commandHelp(this.sender, null);
    final String text = this.helpText();
    final boolean title = text.contains("Available Commands");
    final boolean dump = text.contains("/mcav dump");
    final boolean helpCommand = text.contains("/mcav help");
    final boolean dumpDescription = text.contains("Creates a dump of the current server information");
    final boolean helpDescription = text.contains("Shows the commands of the plugin and how to use them");
    final boolean untranslated = text.contains("mcav.command.");
    assertTrue(title, text);
    assertTrue(dump, text);
    assertTrue(helpCommand, text);
    assertTrue(dumpDescription, text);
    assertTrue(helpDescription, text);
    assertFalse(untranslated, text);
  }

  @Test
  void showsOnlyTheCommandsMatchingAQuery() {
    this.help.commandHelp(this.sender, "dump");
    final String text = this.helpText();
    final boolean dumpListed = text.contains("/mcav dump");
    final boolean dumpDescription = text.contains("Creates a dump of the current server information");
    final boolean helpListed = text.contains("/mcav help");
    final boolean helpDescription = text.contains("Shows the commands of the plugin and how to use them");
    final boolean untranslated = text.contains("mcav.command.");
    assertTrue(dumpListed, text);
    assertTrue(dumpDescription, text);
    assertFalse(helpListed, text);
    assertFalse(helpDescription, text);
    assertFalse(untranslated, text);
  }

  private static String translation(final String key) {
    final TranslationManager translations = LocaleTools.MANAGER;
    return translations.getProperty(key);
  }

  /**
   * Adds the texts of the command listing to the expected messages.
   *
   * @param expected the expected messages, by the key of the help
   */
  private static void putListingMessages(final Map<String, String> expected) {
    final String title = translation("mcav.command.help.command");
    final String description = translation("mcav.command.help.description");
    final String arguments = translation("mcav.command.help.arguments");
    final String optional = translation("mcav.command.help.optional");
    final String available = translation("mcav.command.help.available_commands");
    final String showHelp = translation("mcav.command.help.show_help");
    expected.put(MESSAGE_HELP_TITLE, title);
    expected.put(MESSAGE_DESCRIPTION, description);
    expected.put(MESSAGE_ARGUMENTS, arguments);
    expected.put(MESSAGE_OPTIONAL, optional);
    expected.put(MESSAGE_AVAILABLE_COMMANDS, available);
    expected.put(MESSAGE_CLICK_TO_SHOW_HELP, showHelp);
  }

  /**
   * Adds the texts of the search and of the page navigation to the expected messages.
   *
   * @param expected the expected messages, by the key of the help
   */
  private static void putNavigationMessages(final Map<String, String> expected) {
    final String query = translation("mcav.command.help.search_query");
    final String noResults = translation("mcav.command.help.none_query");
    final String invalidPage = translation("mcav.command.help.page_invalid");
    final String nextPage = translation("mcav.command.help.next_page");
    final String previousPage = translation("mcav.command.help.previous_page");
    expected.put(MESSAGE_SHOWING_RESULTS_FOR_QUERY, query);
    expected.put(MESSAGE_NO_RESULTS_FOR_QUERY, noResults);
    expected.put(MESSAGE_PAGE_OUT_OF_RANGE, invalidPage);
    expected.put(MESSAGE_CLICK_FOR_NEXT_PAGE, nextPage);
    expected.put(MESSAGE_CLICK_FOR_PREVIOUS_PAGE, previousPage);
  }

  @Test
  void translatesEveryTextOfTheHelp() {
    final Map<String, String> messages = HelpCommand.createMessages();
    final Map<String, String> expected = new HashMap<>();
    putListingMessages(expected);
    putNavigationMessages(expected);
    assertEquals(expected, messages, "every text the help knows is filled in from the locale file");
    final Collection<String> texts = messages.values();
    for (final String text : texts) {
      final boolean untranslated = text.startsWith("mcav.");
      assertFalse(untranslated, text);
    }
  }

  @Test
  void refusesNullArguments() {
    assertThrows(NullPointerException.class, () -> new HelpCommand(null));
    assertThrows(NullPointerException.class, () -> this.help.commandHelp(null, "dump"));
  }
}
