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

import static java.util.Objects.requireNonNull;

import java.util.Collection;
import java.util.UUID;
import me.brandonli.mcav.media.config.ScoreboardConfiguration;
import me.brandonli.mcav.utils.ChatUtils;
import me.brandonli.mcav.utils.PacketUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.world.scores.PlayerTeam;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Team;

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
  private final Team[] teamLines;

  ScoreboardImage(final ScoreboardConfiguration configuration) {
    this.configuration = configuration;
    this.teamLines = new Team[configuration.getLines()];
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void release() {
    for (final Team team : this.teamLines) {
      team.unregister();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void displayImage(final StaticImage data) {
    this.release();
    final int lines = this.configuration.getLines();
    final Collection<UUID> viewers = this.configuration.getViewers();
    final ScoreboardManager scoreboardManager = requireNonNull(Bukkit.getScoreboardManager());
    final Scoreboard scoreboard = scoreboardManager.getNewScoreboard();
    for (int i = 0; i < lines; i++) {
      final UUID random = UUID.randomUUID();
      final String name = random.toString();
      final Team team = scoreboard.registerNewTeam(name);
      for (final UUID viewer : viewers) {
        final Player player = Bukkit.getPlayer(viewer);
        if (player == null) {
          continue;
        }
        final String viewerName = viewer.toString();
        team.addEntry(viewerName);
      }
      this.teamLines[i] = team;
    }
    final String character = this.configuration.getCharacter();
    final int width = this.configuration.getWidth();
    data.resize(width, lines);
    final int[] resizedData = data.getAllPixels();
    for (int i = 0; i < lines; i++) {
      final Team team = this.teamLines[i];
      final PlayerTeam playerTeam = (PlayerTeam) team;
      final Component prefix = ChatUtils.createLine(resizedData, character, width, i);
      playerTeam.setPlayerPrefix(prefix);

      final ClientboundSetPlayerTeamPacket packet = ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(playerTeam, false);
      PacketUtils.sendPackets(viewers, packet);
    }
  }
}
