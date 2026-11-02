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
package me.brandonli.mcav.plugin.command.interaction;

/**
 * Whether the player of an interactive command started, as passed to
 * {@link AbstractInteractiveCommand#createStartMessage(StartOutcome, Throwable)}.
 *
 * <p>This enum is immutable and thread-safe.
 *
 * @since 1.0.0
 */
public enum StartOutcome {
  /**
   * The player started.
   *
   * @since 1.0.0
   */
  STARTED,

  /**
   * The player failed to start, or the start was refused.
   *
   * @since 1.0.0
   */
  FAILED,
}
