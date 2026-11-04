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
package me.brandonli.mcav.plugin.testing;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.bukkit.command.CommandSender;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.incendo.cloud.Command;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.bukkit.BukkitParsers;
import org.incendo.cloud.component.CommandComponent;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.internal.CommandRegistrationHandler;
import org.incendo.cloud.paper.LegacyPaperCommandManager;
import org.mockito.AdditionalAnswers;
import org.mockito.MockSettings;
import org.mockito.stubbing.Answer;

public final class TestCommandManager extends CommandManager<CommandSender> {

  private static final String SYNTAX_SEPARATOR = " ";

  // Bukkit parsers read the server when first loaded, so the test server is installed first
  public TestCommandManager() {
    super(ExecutionCoordinator.simpleCoordinator(), CommandRegistrationHandler.nullCommandRegistrationHandler());
    TestServer.ensureInstalled();
    BukkitParsers.register(this);
  }

  @Override
  public boolean hasPermission(final @NonNull CommandSender sender, final @NonNull String permission) {
    return sender.hasPermission(permission);
  }

  // the real Paper manager can only be created inside a server
  public LegacyPaperCommandManager<CommandSender> createPaperManager() {
    final MockSettings settings = withSettings();
    final Answer<Object> delegation = AdditionalAnswers.delegatesTo(this);
    settings.defaultAnswer(delegation);
    return mock(settings);
  }

  public Set<String> getSyntaxes() {
    return this.commands().stream().map(this::formatSyntax).collect(Collectors.toCollection(TreeSet::new));
  }

  public Command<CommandSender> getCommand(final String syntax) {
    for (final Command<CommandSender> command : this.commands()) {
      final String candidate = this.formatSyntax(command);
      if (candidate.equals(syntax)) {
        return command;
      }
    }
    final String message = "Command %s is not registered!".formatted(syntax);
    throw new AssertionError(message);
  }

  private String formatSyntax(final Command<CommandSender> command) {
    final List<CommandComponent<CommandSender>> components = command.components();
    return components.stream().map(CommandComponent::name).collect(Collectors.joining(SYNTAX_SEPARATOR));
  }
}
