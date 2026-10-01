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
 * MCV2 encoder settings, shared CPU budgets, frame/tree serialization and file-stream encoding.
 *
 * <p>Use {@link me.brandonli.mcav.bukkit.media.mcv2.encode.EncoderPool} to bound CPU use, create one
 * {@link me.brandonli.mcav.bukkit.media.mcv2.encode.Mcv2Encoder} per stream and run its work through the pool.
 * RGB inputs are three bytes per pixel in row order. Settings are immutable; encoder state is serialized per stream,
 * apart from the documented begin/finish overlap. Finish every pending frame before transmitting it. Encoders borrow
 * their pool; callers close private pools after all users stop, while individual screens leave the shared pool open.
 * File encoding closes its frame reader but leaves the caller's output and budget open. Native acceleration is optional.
 *
 * <p>Reference parameters and return values are non-null unless marked {@code @Nullable};
 * nullable values and their meanings are described on the corresponding members.
 */
package me.brandonli.mcav.bukkit.media.mcv2.encode;
