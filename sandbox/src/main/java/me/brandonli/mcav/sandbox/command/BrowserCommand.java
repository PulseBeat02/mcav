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

import com.mojang.brigadier.context.CommandContext;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.stream.Stream;
import me.brandonli.mcav.media.player.browser.BrowserPlayer;
import me.brandonli.mcav.media.player.combined.pipeline.filter.video.VideoFilter;
import me.brandonli.mcav.media.player.combined.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.media.player.metadata.VideoMetadata;
import me.brandonli.mcav.media.source.BrowserSource;
import me.brandonli.mcav.media.video.DitherFilter;
import me.brandonli.mcav.media.video.DitherResultStep;
import me.brandonli.mcav.media.video.dither.algorithm.DitherAlgorithm;
import me.brandonli.mcav.media.video.dither.algorithm.builder.ErrorDiffusionDitherBuilder;
import me.brandonli.mcav.media.video.dither.palette.Palette;
import me.brandonli.mcav.media.video.result.MapResult;
import me.brandonli.mcav.sandbox.MCAV;
import me.brandonli.mcav.sandbox.locale.AudienceProvider;
import me.brandonli.mcav.sandbox.locale.Message;
import me.brandonli.mcav.sandbox.utils.ArgumentUtils;
import me.brandonli.mcav.utils.IOUtils;
import me.brandonli.mcav.utils.immutable.Pair;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.incendo.cloud.annotation.specifier.Quoted;
import org.incendo.cloud.annotation.specifier.Range;
import org.incendo.cloud.annotations.*;
import org.incendo.cloud.annotations.suggestion.Suggestions;

public final class BrowserCommand implements AnnotationCommandFeature {

  private BukkitAudiences audiences;
  private BrowserPlayer browser;

  @Override
  public void registerFeature(final MCAV plugin, final AnnotationParser<CommandSender> parser) {
    final AudienceProvider provider = plugin.getAudience();
    this.audiences = provider.retrieve();
  }

  @Command("mcav browser <browserResolution> <blockDimensions> <mapId> <url>")
  @Permission("mcav.browser")
  @CommandDescription("mcav.command.browser.info")
  public void playBrowser(
    final Player player,
    @Argument(suggestions = "resolutions") @Quoted final String browserResolution,
    @Argument(suggestions = "dimensions") @Quoted final String blockDimensions,
    @Argument(suggestions = "id") @Range(min = "0", max = "4294967295") final int mapId,
    @Quoted final String url
  ) {
    final Audience audience = this.audiences.sender(player);
    final Pair<Integer, Integer> resolution;
    final Pair<Integer, Integer> dimensions;
    try {
      resolution = ArgumentUtils.parseDimensions(browserResolution);
      dimensions = ArgumentUtils.parseDimensions(blockDimensions);
    } catch (final IllegalArgumentException e) {
      audience.sendMessage(Message.DIMENSION_ERROR.build());
      return;
    }

    boolean failed = false;
    URI uri = null;
    try {
      uri = new URI(url);
      if (!IOUtils.checkValidUrl(uri)) {
        failed = true;
      }
    } catch (final URISyntaxException e) {
      failed = true;
    }
    if (failed) {
      audience.sendMessage(Message.URL_ERROR.build());
      return;
    }
    requireNonNull(uri);

    if (this.browser != null) {
      try {
        this.browser.release();
      } catch (final Exception e) {
        throw new AssertionError(e);
      }
    }

    final int resolutionWidth = resolution.getFirst();
    final int resolutionHeight = resolution.getSecond();
    final int blockWidth = dimensions.getFirst();
    final int blockHeight = dimensions.getSecond();

    final DitherResultStep result = MapResult.builder().map(mapId).mapBlockHeight(blockHeight).mapBlockWidth(blockWidth).build();
    final DitherAlgorithm algorithm = DitherAlgorithm.errorDiffusion()
      .withAlgorithm(ErrorDiffusionDitherBuilder.Algorithm.FILTER_LITE)
      .withPalette(Palette.DEFAULT)
      .build();
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
  }

  @Suggestions("id")
  public Stream<Integer> suggestId(final CommandContext<CommandSender> ctx, final String input) {
    return Stream.of(0, 5, 10, 100, 1000, 10000, 100000);
  }

  @Suggestions("dimensions")
  public Stream<String> suggestDimensions(final CommandContext<CommandSender> ctx, final String input) {
    return Stream.of("4x4", "5x5", "16x9", "32x18");
  }

  @Suggestions("resolutions")
  public Stream<String> suggestResolutions(final CommandContext<CommandSender> ctx, final String input) {
    return Stream.of("512x512", "640x640", "1280x720", "1920x1080");
  }
}
