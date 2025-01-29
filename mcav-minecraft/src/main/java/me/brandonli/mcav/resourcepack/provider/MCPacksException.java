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
package me.brandonli.mcav.resourcepack.provider;

/**
 * Represents a custom exception specific to MC Packs, used for error handling
 * within the MC Packs functionality of a resource pack hosting system.
 * <p>
 * This exception is a subclass of {@link AssertionError}, allowing it to signal
 * assertion-like failures or conditions that are not expected to occur during
 * normal application execution. It is typically utilized to indicate problems
 * or violations within the context of MC Packs operations.
 * <p>
 * The exception includes a message that provides detailed information about
 * the nature of the error when it is instantiated.
 */
public class MCPacksException extends AssertionError {

  private static final long serialVersionUID = -6775463807604247034L;

  public MCPacksException(final String message) {
    super(message);
  }
}
