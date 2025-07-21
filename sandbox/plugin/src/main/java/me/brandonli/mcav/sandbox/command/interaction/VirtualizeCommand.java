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
package me.brandonli.mcav.sandbox.command.interaction;

import java.util.Collection;
import java.util.UUID;
import me.brandonli.mcav.bukkit.media.config.MapConfiguration;
import me.brandonli.mcav.bukkit.media.result.MapResult;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.pipeline.filter.video.VideoFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.DitherFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.DitherAlgorithm;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.sandbox.MCAVSandbox;
import me.brandonli.mcav.sandbox.locale.Message;
import me.brandonli.mcav.sandbox.utils.ArgumentUtils;
import me.brandonli.mcav.sandbox.utils.DitheringArgument;
import me.brandonli.mcav.sandbox.utils.TaskUtils;
import me.brandonli.mcav.utils.ExecutorUtils;
import me.brandonli.mcav.utils.immutable.Pair;
import me.brandonli.mcav.utils.interaction.MouseClick;
import me.brandonli.mcav.vm.ExecutableNotInPathException;
import me.brandonli.mcav.vm.VMConfiguration;
import me.brandonli.mcav.vm.VMPlayer;
import me.brandonli.mcav.vm.VMSettings;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.incendo.cloud.annotation.specifier.Greedy;
import org.incendo.cloud.annotation.specifier.Quoted;
import org.incendo.cloud.annotation.specifier.Range;
import org.incendo.cloud.annotations.*;
import org.incendo.cloud.bukkit.data.MultiplePlayerSelector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class VirtualizeCommand extends AbstractInteractiveCommand<VMPlayer> {

  @Override
  public void registerFeature(final MCAVSandbox plugin, final AnnotationParser<CommandSender> parser) {
    super.registerFeature(plugin, parser);
  }

  @Override
  public void shutdown() {
    super.shutdown();
    ExecutorUtils.shutdownExecutorGracefully(this.service);
  }

  @Override
  protected void handleLeftClick(final VMPlayer player, final int x, final int y) {
    player.sendMouseEvent(MouseClick.LEFT, x, y);
  }

  @Override
  protected void handleRightClick(final VMPlayer player, final int x, final int y) {
    player.sendMouseEvent(MouseClick.RIGHT, x, y);
  }

  @Override
  protected void handleTextInput(final VMPlayer player, final String text) {
    player.sendKeyEvent(text);
  }

  @Override
  protected void releasePlayer() {
    if (this.player != null) {
      this.player.release();
      this.player = null;
    }
    if (this.result != null) {
      this.result.release();
      this.result = null;
    }
  }

  @Command("mcav vm interact")
  @Permission("mcav.vm.interact")
  @CommandDescription("mcav.command.vm.interact.info")
  public void activateInteraction(final Player player) {
    super.activateInteraction(player, Message.INTERACT_ENABLE.build(), Message.INTERACT_DISABLE.build());
  }

  @Command("mcav vm release")
  @Permission("mcav.vm.release")
  @CommandDescription("mcav.command.vm.release.info")
  public void releaseVM(final CommandSender sender) {
    super.releaseResource(sender, Message.VM_RELEASE.build());
  }

  @Command(
    "mcav vm create <playerSelector> <vmResolution> <targetFps> <blockDimensions> <mapId> <ditheringAlgorithm> <architecture> <flags>"
  )
  @Permission("mcav.command.vm.create")
  @CommandDescription("mcav.command.vm.create.info")
  public void playVM(
    final CommandSender sender,
    final MultiplePlayerSelector playerSelector,
    @Argument(suggestions = "resolutions") @Quoted final String vmResolution,
    @Argument(suggestions = "target-fps") @Range(min = "1") final int targetFps,
    @Argument(suggestions = "dimensions") @Quoted final String blockDimensions,
    @Argument(suggestions = "ids") @Range(min = "0") final int mapId,
    final DitheringArgument ditheringAlgorithm,
    final VMPlayer.Architecture architecture,
    @Greedy final String flags
  ) {
    if (!this.plugin.isQemuInstalled()) {
      sender.sendMessage(Message.QEMU_NOT_INSTALLED.build());
      return;
    }

    final Pair<Integer, Integer> resolution;
    final Pair<Integer, Integer> dimensions;
    try {
      resolution = ArgumentUtils.parseDimensions(vmResolution);
      dimensions = ArgumentUtils.parseDimensions(blockDimensions);
    } catch (final IllegalArgumentException e) {
      sender.sendMessage(Message.UNSUPPORTED_DIMENSION.build());
      return;
    }

    if (this.player != null || this.result != null) {
      try {
        this.releasePlayer();
        this.player = null;
        this.result = null;
      } catch (final Exception e) {
        throw new AssertionError(e);
      }
    }

    final int resolutionWidth = resolution.getFirst();
    final int resolutionHeight = resolution.getSecond();
    final int blockWidth = dimensions.getFirst();
    final int blockHeight = dimensions.getSecond();

    final Collection<UUID> players = ArgumentUtils.parsePlayerSelectors(playerSelector);
    final MapConfiguration configuration = MapConfiguration.builder()
      .map(mapId)
      .mapBlockHeight(blockHeight)
      .mapBlockWidth(blockWidth)
      .viewers(players)
      .build();
    final DitherAlgorithm algorithm = ditheringAlgorithm.getAlgorithm();
    final MapResult result = new MapResult(configuration);
    final VideoFilter filter = DitherFilter.dither(algorithm, result);
    final VideoPipelineStep pipeline = VideoPipelineStep.of(filter);
    final VMConfiguration config = this.parseVMOptions(flags);
    final VMSettings settings = VMSettings.of(resolutionWidth, resolutionHeight, targetFps);
    sender.sendMessage(Message.VM_LOADING.build());

    try {
      final VMPlayer player = VMPlayer.vm();
      final VideoAttachableCallback callback = player.getVideoAttachableCallback();
      callback.attach(pipeline);
      player
        .startAsync(settings, architecture, config, this.service)
        .exceptionally(throwable -> handleException(sender, throwable))
        .thenRun(TaskUtils.handleAsyncTask(this.plugin, () -> sender.sendMessage(Message.VM_CREATE.build())));
      this.player = player;
      this.result = result;
    } catch (final Exception e) {
      throw new AssertionError(e);
    }
  }

  private static boolean handleException(final CommandSender sender, final Throwable throwable) {
    if (throwable instanceof ExecutableNotInPathException) {
      sender.sendMessage(Message.VM_PATH.build());
    }
    final Logger logger = LoggerFactory.getLogger("QEMU");
    logger.error("Failed to start VM", throwable);
    return false;
  }

  private VMConfiguration parseVMOptions(final String commandLine) {
    final VMConfiguration config = VMConfiguration.builder();
    final String[] parts = commandLine.trim().split("\\s+");
    for (int i = 0; i < parts.length; i++) {
      if (parts[i].startsWith("-")) {
        final String option = parts[i].substring(1);
        if (i + 1 < parts.length && !parts[i + 1].startsWith("-")) {
          config.option(option, parts[i + 1]);
          i++;
        } else {
          config.flag(option);
        }
      }
    }
    return config;
  }
}
