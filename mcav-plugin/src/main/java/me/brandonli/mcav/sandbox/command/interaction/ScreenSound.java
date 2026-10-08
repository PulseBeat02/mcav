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
package me.brandonli.mcav.sandbox.command.interaction;

import me.brandonli.mcav.sandbox.utils.AudioArgument;
import org.bukkit.entity.Player;

/**
 * Where the sound of a screen plays, and who hears it: the sound of a browser or of a virtual machine.
 */
final class ScreenSound {

  private final AudioArgument type;
  private final Player[] viewers;

  /**
   * Constructs the sound of a screen.
   *
   * @param type    the audio output
   * @param viewers the players who see the screen
   */
  ScreenSound(final AudioArgument type, final Player[] viewers) {
    this.type = type;
    this.viewers = viewers.clone();
  }

  AudioArgument getType() {
    return this.type;
  }

  Player[] getViewers() {
    return this.viewers.clone();
  }
}
