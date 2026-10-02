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
 * Identifies capture devices by nonnegative integer index for the JavaCV device player.
 *
 * <p>{@link me.brandonli.mcav.media.source.device.DeviceSource#device(int)} creates an immutable description;
 * it does not enumerate or open devices. Index zero commonly selects the default camera, but availability and
 * index assignment depend on the host. The detector accepts nonnegative decimal integers that fit in an
 * {@code int}. Use {@link me.brandonli.mcav.media.player.multimedia.VideoPlayer#device()} to open the source
 * and release that player when finished. Sources and the default stateless detector can be shared across threads.
 *
 * <p>Reference parameters and return values are non-null unless marked {@code @Nullable};
 * nullable values and their meanings are described on the corresponding members.
 */
package me.brandonli.mcav.media.source.device;
