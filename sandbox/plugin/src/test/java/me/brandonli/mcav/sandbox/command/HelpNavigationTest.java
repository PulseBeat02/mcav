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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import me.brandonli.mcav.sandbox.testing.Components;
import me.brandonli.mcav.sandbox.testing.TestCommandManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentIteratorFlag;
import net.kyori.adventure.text.ComponentIteratorType;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.command.CommandSender;
import org.incendo.cloud.Command;
import org.incendo.cloud.annotations.AnnotationParser;
import org.incendo.cloud.description.Description;
import org.incendo.cloud.execution.CommandExecutor;
import org.incendo.cloud.execution.CommandResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** Replays Cloud-generated click commands through the actual annotation parser and command executor. */
final class HelpNavigationTest {

  private TestCommandManager manager;
  private HelpCommand help;
  private CommandSender sender;

  @BeforeEach
  void registerPaginatedCommands() {
    this.manager = new TestCommandManager();
    final AnnotationParser<CommandSender> parser = new AnnotationParser<>(this.manager, CommandSender.class);
    this.help = new HelpCommand(this.manager);
    parser.parse(this.help);
    for (int index = 0; index < 9; index++) {
      final Command.Builder<CommandSender> root = this.manager.commandBuilder("mcav");
      final Command.Builder<CommandSender> topic = root.literal("topic" + index);
      final Description description = Description.of("Description for topic" + index);
      final Command.Builder<CommandSender> described = topic.commandDescription(description);
      this.manager.command(described);

      final Command.Builder<CommandSender> video = root.literal("video");
      final Command.Builder<CommandSender> clip = video.literal("clip" + index);
      final Description clipDescription = Description.of("Description for clip" + index);
      final Command.Builder<CommandSender> describedClip = clip.commandDescription(clipDescription);
      this.manager.command(describedClip);
    }
    final Command.Builder<CommandSender> root = this.manager.commandBuilder("mcav");
    final Command.Builder<CommandSender> similar = root.literal("mcavity");
    final Description description = Description.of("Description for mcavity");
    final Command.Builder<CommandSender> described = similar.commandDescription(description);
    this.manager.command(described);
    this.sender = mock(CommandSender.class);
    when(this.sender.hasPermission(anyString())).thenReturn(true);
    doCallRealMethod().when(this.sender).sendMessage(any(ComponentLike.class));
  }

  @Test
  void generatedEntryLinksOpenTheirOwnCommandHelp() {
    this.help.commandHelp(this.sender, null);
    this.followGeneratedClick("/mcav help mcav topic0");
    final String text = this.text();
    final boolean description = text.contains("Description for topic0");
    assertTrue(description, text);
  }

  @Test
  void indexPaginationPreservesTheWholeCommandIndexInBothDirections() {
    this.help.commandHelp(this.sender, null);
    final List<Component> firstPage = Components.received(this.sender);
    this.followGeneratedClick("/mcav help 2");
    final String secondPage = this.text();
    final boolean numberedPage = secondPage.contains("2/4");
    assertTrue(numberedPage, secondPage);
    this.followGeneratedClick("/mcav help 1");
    final List<Component> restored = Components.received(this.sender);
    assertEquals(firstPage, restored, "previous-page navigation must return to the same index, query and click targets");
  }

  @Test
  void filteredPaginationPreservesTheQueryInBothDirections() {
    this.help.commandHelp(this.sender, "video");
    final List<Component> firstPage = Components.received(this.sender);
    this.followGeneratedClick("/mcav help mcav video 2");
    final String secondPage = this.text();
    final boolean lastClip = secondPage.contains("mcav video clip8");
    assertTrue(lastClip, secondPage);
    this.followGeneratedClick("/mcav help mcav video 1");
    final List<Component> restored = Components.received(this.sender);
    assertEquals(firstPage, restored, "filtered navigation must add the mcav root exactly once");
  }

  @ParameterizedTest
  @ValueSource(strings = { "topic0", "mcav topic0", "MCAV topic0", "  mcav topic0  " })
  void acceptsShorthandAndFullManualQueries(final String query) {
    this.help.commandHelp(this.sender, query);
    final String text = this.text();
    final boolean description = text.contains("Description for topic0");
    assertTrue(description, text);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = { "   " })
  void missingAndBlankQueriesShowTheSameIndex(final String query) {
    this.help.commandHelp(this.sender, null);
    final List<Component> expected = Components.received(this.sender);
    clearInvocations(this.sender);
    this.help.commandHelp(this.sender, query);
    final List<Component> actual = Components.received(this.sender);
    assertEquals(expected, actual);
  }

  @Test
  void rootPrefixInASubcommandNameDoesNotCountAsTheWholeRoot() {
    this.help.commandHelp(this.sender, "mcavity");
    final String text = this.text();
    final boolean description = text.contains("Description for mcavity");
    assertTrue(description, text);
  }

  @Test
  void wholeRootQueriesAndManualFilteredPagesRemainSupported() {
    this.help.commandHelp(this.sender, "mcav");
    final Set<String> rootClicks = this.clickCommands();
    final boolean rootPage = rootClicks.contains("/mcav help mcav 2");
    final String rootTargets = rootClicks.toString();
    assertTrue(rootPage, rootTargets);
    clearInvocations(this.sender);
    this.help.commandHelp(this.sender, "video 2");
    final Set<String> filteredClicks = this.clickCommands();
    final boolean previousPage = filteredClicks.contains("/mcav help mcav video 1");
    final String filteredTargets = filteredClicks.toString();
    assertTrue(previousPage, filteredTargets);
  }

  private void followGeneratedClick(final String command) {
    final Set<String> available = this.clickCommands();
    final boolean generated = available.contains(command);
    assertTrue(generated, "Expected a real generated click target among " + available);
    clearInvocations(this.sender);
    final CommandExecutor<CommandSender> executor = this.manager.commandExecutor();
    final String input = command.substring(1);
    final CompletableFuture<CommandResult<CommandSender>> execution = executor.executeCommand(this.sender, input);
    execution.join();
  }

  private Set<String> clickCommands() {
    final Set<String> commands = new LinkedHashSet<>();
    final List<Component> messages = Components.received(this.sender);
    final EnumSet<ComponentIteratorFlag> flags = EnumSet.allOf(ComponentIteratorFlag.class);
    for (final Component message : messages) {
      final Iterable<Component> parts = message.iterable(ComponentIteratorType.DEPTH_FIRST, flags);
      for (final Component part : parts) {
        final ClickEvent<?> click = part.clickEvent();
        if (click != null && ClickEvent.Action.RUN_COMMAND.equals(click.action())) {
          final ClickEvent.Payload.Text payload = (ClickEvent.Payload.Text) click.payload();
          final String command = payload.value();
          commands.add(command);
        }
      }
    }
    return commands;
  }

  private String text() {
    final List<Component> messages = Components.received(this.sender);
    final List<String> lines = new ArrayList<>(messages.size());
    for (final Component message : messages) {
      final String text = Components.plain(message);
      lines.add(text);
    }
    return String.join("\n", lines);
  }
}
