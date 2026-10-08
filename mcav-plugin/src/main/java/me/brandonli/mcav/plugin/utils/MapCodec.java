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
package me.brandonli.mcav.plugin.utils;

/**
 * The {@code --codec} flag of the commands that draw on a wall of maps ({@code /mcav video map}, {@code /mcav image
 * map}, {@code /mcav browser create}, {@code /mcav vm create} and {@code /mcav vnc create}): how the picture reaches
 * the players. A command without the flag uses {@code mcv2.default-codec} of the configuration. Block, chat, entity,
 * scoreboard and hologram outputs draw no maps, so the flag does not apply to them.
 */
public enum MapCodec {
  /** Map colours, dithered: every client shows them, at 128 by 128 pixels a map. */
  DITHER,
  /**
   * MCV2: the picture is encoded for the MCV2 resource pack, which decodes it over the wall at the resolution the
   * command asked for. A player whose client has not loaded the pack, or who declined it, sees the dithered maps.
   */
  MCV2,
}
