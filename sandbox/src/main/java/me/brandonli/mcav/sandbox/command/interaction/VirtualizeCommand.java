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
package me.brandonli.mcav.sandbox.command.interaction;

import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import me.brandonli.mcav.bukkit.media.config.MapConfiguration;
import me.brandonli.mcav.bukkit.media.result.MapResult;
import me.brandonli.mcav.media.player.metadata.VideoMetadata;
import me.brandonli.mcav.media.player.pipeline.filter.video.VideoFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.DitherFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.DitherAlgorithm;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.media.player.vm.ExecutableNotInPathException;
import me.brandonli.mcav.media.player.vm.VMConfiguration;
import me.brandonli.mcav.media.player.vm.VMPlayer;
import me.brandonli.mcav.sandbox.MCAVSandbox;
import me.brandonli.mcav.sandbox.locale.Message;
import me.brandonli.mcav.sandbox.utils.ArgumentUtils;
import me.brandonli.mcav.sandbox.utils.DitheringArgument;
import me.brandonli.mcav.utils.ExecutorUtils;
import me.brandonli.mcav.utils.immutable.Pair;
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

  private ExecutorService service;

  @Override
  public void registerFeature(final MCAVSandbox plugin, final AnnotationParser<CommandSender> parser) {
    super.registerFeature(plugin, parser);
    this.service = Executors.newVirtualThreadPerTaskExecutor();
  }

  @Override
  public void shutdown() {
    super.shutdown();
    ExecutorUtils.shutdownExecutorGracefully(this.service);
  }

  @Override
  protected void handleLeftClick(final VMPlayer player, final int x, final int y) {
    player.moveMouse(x, y);
    player.updateMouseButton(1, true);
    player.updateMouseButton(1, false);
  }

  @Override
  protected void handleRightClick(final VMPlayer player, final int x, final int y) {
    player.moveMouse(x, y);
    player.updateMouseButton(3, true);
    player.updateMouseButton(3, false);
  }

  @Override
  protected void handleTextInput(final VMPlayer player, final String text) {
    player.type(text);
  }

  @Override
  protected void releasePlayer() {
    if (this.player == null) {
      return;
    }
    this.player.release();
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

  @Command("mcav vm create <playerSelector> <browserResolution> <blockDimensions> <mapId> <ditheringAlgorithm> <architecture> <flags>")
  @Permission("mcav.command.vm.create")
  @CommandDescription("mcav.command.vm.create.info")
  public void playVM(
    final CommandSender sender,
    final MultiplePlayerSelector playerSelector,
    @Argument(suggestions = "resolutions") @Quoted final String browserResolution,
    @Argument(suggestions = "dimensions") @Quoted final String blockDimensions,
    @Argument(suggestions = "ids") @Range(min = "0") final int mapId,
    final DitheringArgument ditheringAlgorithm,
    final VMPlayer.Architecture architecture,
    @Greedy final String flags
  ) {
    final Pair<Integer, Integer> resolution;
    final Pair<Integer, Integer> dimensions;
    try {
      resolution = ArgumentUtils.parseDimensions(browserResolution);
      dimensions = ArgumentUtils.parseDimensions(blockDimensions);
    } catch (final IllegalArgumentException e) {
      sender.sendMessage(Message.UNSUPPORTED_DIMENSION.build());
      return;
    }

    if (this.player != null) {
      try {
        this.releasePlayer();
        this.player = null;
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
    final VideoMetadata metadata = VideoMetadata.of(resolutionWidth, resolutionHeight);
    final VMConfiguration config = this.parseVMOptions(flags);

    sender.sendMessage(Message.VM_LOADING.build());
    try {
      this.player = VMPlayer.vm();
      this.player.startAsync(pipeline, architecture, config, metadata, this.service)
        .exceptionally(throwable -> handleException(sender, throwable))
        .thenRun(() -> sender.sendMessage(Message.VM_CREATE.build()));
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
