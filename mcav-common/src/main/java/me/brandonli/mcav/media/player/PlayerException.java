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
package me.brandonli.mcav.media.player;

import java.io.Serial;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Thrown when a player cannot open, decode, or control its media, for example because the media cannot be reached, an
 * audio device cannot be opened, or a remote server refuses the connection.
 *
 * <p>This is a plain {@link RuntimeException}: the failures it reports come from the media, the network, or the
 * machine, not from calling a player in the wrong state, so neither {@link IllegalStateException} nor
 * {@link IllegalArgumentException} would describe them. Callers that can recover, for example by trying another
 * source, catch it explicitly; everyone else can let it propagate like any other unchecked exception.
 */
public class PlayerException extends RuntimeException {

  @Serial
  private static final long serialVersionUID = 5422221049222805060L;

  /**
   * Constructs a new exception with a detail message and no cause.
   *
   * @param message the detail message, which describes what the player could not do
   */
  public PlayerException(final @Nullable String message) {
    super(message);
  }

  /**
   * Constructs a new exception with a detail message and the failure that caused it.
   *
   * @param message the detail message, which describes what the player could not do
   * @param cause   the underlying failure, or null if it is unknown
   */
  public PlayerException(final @Nullable String message, final @Nullable Throwable cause) {
    super(message, cause);
  }
}
