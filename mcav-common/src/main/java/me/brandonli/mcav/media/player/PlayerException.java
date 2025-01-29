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
package me.brandonli.mcav.media.player;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * PlayerException is a custom exception that extends {@link AssertionError}.
 * It is used to indicate exceptional conditions specifically related to
 * player functionality or operations in the media playback library.
 * <p>
 * This exception can be thrown when an error occurs in the implementation or
 * usage of player-related components and serves as a mechanism for error
 * reporting within the context of the application.
 */
public class PlayerException extends AssertionError {

  private static final long serialVersionUID = 5422221049222805060L;

  public PlayerException(final @Nullable String message) {
    super(message);
  }
}
