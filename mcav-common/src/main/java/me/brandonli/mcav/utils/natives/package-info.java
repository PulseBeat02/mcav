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
 * Converts sample byte order and represents failures to extract or load native libraries.
 *
 * <p>{@link me.brandonli.mcav.utils.natives.ByteUtils} copies the remaining samples of input buffers into new
 * heap byte buffers ready for reading. It does not consume inputs; byte-buffer conversions interpret complete
 * 16-bit samples according to the source buffer's declared order and preserve a final unmatched byte unchanged.
 * Callers keep ownership of all buffers and must prevent concurrent input mutation during conversion.
 *
 * <p>{@link me.brandonli.mcav.utils.natives.NativeLoadingException} retains an optional underlying failure so
 * applications can report an unavailable native feature. The stateless conversion helpers are safe for independent
 * buffers on concurrent threads.
 *
 * <p>Reference parameters and return values are non-null unless marked {@code @Nullable};
 * nullable values and their meanings are described on the corresponding members.
 */
package me.brandonli.mcav.utils.natives;
