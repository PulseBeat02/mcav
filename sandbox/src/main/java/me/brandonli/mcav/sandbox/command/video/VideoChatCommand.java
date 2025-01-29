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
import me.brandonli.mcav.bukkit.media.config.ChatConfiguration;
import me.brandonli.mcav.bukkit.media.result.ChatResult;
import me.brandonli.mcav.bukkit.media.result.FunctionalVideoFilter;
import me.brandonli.mcav.media.player.pipeline.builder.PipelineBuilder;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.sandbox.utils.ArgumentUtils;
import me.brandonli.mcav.sandbox.utils.AudioArgument;
import me.brandonli.mcav.sandbox.utils.PlayerArgument;
import me.brandonli.mcav.utils.immutable.Pair;
import org.bukkit.command.CommandSender;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.incendo.cloud.annotation.specifier.Quoted;
import org.incendo.cloud.annotations.Argument;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.CommandDescription;
import org.incendo.cloud.annotations.Permission;
import org.incendo.cloud.bukkit.data.MultiplePlayerSelector;

public final class VideoChatCommand extends AbstractVideoCommand {

  @Command("mcav video chat <playerSelector> <playerType> <audioType> <videoResolution> <character> <flags> <mrl>")
  @Permission("mcav.command.video.chat")
  @CommandDescription("mcav.command.video.chat.info")
  public void playChatVideo(
    final CommandSender player,
    final MultiplePlayerSelector playerSelector,
    final PlayerArgument playerType,
    final AudioArgument audioType,
    @Argument(suggestions = "dimensions") @Quoted final String videoResolution,
    @Argument(suggestions = "chat-characters") @Quoted final String character,
    @Quoted final String flags,
    @Quoted final String mrl
  ) {
    final Collection<UUID> players = ArgumentUtils.parsePlayerSelectors(playerSelector);
    final VideoConfigurationProvider configProvider = resolution -> this.constructChatConfiguration(resolution, character, players);
    this.playVideo(configProvider, player, playerSelector, playerType, audioType, videoResolution, mrl, flags);
  }

  @Override
  public VideoPipelineStep createVideoFilter(final Pair<Integer, Integer> resolution, final VideoConfigurationProvider configProvider) {
    final ChatConfiguration configuration = (ChatConfiguration) configProvider.buildConfiguration(resolution);
    final FunctionalVideoFilter result = new ChatResult(configuration);
    result.start();
    this.manager.setFilter(result);
    return PipelineBuilder.video().then(result).build();
  }

  private ChatConfiguration constructChatConfiguration(
    final Pair<@NonNull Integer, @NonNull Integer> resolution,
    final String character,
    final Collection<UUID> players
  ) {
    return ChatConfiguration.builder()
      .viewers(players)
      .chatWidth(resolution.getFirst())
      .chatHeight(resolution.getSecond())
      .character(character)
      .build();
  }
}
