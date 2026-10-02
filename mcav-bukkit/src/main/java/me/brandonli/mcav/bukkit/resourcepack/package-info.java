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
 * Builds resource-pack archives containing streamed sounds and caller-supplied files or bytes.
 *
 * <p>Create a {@link me.brandonli.mcav.bukkit.resourcepack.SimpleResourcePack}, set its metadata, register its
 * contents, then write the archive. The mutable builder is not thread-safe. Byte content is copied; file paths
 * are retained and read while zipping. The caller owns the resulting archive and can expose it through the
 * hosting providers. Archive replacement uses a temporary file and an atomic move where supported.
 *
 * <p>Reference parameters and return values are non-null unless marked {@code @Nullable};
 * nullable values and their meanings are described on the corresponding members.
 */
package me.brandonli.mcav.bukkit.resourcepack;
