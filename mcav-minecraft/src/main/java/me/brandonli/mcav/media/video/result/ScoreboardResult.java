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
package me.brandonli.mcav.media.video.result;

import static net.kyori.adventure.text.Component.empty;

import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams;
import com.google.common.base.Preconditions;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import me.brandonli.mcav.media.image.StaticImage;
import me.brandonli.mcav.media.player.metadata.VideoMetadata;
import me.brandonli.mcav.utils.ChatUtils;
import me.brandonli.mcav.utils.PacketUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * Represents a functional video filter that uses Minecraft scoreboard mechanics to display
 * custom data to specific viewers. The
 */
public class ScoreboardResult implements FunctionalVideoFilter {

  private final Collection<UUID> viewers;
  private final String character;
  private final int lines;
  private final int width;
  private final List<String> teamLines;

  private ScoreboardResult(final Builder<?> builder) {
    this.viewers = builder.viewers;
    this.character = builder.character;
    this.lines = builder.lines;
    this.width = builder.width;
    this.teamLines = new ArrayList<>();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void start() {
    for (int i = 0; i < this.lines; i++) {
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
      PacketUtils.sendPackets(this.viewers, create);
      final String[] entries = this.viewers.stream().map(UUID::toString).toArray(String[]::new);
      final WrapperPlayServerTeams add = new WrapperPlayServerTeams(name, WrapperPlayServerTeams.TeamMode.ADD_ENTITIES, teamInfo, entries);
      PacketUtils.sendPackets(this.viewers, add);
      this.teamLines.add(name);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void release() {
    for (int i = 0; i < this.lines; i++) {
      final String teamName = this.teamLines.get(i);
      final WrapperPlayServerTeams remove = new WrapperPlayServerTeams(
        teamName,
        WrapperPlayServerTeams.TeamMode.REMOVE,
        (WrapperPlayServerTeams.ScoreBoardTeamInfo) null
      );
      PacketUtils.sendPackets(this.viewers, remove);
    }
  }

  /**
   * Builder class for constructing instances of {@link ScoreboardResult}.
   * This class provides methods to set the properties of the scoreboard result.
   */
  public static final class ScoreboardResultBuilder extends Builder<ScoreboardResultBuilder> {

    @Override
    protected ScoreboardResultBuilder self() {
      return this;
    }
  }

  /**
   * Creates a new builder instance for constructing {@link ScoreboardResult} objects.
   * This method provides access to a {@link ScoreboardResultBuilder} for setting specific properties.
   *
   * @return a {@link Builder} instance configured for {@link ScoreboardResult}.
   */
  public static Builder<?> builder() {
    return new ScoreboardResultBuilder();
  }

  /**
   * Abstract builder class for creating instances of {@link ScoreboardResult}.
   * This class provides methods to set the properties of the scoreboard result.
   *
   * @param <T> the type of the builder
   */
  public abstract static class Builder<T extends Builder<T>> {

    private Collection<UUID> viewers;
    private String character;
    private int lines;
    private int width;

    protected abstract T self();

    /**
     * Sets the viewers for the builder.
     *
     * @param viewers the collection of UUIDs representing the viewers
     * @return the builder instance for method chaining
     */
    public T viewers(final Collection<UUID> viewers) {
      this.viewers = viewers;
      return this.self();
    }

    /**
     * Sets the character to be used and returns the current builder instance.
     *
     * @param character the character to be used
     * @return the current builder instance
     */
    public T character(final String character) {
      this.character = character;
      return this.self();
    }

    /**
     * Sets the number of lines and returns the builder instance.
     *
     * @param lines the number of lines to be set; must be a positive integer
     * @return the current builder instance
     */
    public T lines(final int lines) {
      this.lines = lines;
      return this.self();
    }

    /**
     * Sets the width property for the builder.
     *
     * @param width the width value to be set; must be a positive integer
     * @return the builder instance for method chaining
     */
    public T width(final int width) {
      this.width = width;
      return this.self();
    }

    /**
     * Builds and returns an instance of {@link FunctionalVideoFilter}.
     * This method performs validation checks to ensure all required fields have been set.
     *
     * @return a new instance of {@link FunctionalVideoFilter} with the configured properties.
     * @throws NullPointerException     if any mandatory field (viewers or character) is null.
     * @throws IllegalArgumentException if the lines or width values are non-positive.
     */
    public FunctionalVideoFilter build() {
      Preconditions.checkNotNull(this.viewers);
      Preconditions.checkNotNull(this.character);
      Preconditions.checkArgument(this.lines > 0, "Lines must be positive");
      Preconditions.checkArgument(this.width > 0, "Width must be positive");
      return new ScoreboardResult(this);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void applyFilter(final StaticImage data, final VideoMetadata metadata) {
    data.resize(this.width, this.lines);
    final int[] resizedData = data.getAllPixels();
    for (int i = 0; i < this.lines; i++) {
      final String teamName = this.teamLines.get(i);
      final Component prefix = ChatUtils.createLine(resizedData, this.character, this.width, i);
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
      PacketUtils.sendPackets(this.viewers, update);
    }
  }
}
