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
import com.google.common.base.Equivalence;
import com.google.common.base.Preconditions;
import java.util.List;
import me.brandonli.mcav.plugin.MCAVSandbox;
import me.brandonli.mcav.plugin.command.image.ImageBlockCommand;
import me.brandonli.mcav.plugin.command.image.ImageChatCommand;
import me.brandonli.mcav.plugin.command.image.ImageControlCommand;
import me.brandonli.mcav.plugin.command.image.ImageEntityCommand;
import me.brandonli.mcav.plugin.command.image.ImageMapCommand;
import me.brandonli.mcav.plugin.command.image.ImageScoreboardCommand;
import me.brandonli.mcav.plugin.command.interaction.BrowserCommand;
import me.brandonli.mcav.plugin.command.interaction.VirtualizeCommand;
import me.brandonli.mcav.plugin.command.interaction.VncCommand;
import me.brandonli.mcav.plugin.command.video.Mcv2PlayCommand;
import me.brandonli.mcav.plugin.command.video.VideoBlockCommand;
import me.brandonli.mcav.plugin.command.video.VideoChatCommand;
import me.brandonli.mcav.plugin.command.video.VideoControlCommand;
import me.brandonli.mcav.plugin.command.video.VideoDevicesCommand;
import me.brandonli.mcav.plugin.command.video.VideoEntityCommand;
import me.brandonli.mcav.plugin.command.video.VideoMapCommand;
import me.brandonli.mcav.plugin.command.video.VideoMcv2Command;
import me.brandonli.mcav.plugin.command.video.VideoPlaybackCommand;
import me.brandonli.mcav.plugin.command.video.VideoScoreboardCommand;
import me.brandonli.mcav.plugin.locale.LocaleTools;
import me.brandonli.mcav.plugin.utils.CleanupUtils;
import me.brandonli.mcav.utils.ThrowableUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import org.bukkit.command.CommandSender;
import org.checkerframework.checker.initialization.qual.UnderInitialization;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.annotations.AnnotationParser;
import org.incendo.cloud.bukkit.CloudBukkitCapabilities;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.minecraft.extras.RichDescription;
import org.incendo.cloud.paper.LegacyPaperCommandManager;

/**
 * Creates the Cloud command manager and every command feature of the plugin.
 *
 * <p>This class is not thread-safe: use it on the server main thread.
 *
 * @since 1.0.0
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
   * @throws NullPointerException if {@code plugin} is null
   * @since 1.0.0
   */
  public AnnotationParserHandler(final MCAVSandbox plugin) {
    Preconditions.checkNotNull(plugin, "Plugin must not be null!");

    this.manager = this.createCommandManager(plugin);
    this.parser = this.createAnnotationParser(this.manager);
    this.features = this.createFeatures(plugin, this.manager);
    this.shutDown = false;
  }

  private CommandManager<CommandSender> createCommandManager(@UnderInitialization AnnotationParserHandler this, final MCAVSandbox plugin) {
    final ExecutionCoordinator<CommandSender> coordinator = ExecutionCoordinator.simpleCoordinator();
    final LegacyPaperCommandManager<CommandSender> paperManager = LegacyPaperCommandManager.createNative(plugin, coordinator);
    final boolean brigadier = paperManager.hasCapability(CloudBukkitCapabilities.NATIVE_BRIGADIER);
    if (brigadier) {
      paperManager.registerBrigadier();
    }
    return paperManager;
  }

  private AnnotationParser<CommandSender> createAnnotationParser(
    @UnderInitialization AnnotationParserHandler this,
    final CommandManager<CommandSender> manager
  ) {
    final AnnotationParser<CommandSender> annotationParser = new AnnotationParser<>(manager, CommandSender.class);
    annotationParser.descriptionMapper(this::describe);
    return annotationParser;
  }

  // the server translates only the game's own keys, so plugin descriptions are rendered here
  @VisibleForTesting
  RichDescription describe(@UnderInitialization AnnotationParserHandler this, final String key) {
    if (key.isEmpty()) {
      return RichDescription.empty();
    }

    final TranslatableComponent translatable = Component.translatable(key);
    final Component rendered = LocaleTools.MANAGER.render(translatable);
    return RichDescription.of(rendered);
  }

  private List<AnnotationCommandFeature> createFeatures(
    @UnderInitialization AnnotationParserHandler this,
    final MCAVSandbox plugin,
    final CommandManager<CommandSender> manager
  ) {
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
      new VideoPlaybackCommand(plugin),
      new VideoDevicesCommand(plugin),
      new VideoEntityCommand(plugin),
      new VideoChatCommand(plugin),
      new VideoScoreboardCommand(plugin),
      new VirtualizeCommand(plugin),
      new VncCommand(plugin),
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
   * Sets up every feature and registers its commands.
   *
   * <p>If a feature cannot be set up or its commands cannot be parsed, every feature is shut down as by
   * {@link #shutdownCommands()} and the failure is rethrown, with a failure of that shutdown suppressed into it. A
   * {@link VirtualMachineError} is rethrown at once instead, with no further setup or shutdown.
   *
   * @throws RuntimeException if a feature cannot be set up or its commands cannot be parsed
   * @since 1.0.0
   */
  public void registerCommands() {
    try {
      for (final AnnotationCommandFeature feature : this.features) {
        feature.registerFeature(this.parser);
        this.parser.parse(feature);
      }
    } catch (final RuntimeException | Error exception) {
      ThrowableUtils.throwIfFatal(exception);
      this.cleanupAfterFailure(exception, this::shutdownCommands);
      throw exception;
    }
  }

  private void cleanupAfterFailure(final Throwable failure, final Runnable cleanup) {
    try {
      cleanup.run();
    } catch (final RuntimeException | Error exception) {
      ThrowableUtils.throwIfFatal(exception);
      final boolean sameFailure = FAILURE_IDENTITY.equivalent(failure, exception);
      if (!sameFailure) {
        failure.addSuppressed(exception);
      }
    }
  }

  /**
   * Shuts every feature down, also the ones after a feature that fails to shut down.
   *
   * <p>Only the first call does anything, and a failed {@link #registerCommands()} has made it already. A
   * {@link VirtualMachineError} is rethrown at once, without shutting the remaining features down.
   *
   * @throws RuntimeException if a feature fails to shut down: the first such failure, with the later ones suppressed
   * @since 1.0.0
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

  /**
   * Returns the command manager.
   *
   * @return the command manager
   * @since 1.0.0
   */
  public CommandManager<CommandSender> getManager() {
    return this.manager;
  }
}
