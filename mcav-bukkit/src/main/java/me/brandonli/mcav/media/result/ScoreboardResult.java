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
package me.brandonli.mcav.media.result;

import static net.kyori.adventure.text.Component.empty;

import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import me.brandonli.mcav.media.config.ScoreboardConfiguration;
import me.brandonli.mcav.media.image.StaticImage;
import me.brandonli.mcav.media.player.metadata.VideoMetadata;
import me.brandonli.mcav.utils.ChatUtils;
import me.brandonli.mcav.utils.PacketUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * Represents the result of processing a scoreboard using a functional video filter.
 * This class implements the {@link FunctionalVideoFilter} interface and provides specific
 * functionality to manage the lifecycle and application of a video filter configured
 * for scoreboard rendering.
 * <p>
 * ScoreboardResult is responsible for:
 * - Initializing the scoreboard filter using team-based entities and configurations defined in {@link ScoreboardConfiguration}.
 * - Releasing resources and cleaning up the scoreboard elements upon completion.
 * - Applying the functional filter logic to process static images and render scoreboard-related data.
 */
public class ScoreboardResult implements FunctionalVideoFilter {

  private final ScoreboardConfiguration configuration;
  private final List<String> teamLines;

  /**
   * Constructs a new instance of the {@code ScoreboardResult} class using the provided
   * {@code ScoreboardConfiguration}.
   *
   * @param configuration the configuration object that defines the properties of the
   *                      scoreboard, including viewers, character, lines, and width.
   */
  public ScoreboardResult(final ScoreboardConfiguration configuration) {
    this.configuration = configuration;
    this.teamLines = new ArrayList<>();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void start() {
    final int lines = this.configuration.getLines();
    final Collection<UUID> viewers = this.configuration.getViewers();
    for (int i = 0; i < lines; i++) {
      final UUID random = UUID.randomUUID();
      final String name = random.toString();
      final WrapperPlayServerTeams.ScoreBoardTeamInfo teamInfo = new WrapperPlayServerTeams.ScoreBoardTeamInfo(
        empty(),
        empty(),
        empty(),
        WrapperPlayServerTeams.NameTagVisibility.ALWAYS,
        WrapperPlayServerTeams.CollisionRule.ALWAYS,
        NamedTextColor.WHITE,
        WrapperPlayServerTeams.OptionData.ALL
      );
      final WrapperPlayServerTeams create = new WrapperPlayServerTeams(name, WrapperPlayServerTeams.TeamMode.CREATE, teamInfo);
      PacketUtils.sendPackets(viewers, create);
      final String[] entries = viewers.stream().map(UUID::toString).toArray(String[]::new);
      final WrapperPlayServerTeams add = new WrapperPlayServerTeams(name, WrapperPlayServerTeams.TeamMode.ADD_ENTITIES, teamInfo, entries);
      PacketUtils.sendPackets(viewers, add);
      this.teamLines.add(name);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void release() {
    final int lines = this.configuration.getLines();
    final Collection<UUID> viewers = this.configuration.getViewers();
    for (int i = 0; i < lines; i++) {
      final String teamName = this.teamLines.get(i);
      final WrapperPlayServerTeams remove = new WrapperPlayServerTeams(
        teamName,
        WrapperPlayServerTeams.TeamMode.REMOVE,
        (WrapperPlayServerTeams.ScoreBoardTeamInfo) null
      );
      PacketUtils.sendPackets(viewers, remove);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void applyFilter(final StaticImage data, final VideoMetadata metadata) {
    final String character = this.configuration.getCharacter();
    final int width = this.configuration.getWidth();
    final int lines = this.configuration.getLines();
    final Collection<UUID> viewers = this.configuration.getViewers();
    data.resize(width, lines);
    final int[] resizedData = data.getAllPixels();
    for (int i = 0; i < lines; i++) {
      final String teamName = this.teamLines.get(i);
      final Component prefix = ChatUtils.createLine(resizedData, character, width, i);
      final WrapperPlayServerTeams.ScoreBoardTeamInfo teamInfo = new WrapperPlayServerTeams.ScoreBoardTeamInfo(
        empty(),
        prefix,
        empty(),
        WrapperPlayServerTeams.NameTagVisibility.ALWAYS,
        WrapperPlayServerTeams.CollisionRule.ALWAYS,
        NamedTextColor.WHITE,
        WrapperPlayServerTeams.OptionData.ALL
      );
      final WrapperPlayServerTeams update = new WrapperPlayServerTeams(teamName, WrapperPlayServerTeams.TeamMode.UPDATE, teamInfo);
      PacketUtils.sendPackets(viewers, update);
    }
  }
}
