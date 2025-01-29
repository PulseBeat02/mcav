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

import java.util.List;
import me.brandonli.mcav.sandbox.MCAVSandbox;
import me.brandonli.mcav.sandbox.command.video.*;
import org.bukkit.command.CommandSender;
import org.checkerframework.checker.initialization.qual.UnderInitialization;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.annotations.AnnotationParser;
import org.incendo.cloud.bukkit.CloudBukkitCapabilities;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.minecraft.extras.RichDescription;
import org.incendo.cloud.paper.LegacyPaperCommandManager;

public final class AnnotationParserHandler {

  private static final List<AnnotationCommandFeature> COMMAND_FEATURES = List.of(
    new SuggestionProvider(),
    new BrowserCommand(),
    new DumpCommand(),
    new HelpCommand(),
    new ScreenCommand(),
    new VideoMapCommand(),
    new VideoControlCommand(),
    new VideoEntityCommand(),
    new VideoChatCommand(),
    new VideoScoreboardCommand()
  );

  private final CommandManager<CommandSender> manager;
  private final AnnotationParser<CommandSender> parser;
  private final MCAVSandbox plugin;

  public AnnotationParserHandler(final MCAVSandbox plugin) {
    this.plugin = plugin;
    this.manager = this.getCommandManager(plugin);
    this.parser = this.getAnnotationParser(this.manager);
  }

  private AnnotationParser<CommandSender> getAnnotationParser(
    @UnderInitialization AnnotationParserHandler this,
    final CommandManager<CommandSender> manager
  ) {
    final Class<CommandSender> sender = CommandSender.class;
    final AnnotationParser<CommandSender> parser = new AnnotationParser<>(manager, sender);
    parser.descriptionMapper(RichDescription::translatable);
    return parser;
  }

  private CommandManager<CommandSender> getCommandManager(@UnderInitialization AnnotationParserHandler this, final MCAVSandbox plugin) {
    final ExecutionCoordinator<CommandSender> coordinator = ExecutionCoordinator.simpleCoordinator();
    final LegacyPaperCommandManager<CommandSender> manager = LegacyPaperCommandManager.createNative(plugin, coordinator);
    this.registerBrigadierCapability(manager);
    return manager;
  }

  private void registerBrigadierCapability(
    @UnderInitialization AnnotationParserHandler this,
    final LegacyPaperCommandManager<CommandSender> manager
  ) {
    if (manager.hasCapability(CloudBukkitCapabilities.NATIVE_BRIGADIER)) {
      manager.registerBrigadier();
    }
  }

  public CommandManager<CommandSender> getManager() {
    return this.manager;
  }

  public void registerCommands() {
    for (final AnnotationCommandFeature feature : COMMAND_FEATURES) {
      feature.registerFeature(this.plugin, this.parser);
      this.parser.parse(feature);
    }
  }
}
