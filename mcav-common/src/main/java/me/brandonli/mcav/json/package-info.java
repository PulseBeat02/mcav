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
 * Shares the library's default Gson configuration.
 *
 * <p>{@link me.brandonli.mcav.json.GsonProvider#getSimple()} returns a shared parser/serializer with Gson's
 * default settings. The Gson instance may be used concurrently, but that does not make mutable objects being
 * serialized or deserialized safe for concurrent mutation. Media metadata models live in
 * {@link me.brandonli.mcav.json.ytdlp.format}.
 *
 * <p>Reference parameters and return values are non-null unless marked {@code @Nullable};
 * nullable values and their meanings are described on the corresponding members.
 */
package me.brandonli.mcav.json;
