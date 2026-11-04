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
package me.brandonli.mcav.bukkit.media.mcv2;

import java.util.Collection;
import java.util.UUID;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2.Pool;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2.Settings;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.block.BlockFace;

/**
 * Builds MCV2 screen configurations. The viewers, the origin, the facing, the map id and the wall size are
 * required.
 *
 * <p>Builders are mutable and not thread-safe. Numeric setters generally defer validation to {@link #build()}.
 * Origin, facing and outline-color setters validate immediately. A zero video dimension selects 128 times the
 * corresponding wall dimension, which {@link #build()} refuses past the codec's 4096-pixel limit: a wall of more than
 * 32 maps on a side needs a smaller video size.
 *
 * @since 1.0.0
 */
public interface Mcv2ConfigurationBuilder {
  /**
   * Sets the players who watch the screen. The collection is not copied.
   *
   * @param viewers the UUIDs of the viewers
   * @return this builder
   * @throws NullPointerException if {@code viewers} is null
   * @since 1.0.0
   */
  Mcv2ConfigurationBuilder viewers(final Collection<UUID> viewers);

  /**
   * Sets the block the top-left frame of the wall hangs in.
   *
   * <p>The location is copied to block coordinates; subsequent caller mutations do not move the screen.
   *
   * @param origin the location of that block, in a world
   * @return this builder
   * @throws IllegalArgumentException if the origin has no world
   * @throws NullPointerException     if {@code origin} is null
   * @since 1.0.0
   */
  Mcv2ConfigurationBuilder origin(final Location origin);

  /**
   * Sets the direction the frames of the wall face.
   *
   * @param facing north, south, east or west
   * @return this builder
   * @throws IllegalArgumentException if the direction is not horizontal and cardinal
   * @throws NullPointerException     if {@code facing} is null
   * @since 1.0.0
   */
  Mcv2ConfigurationBuilder facing(final BlockFace facing);

  /**
   * Sets the id of the top-left map of the wall.
   *
   * @param map the first map id, not negative
   * @return this builder
   * @since 1.0.0
   */
  Mcv2ConfigurationBuilder map(final int map);

  /**
   * Sets the width of the wall in maps.
   *
   * @param columns 1 to {@link Mcv2Configuration#MAXIMUM_SIDE}
   * @return this builder
   * @since 1.0.0
   */
  Mcv2ConfigurationBuilder columns(final int columns);

  /**
   * Sets the height of the wall in maps.
   *
   * @param rows 1 to {@link Mcv2Configuration#MAXIMUM_SIDE}
   * @return this builder
   * @since 1.0.0
   */
  Mcv2ConfigurationBuilder rows(final int rows);

  /**
   * Sets the size of the encoded video. Frames are resized to it before they are encoded.
   *
   * @param width  1 to 4096 pixels, or 0 for the wall's native width, 128 pixels a map, which a wall of more than 32
   *               columns takes past 4096
   * @param height 1 to 4096 pixels, or 0 for the wall's native height, 128 pixels a map, which a wall of more than 32
   *               rows takes past 4096
   * @return this builder
   * @since 1.0.0
   */
  Mcv2ConfigurationBuilder video(final int width, final int height);

  /**
   * Sets the id of the map carrying the first page of every frame.
   *
   * @param pageMap the first page map id, not negative
   * @return this builder
   * @since 1.0.0
   */
  Mcv2ConfigurationBuilder pageMap(final int pageMap);

  /**
   * Sets how many pages a frame may have. Behind every map of the wall hangs one page's frame, so a wall carries at
   * most as many slots as it has maps; {@link #build()} refuses more.
   *
   * @param pageSlots 1 to {@link Mcv2Configuration#MAXIMUM_PAGE_SLOTS} and at most the wall's maps, or 0 for the
   *                  default: {@value Mcv2Configuration#MAXIMUM_PAGE_SLOTS}, or the wall's maps when it has fewer
   * @return this builder
   * @since 1.0.0
   */
  Mcv2ConfigurationBuilder pageSlots(final int pageSlots);

  /**
   * Sets the stream id every page and anchor carries, which tells the resource pack which of its screens they are.
   *
   * @param streamId 0 to {@value Mcv2Configuration#MAXIMUM_STREAM_ID}: an anchor carries it in two six-bit symbols
   * @return this builder
   * @since 1.0.0
   */
  Mcv2ConfigurationBuilder streamId(final long streamId);

  /**
   * Sets the id of the screen's first frame; defaults to 0.
   *
   * @param firstFrameId an unsigned 32-bit value
   * @return this builder
   * @since 1.0.0
   */
  Mcv2ConfigurationBuilder firstFrameId(final long firstFrameId);

  /**
   * Sets the most frames a second the screen shows; defaults to {@link Mcv2Configuration#DEFAULT_MAXIMUM_FRAME_RATE}.
   *
   * @param maximumFrameRate a finite rate from 0 through {@value Mcv2Configuration#MAXIMUM_FRAME_RATE} frames per
   *                         second; 0 accepts every source frame
   * @return this builder
   * @since 1.0.0
   */
  Mcv2ConfigurationBuilder maximumFrameRate(final double maximumFrameRate);

  /**
   * Sets the encoder profile; defaults to {@link Settings#DEFAULT}. A screen that falls behind steps to
   * {@link Settings#FAST}, then lowers its frame rate and video size before using dithered maps.
   *
   * @param settings the settings
   * @return this builder
   * @throws NullPointerException if {@code settings} is null
   * @since 1.0.0
   */
  Mcv2ConfigurationBuilder settings(final Settings settings);

  /**
   * Sets the team color of the hidden page frames; defaults to dark purple.
   *
   * @param outlineColor the color, not black: an outline color of zero means no outline, so the client would not
   *                     run the pack's post chain
   * @return this builder
   * @throws IllegalArgumentException if the color is black
   * @throws NullPointerException     if {@code outlineColor} is null
   * @since 1.0.0
   */
  Mcv2ConfigurationBuilder outlineColor(final NamedTextColor outlineColor);

  /**
   * Sets how much video a viewer's connection may have left to write for the viewer to be sent another frame;
   * defaults to {@link Mcv2Configuration#DEFAULT_BACKLOG_LIMIT}.
   *
   * @param backlogLimit the nonnegative threshold in estimated map-update bytes, including patch overhead;
   *                     zero allows a new frame only once the tracked backlog has drained
   * @return this builder
   * @since 1.0.0
   */
  Mcv2ConfigurationBuilder backlogLimit(final long backlogLimit);

  /**
   * Sets the cap on the bytes a viewer's operating system may hold unsent, {@link Mcv2Configuration#DEFAULT_UNSENT_LIMIT}
   * unless set.
   *
   * @param unsentLimit the cap in bytes, or 0 to leave the system's own, for example with no backlog limit either
   * @return this builder
   * @since 1.0.0
   */
  Mcv2ConfigurationBuilder unsentLimit(final int unsentLimit);

  /**
   * Sets the encoder budget the screen encodes in, instead of the server's shared budget, {@link Pool#shared()}.
   * Screens that encode at the same time share a budget's threads.
   *
   * @param encoderPool the budget
   * @return this builder
   * @throws NullPointerException if {@code encoderPool} is null
   * @since 1.0.0
   */
  Mcv2ConfigurationBuilder encoderPool(final Pool encoderPool);

  /**
   * Builds the configuration. The builder is not changed.
   *
   * @return the configuration
   * @throws NullPointerException     if the viewers, the origin or the facing were not set
   * @throws IllegalArgumentException if a value is out of range, or the video, at the wall's native size where none was
   *                                  set, is larger than 4096 pixels
   * @since 1.0.0
   */
  Mcv2Configuration build();
}
