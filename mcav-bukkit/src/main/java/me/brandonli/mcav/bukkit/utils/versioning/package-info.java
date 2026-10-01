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
 * Checks the Minecraft version expected by the Bukkit integration before server internals are used.
 *
 * <p>{@link me.brandonli.mcav.bukkit.utils.versioning.ServerEnvironment#checkSupported()} is invoked during
 * module injection and reports unsupported versions with an unchecked state exception. Compatibility is an
 * exact-version or dotted-prefix string check; it does not test individual server capabilities.
 *
 * <p>Reference parameters and return values are non-null unless marked {@code @Nullable};
 * nullable values and their meanings are described on the corresponding members.
 */
package me.brandonli.mcav.bukkit.utils.versioning;
