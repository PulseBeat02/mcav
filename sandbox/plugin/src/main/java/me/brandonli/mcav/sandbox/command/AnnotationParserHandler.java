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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Equivalence;
import com.google.common.base.Preconditions;
import java.util.List;
import me.brandonli.mcav.sandbox.MCAVSandbox;
import me.brandonli.mcav.sandbox.command.image.ImageBlockCommand;
import me.brandonli.mcav.sandbox.command.image.ImageChatCommand;
import me.brandonli.mcav.sandbox.command.image.ImageControlCommand;
import me.brandonli.mcav.sandbox.command.image.ImageEntityCommand;
import me.brandonli.mcav.sandbox.command.image.ImageMapCommand;
import me.brandonli.mcav.sandbox.command.image.ImageScoreboardCommand;
import me.brandonli.mcav.sandbox.command.interaction.BrowserCommand;
import me.brandonli.mcav.sandbox.command.interaction.VirtualizeCommand;
import me.brandonli.mcav.sandbox.command.video.Mcv2PlayCommand;
import me.brandonli.mcav.sandbox.command.video.VideoBlockCommand;
import me.brandonli.mcav.sandbox.command.video.VideoChatCommand;
import me.brandonli.mcav.sandbox.command.video.VideoControlCommand;
import me.brandonli.mcav.sandbox.command.video.VideoEntityCommand;
import me.brandonli.mcav.sandbox.command.video.VideoMapCommand;
import me.brandonli.mcav.sandbox.command.video.VideoMcv2Command;
import me.brandonli.mcav.sandbox.command.video.VideoScoreboardCommand;
import me.brandonli.mcav.sandbox.locale.LocaleTools;
import me.brandonli.mcav.sandbox.utils.CleanupUtils;
import me.brandonli.mcav.utils.ThrowableUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import org.bukkit.command.CommandSender;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.annotations.AnnotationParser;
import org.incendo.cloud.bukkit.CloudBukkitCapabilities;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.minecraft.extras.RichDescription;
import org.incendo.cloud.paper.LegacyPaperCommandManager;

/**
 * Creates the Cloud command manager and every command feature of the plugin.
 */
public final class AnnotationParserHandler {

  private static final Equivalence<Object> FAILURE_IDENTITY = Equivalence.identity();

  private final CommandManager<CommandSender> manager;

  private final AnnotationParser<CommandSender> parser;

  private final List<AnnotationCommandFeature> features;

  private boolean shutDown;

  /**
   * Creates the command manager and the features. The plugin's managers must exist already.
   *
   * @param plugin the plugin
   */
  public AnnotationParserHandler(final MCAVSandbox plugin) {
    Preconditions.checkNotNull(plugin, "Plugin must not be null");
    this.manager = createCommandManager(plugin);
    this.parser = createAnnotationParser(this.manager);
    this.features = createFeatures(plugin, this.manager);
  }

  private static CommandManager<CommandSender> createCommandManager(final MCAVSandbox plugin) {
    final ExecutionCoordinator<CommandSender> coordinator = ExecutionCoordinator.simpleCoordinator();
    final LegacyPaperCommandManager<CommandSender> paperManager = LegacyPaperCommandManager.createNative(plugin, coordinator);
    final boolean brigadier = paperManager.hasCapability(CloudBukkitCapabilities.NATIVE_BRIGADIER);
    if (brigadier) {
      paperManager.registerBrigadier();
    }
    return paperManager;
  }

  private static AnnotationParser<CommandSender> createAnnotationParser(final CommandManager<CommandSender> manager) {
    final AnnotationParser<CommandSender> annotationParser = new AnnotationParser<>(manager, CommandSender.class);
    annotationParser.descriptionMapper(AnnotationParserHandler::describe);
    return annotationParser;
  }

  /**
   * Renders the description of a command or argument with the messages of the plugin. The server only translates
   * the keys of the game itself, so a translatable description would show its key in the help and the command
   * suggestions.
   *
   * @param key the key of the message, or an empty string for no description
   * @return the rendered description
   */
  @VisibleForTesting
  static RichDescription describe(final String key) {
    if (key.isEmpty()) {
      return RichDescription.empty();
    }
    final TranslatableComponent translatable = Component.translatable(key);
    final Component rendered = LocaleTools.MANAGER.render(translatable);
    return RichDescription.of(rendered);
  }

  private static List<AnnotationCommandFeature> createFeatures(final MCAVSandbox plugin, final CommandManager<CommandSender> manager) {
    return List.of(
      new SuggestionProvider(),
      new BrowserCommand(plugin),
      new DumpCommand(),
      new HelpCommand(manager),
      new ScreenCommand(),
      new VideoMapCommand(plugin),
      new VideoMcv2Command(plugin),
      new Mcv2PlayCommand(plugin),
      new VideoControlCommand(plugin),
      new VideoEntityCommand(plugin),
      new VideoChatCommand(plugin),
      new VideoScoreboardCommand(plugin),
      new VirtualizeCommand(plugin),
      new VideoBlockCommand(plugin),
      new ImageBlockCommand(plugin),
      new ImageChatCommand(plugin),
      new ImageControlCommand(plugin),
      new ImageEntityCommand(plugin),
      new ImageMapCommand(plugin),
      new ImageScoreboardCommand(plugin)
    );
  }

  /**
   * Gets the command manager.
   *
   * @return the command manager
   */
  public CommandManager<CommandSender> getManager() {
    return this.manager;
  }

  /**
   * Sets up every feature and registers its commands.
   */
  public void registerCommands() {
    try {
      for (final AnnotationCommandFeature feature : this.features) {
        feature.registerFeature(this.parser);
        this.parser.parse(feature);
      }
    } catch (final RuntimeException | Error exception) {
      ThrowableUtils.throwIfFatal(exception);
      try {
        this.shutdownCommands();
      } catch (final RuntimeException | Error cleanupFailure) {
        ThrowableUtils.throwIfFatal(cleanupFailure);
        final boolean sameFailure = FAILURE_IDENTITY.equivalent(exception, cleanupFailure);
        if (!sameFailure) {
          exception.addSuppressed(cleanupFailure);
        }
      }
      throw exception;
    }
  }

  /**
   * Shuts every feature down.
   */
  public void shutdownCommands() {
    if (this.shutDown) {
      return;
    }
    this.shutDown = true;
    final int count = this.features.size();
    final Runnable[] cleanups = new Runnable[count];
    for (int index = 0; index < count; index++) {
      final AnnotationCommandFeature feature = this.features.get(index);
      cleanups[index] = feature::shutdown;
    }
    CleanupUtils.runAll(cleanups);
  }
}
