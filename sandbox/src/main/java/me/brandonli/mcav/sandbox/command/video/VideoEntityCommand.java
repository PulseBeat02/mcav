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
package me.brandonli.mcav.sandbox.command.video;

import java.util.Collection;
import java.util.UUID;
import me.brandonli.mcav.bukkit.media.config.EntityConfiguration;
import me.brandonli.mcav.bukkit.media.result.EntityResult;
import me.brandonli.mcav.media.player.pipeline.builder.PipelineBuilder;
import me.brandonli.mcav.media.player.pipeline.filter.FunctionalVideoFilter;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.sandbox.MCAVSandbox;
import me.brandonli.mcav.sandbox.utils.ArgumentUtils;
import me.brandonli.mcav.sandbox.utils.AudioArgument;
import me.brandonli.mcav.sandbox.utils.PlayerArgument;
import me.brandonli.mcav.utils.immutable.Pair;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.scheduler.BukkitScheduler;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.incendo.cloud.annotation.specifier.Quoted;
import org.incendo.cloud.annotations.*;
import org.incendo.cloud.bukkit.data.MultiplePlayerSelector;

public final class VideoEntityCommand extends AbstractVideoCommand {

  private MCAVSandbox plugin;

  @Override
  public void registerFeature(final MCAVSandbox plugin, final AnnotationParser<CommandSender> parser) {
    super.registerFeature(plugin, parser);
    this.plugin = plugin;
  }

  @Command("mcav video entity <playerSelector> <playerType> <audioType> <videoResolution> <character> <location> <flags> <mrl>")
  @Permission("mcav.command.video.entity")
  @CommandDescription("mcav.command.video.entity.info")
  public void playEntityVideo(
    final CommandSender player,
    final MultiplePlayerSelector playerSelector,
    final PlayerArgument playerType,
    final AudioArgument audioType,
    @Argument(suggestions = "dimensions") @Quoted final String videoResolution,
    @Argument(suggestions = "chat-characters") @Quoted final String character,
    final Location location,
    @Quoted final String flags,
    @Quoted final String mrl
  ) {
    final Collection<UUID> players = ArgumentUtils.parsePlayerSelectors(playerSelector);
    final VideoConfigurationProvider configProvider = resolution ->
      this.constructEntityConfiguration(resolution, character, location, players);
    this.playVideo(configProvider, player, playerSelector, playerType, audioType, videoResolution, mrl, flags);
  }

  @Override
  public VideoPipelineStep createVideoFilter(final Pair<Integer, Integer> resolution, final VideoConfigurationProvider configProvider) {
    final EntityConfiguration configuration = (EntityConfiguration) configProvider.buildConfiguration(resolution);
    final FunctionalVideoFilter result = new EntityResult(configuration);
    final BukkitScheduler scheduler = Bukkit.getScheduler();
    scheduler.runTask(this.plugin, result::start);
    this.manager.setFilter(result);
    return PipelineBuilder.video().then(result).build();
  }

  private EntityConfiguration constructEntityConfiguration(
    final Pair<@NonNull Integer, @NonNull Integer> resolution,
    final String character,
    final Location location,
    final Collection<UUID> players
  ) {
    return EntityConfiguration.builder()
      .viewers(players)
      .entityWidth(resolution.getFirst())
      .entityHeight(resolution.getSecond())
      .character(character)
      .position(location)
      .build();
  }
}
