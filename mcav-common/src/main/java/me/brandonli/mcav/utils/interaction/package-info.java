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
/**
 * Encodes special keys and mouse actions understood by interactive players.
 *
 * <p>{@link me.brandonli.mcav.utils.interaction.KeyUtils#replaceKeysWithKeyCodes(String)} converts named tokens
 * such as {@code "Hello{ENTER}"} into text containing private-use key characters. Names are case-insensitive,
 * unrecognized tokens remain literal and a doubled opening brace escapes a literal brace. The helper does not
 * send input itself; pass the result to the appropriate browser, VNC or VM player.
 *
 * <p>{@link me.brandonli.mcav.utils.interaction.MouseClick} distinguishes click, double-click, hold and release.
 * Coordinate scaling and which actions work while paused depend on the player. Enum values and stateless helpers
 * can be shared across threads; they own no input devices or native resources.
 *
 * <p>Reference parameters and return values are non-null unless marked {@code @Nullable};
 * nullable values and their meanings are described on the corresponding members.
 */
package me.brandonli.mcav.utils.interaction;
