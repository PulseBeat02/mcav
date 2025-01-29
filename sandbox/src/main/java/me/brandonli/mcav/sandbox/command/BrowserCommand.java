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

import static java.util.Objects.requireNonNull;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.UUID;
import me.brandonli.mcav.bukkit.media.config.MapConfiguration;
import me.brandonli.mcav.bukkit.media.result.MapResult;
import me.brandonli.mcav.media.player.driver.BrowserPlayer;
import me.brandonli.mcav.media.player.metadata.VideoMetadata;
import me.brandonli.mcav.media.player.pipeline.filter.video.VideoFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.DitherFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.DitherAlgorithm;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.media.source.BrowserSource;
import me.brandonli.mcav.sandbox.MCAVSandbox;
import me.brandonli.mcav.sandbox.locale.Message;
import me.brandonli.mcav.sandbox.utils.ArgumentUtils;
import me.brandonli.mcav.sandbox.utils.DitheringArgument;
import me.brandonli.mcav.utils.immutable.Pair;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.incendo.cloud.annotation.specifier.Quoted;
import org.incendo.cloud.annotation.specifier.Range;
import org.incendo.cloud.annotations.*;

public final class BrowserCommand implements AnnotationCommandFeature {

  private @Nullable BrowserPlayer browser;

  @Override
  public void registerFeature(final MCAVSandbox plugin, final AnnotationParser<CommandSender> parser) {}

  @Command("mcav browser release")
  @Permission("mcav.browser.release")
  @CommandDescription("mcav.command.browser.release.info")
  public void playBrowser(final CommandSender player) {
    if (this.browser != null) {
      try {
        this.browser.release();
        this.browser = null;
      } catch (final Exception e) {
        throw new AssertionError(e);
      }
    }
    player.sendMessage(Message.RELEASE_BROWSER.build());
  }

  @Command("mcav browser create <browserResolution> <blockDimensions> <mapId> <ditheringAlgorithm> <url>")
  @Permission("mcav.command.browser.create")
  @CommandDescription("mcav.command.browser.create.info")
  public void playBrowser(
    final CommandSender player,
    @Argument(suggestions = "resolutions") @Quoted final String browserResolution,
    @Argument(suggestions = "dimensions") @Quoted final String blockDimensions,
    @Argument(suggestions = "ids") @Range(min = "0") final int mapId,
    final DitheringArgument ditheringAlgorithm,
    @Quoted final String url
  ) {
    final Pair<Integer, Integer> resolution;
    final Pair<Integer, Integer> dimensions;
    try {
      resolution = ArgumentUtils.parseDimensions(browserResolution);
      dimensions = ArgumentUtils.parseDimensions(blockDimensions);
    } catch (final IllegalArgumentException e) {
      player.sendMessage(Message.UNSUPPORTED_DIMENSION.build());
      return;
    }

    boolean failed = false;
    URI uri = null;
    try {
      uri = new URI(url);
    } catch (final URISyntaxException e) {
      failed = true;
    }
    if (failed) {
      player.sendMessage(Message.UNSUPPORTED_URL.build());
      return;
    }
    requireNonNull(uri);

    if (this.browser != null) {
      try {
        this.browser.release();
        this.browser = null;
      } catch (final Exception e) {
        throw new AssertionError(e);
      }
    }

    final int resolutionWidth = resolution.getFirst();
    final int resolutionHeight = resolution.getSecond();
    final int blockWidth = dimensions.getFirst();
    final int blockHeight = dimensions.getSecond();

    final Collection<UUID> players = Bukkit.getOnlinePlayers().stream().map(Player::getUniqueId).toList();
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
    final BrowserSource source = BrowserSource.uri(uri, metadata);
    try {
      this.browser = BrowserPlayer.defaultChrome();
      this.browser.start(pipeline, source);
    } catch (final Exception e) {
      throw new AssertionError(e);
    }

    player.sendMessage(Message.START_BROWSER.build());
  }
}
