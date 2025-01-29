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
package me.brandonli.mcav.media.image;

import static net.kyori.adventure.text.Component.empty;

import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import me.brandonli.mcav.media.config.ScoreboardConfiguration;
import me.brandonli.mcav.utils.ChatUtils;
import me.brandonli.mcav.utils.PacketUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * The ScoreboardImage class is responsible for managing and displaying scoreboard-style images based
 * on the provided configuration. It implements the DisplayableImage interface, allowing it to manage
 * reusable scoreboard resources effectively.
 * <p>
 * This class leverages teams within a scoreboard to display images by manipulating prefixes with character
 * sequences that represent pixel data. The visual representation is configured using the ScoreboardConfiguration
 * parameters, including dimensions, characters, and viewers.
 * <p>
 * Instances of this class manage the lifecycle of a scoreboard image, including creating, updating, and releasing
 * associated resources.
 */
public class ScoreboardImage implements DisplayableImage {

  private final ScoreboardConfiguration configuration;
  private final List<String> teamLines;

  ScoreboardImage(final ScoreboardConfiguration configuration) {
    this.configuration = configuration;
    this.teamLines = new ArrayList<>();
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
  public void displayImage(final StaticImage data) {
    this.release();
    final int lines = this.configuration.getLines();
    final String character = this.configuration.getCharacter();
    final int width = this.configuration.getWidth();
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
